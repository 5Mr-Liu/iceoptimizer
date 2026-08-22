package dev.rlcraft.ice.optimizer.compat.optifine;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import dev.rlcraft.ice.optimizer.client.ClientOptimizerRuntime;
import dev.rlcraft.ice.optimizer.client.ModernRendererRuntime;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;

/** Bounded pairing of OptiFine's resolved stage sources into native candidates. */
public final class OptifineShaderSourceBridge {
    private static final String MODULE = "optifine-shader-bridge";
    private static final int VERTEX = 0;
    private static final int GEOMETRY = 1;
    private static final int FRAGMENT = 2;
    private static final int MAX_PROGRAMS = 256;
    private static final long MAX_CAPTURE_BYTES = 32L * 1024L * 1024L;
    private static final int MAX_STAGE_BYTES = 8 * 1024 * 1024;
    private static final Map<Object, Entry> PROGRAMS =
        new IdentityHashMap<Object, Entry>();
    private static final ClassValue<ProgramAccess> ACCESS =
        new ClassValue<ProgramAccess>() {
            @Override protected ProgramAccess computeValue(Class<?> type) {
                return new ProgramAccess(type);
            }
        };
    private static long captureBytes;
    private static long resourceGeneration;
    private static long shaderGeneration;
    private static volatile boolean coreBridgeInstalled;

    private OptifineShaderSourceBridge() {
    }

    public static synchronized boolean installCoreBridge() {
        if (coreBridgeInstalled) return true;
        try {
            ClassLoader loader = OptifineShaderSourceBridge.class.getClassLoader();
            Class<?> bootstrap = Class.forName(
                "dev.rlcraft.ice.hooks.OptifineShaderSourceBootstrap", true, loader);
            Object installed = bootstrap.getMethod("install", Class.class)
                .invoke(null, OptifineShaderSourceBridge.class);
            if (Boolean.TRUE.equals(installed)) {
                coreBridgeInstalled = true;
                return true;
            }
            OptimizerBridge.failure(MODULE,
                new IllegalStateException("Core OptiFine source bridge mismatch"));
        } catch (ClassNotFoundException missingCore) {
            return false;
        } catch (Throwable error) {
            Throwable cause = error instanceof InvocationTargetException
                && ((InvocationTargetException) error).getCause() != null
                ? ((InvocationTargetException) error).getCause() : error;
            OptimizerBridge.failure(MODULE, cause);
        }
        return false;
    }

    /** Called only after OptiFine's original glShaderSourceARB succeeded. */
    public static void capture(int shader, CharSequence source,
                               Object program, String path, int stage) {
        try {
            ModernRendererRuntime runtime =
                ClientOptimizerRuntime.INSTANCE.modernRenderer();
            if (runtime == null) return;
            long resources = runtime.resourceGeneration();
            long shaders = runtime.shaderPackGeneration();
            if (resources <= 0L || shaders <= 0L) return;
            Completed entry = retain(shader, source, program, path, stage,
                resources, shaders);
            if (entry == null) return;
            ProgramAccess access = ACCESS.get(program.getClass());
            String programName = access.name(program);
            String packId = access.packId();
            String permutation = permutation(entry);
            boolean queued = runtime.queueOptifineShaderSources(program,
                packId, programName, permutation, resources, shaders,
                entry.vertexPath, entry.vertexSource,
                entry.geometryPath, entry.geometrySource,
                entry.fragmentPath, entry.fragmentSource, "");
            if (!queued) {
                try {
                    OptimizerRegistry.breaker(
                        OptimizationModule.OPTIFINE_SHADER_BRIDGE)
                        .recordRejected(
                            "resolved Shader candidate queue full or stale");
                } catch (Throwable ignored) {
                    FatalErrors.rethrowIfFatal(ignored);
                }
            }
        } catch (Throwable error) {
            try { OptimizerBridge.failure(MODULE, error); }
            catch (Throwable ignored) {
                FatalErrors.rethrowIfFatal(ignored);
            }
        }
    }

    /**
     * Retains one stage under the identity/generation key and releases all
     * capture accounting before returning a complete immutable candidate.
     * The runtime queue call deliberately happens outside this monitor to
     * avoid a bridge/runtime lock inversion during resource reload.
     */
    private static synchronized Completed retain(int shader,
                                                  CharSequence source,
                                                  Object program, String path,
                                                  int stage, long resources,
                                                  long shaders) {
        if (shader <= 0 || source == null || program == null || path == null
            || path.isEmpty() || path.length() > 1024 || path.indexOf('\0') >= 0
            || stage < VERTEX || stage > FRAGMENT || resources <= 0L
            || shaders <= 0L || source.length() > MAX_STAGE_BYTES) return null;
        if (resources != resourceGeneration || shaders != shaderGeneration) {
            clear(resources, shaders);
        }
        String text = source.toString();
        int bytes = boundedUtf8Bytes(text);
        if (bytes <= 0 || bytes > MAX_STAGE_BYTES || text.indexOf('\0') >= 0) {
            Entry rejected = PROGRAMS.get(program);
            if (rejected != null) remove(program, rejected);
            return null;
        }
        Entry entry = PROGRAMS.get(program);
        if (entry == null) {
            if (PROGRAMS.size() >= MAX_PROGRAMS) return null;
            entry = new Entry();
            PROGRAMS.put(program, entry);
        }
        long oldBytes = entry.bytes(stage);
        long retained = Math.max(0L, captureBytes - oldBytes);
        if (bytes > MAX_CAPTURE_BYTES - retained) {
            remove(program, entry);
            return null;
        }
        captureBytes = retained + bytes;
        entry.set(stage, path, text, bytes);
        if (entry.vertexSource == null || entry.fragmentSource == null) return null;
        Completed completed = new Completed(entry);
        remove(program, entry);
        return completed;
    }

    /** Counts UTF-8 bytes without allocating a second attacker-sized array. */
    private static int boundedUtf8Bytes(String value) {
        if (value == null || value.length() > MAX_STAGE_BYTES) return -1;
        long bytes = 0L;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current <= 0x7f) bytes++;
            else if (current <= 0x7ff) bytes += 2L;
            else if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                    || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return -1;
                }
                index++;
                bytes += 4L;
            } else if (Character.isLowSurrogate(current)) {
                return -1;
            } else bytes += 3L;
            if (bytes > MAX_STAGE_BYTES) return MAX_STAGE_BYTES + 1;
        }
        return (int) bytes;
    }

    static boolean captureForTest(int shader, CharSequence source,
                                  Object program, String path, int stage,
                                  long resources, long shaders) {
        return retain(shader, source, program, path, stage, resources,
            shaders) != null;
    }

    private static String permutation(Completed entry) {
        String value = "resolved:" + entry.vertexPath + '|'
            + (entry.geometryPath == null ? "-" : entry.geometryPath) + '|'
            + entry.fragmentPath;
        if (value.length() <= 1024) return value;
        return "resolved:" + Integer.toHexString(value.hashCode());
    }

    private static void remove(Object program, Entry entry) {
        if (PROGRAMS.remove(program) != null) {
            captureBytes = Math.max(0L, captureBytes - entry.totalBytes());
        }
    }

    private static void clear(long resources, long shaders) {
        PROGRAMS.clear();
        captureBytes = 0L;
        resourceGeneration = resources;
        shaderGeneration = shaders;
    }

    static synchronized int programCountForTest() { return PROGRAMS.size(); }
    static synchronized long capturedBytesForTest() { return captureBytes; }

    /** Releases incomplete source tuples at resource/Shader lifecycle edges. */
    public static synchronized void reset() {
        clear(0L, 0L);
    }

    static synchronized void resetForTest() {
        reset();
        coreBridgeInstalled = false;
    }

    private static final class Entry {
        private String vertexPath;
        private String vertexSource;
        private String geometryPath;
        private String geometrySource;
        private String fragmentPath;
        private String fragmentSource;
        private int vertexBytes;
        private int geometryBytes;
        private int fragmentBytes;

        private long bytes(int stage) {
            return stage == VERTEX ? vertexBytes
                : stage == GEOMETRY ? geometryBytes : fragmentBytes;
        }

        private void set(int stage, String path, String source, int bytes) {
            if (stage == VERTEX) {
                vertexPath = path;
                vertexSource = source;
                vertexBytes = bytes;
            } else if (stage == GEOMETRY) {
                geometryPath = path;
                geometrySource = source;
                geometryBytes = bytes;
            } else {
                fragmentPath = path;
                fragmentSource = source;
                fragmentBytes = bytes;
            }
        }

        private long totalBytes() {
            return (long) vertexBytes + geometryBytes + fragmentBytes;
        }
    }

    private static final class Completed {
        private final String vertexPath;
        private final String vertexSource;
        private final String geometryPath;
        private final String geometrySource;
        private final String fragmentPath;
        private final String fragmentSource;

        private Completed(Entry entry) {
            vertexPath = entry.vertexPath;
            vertexSource = entry.vertexSource;
            geometryPath = entry.geometryPath;
            geometrySource = entry.geometrySource;
            fragmentPath = entry.fragmentPath;
            fragmentSource = entry.fragmentSource;
        }
    }

    private static final class ProgramAccess {
        private final Method name;
        private final Field currentPack;

        private ProgramAccess(Class<?> programType) {
            try {
                name = programType.getMethod("getName");
                Class<?> shaders = Class.forName("net.optifine.shaders.Shaders",
                    false, programType.getClassLoader());
                currentPack = shaders.getField("currentShaderName");
            } catch (ReflectiveOperationException incompatible) {
                throw new IllegalStateException("OptiFine Program source ABI", incompatible);
            }
        }

        private String name(Object program) throws ReflectiveOperationException {
            Object value = name.invoke(program);
            return bounded(value, "program");
        }

        private String packId() throws ReflectiveOperationException {
            Object value = currentPack.get(null);
            return bounded(value, "pack");
        }

        private static String bounded(Object value, String fallback) {
            String text = value instanceof String ? (String) value : fallback;
            if (text.isEmpty()) text = fallback;
            if (text.length() > 256) text = text.substring(0, 256);
            return text.indexOf('\0') >= 0 ? fallback : text;
        }
    }
}
