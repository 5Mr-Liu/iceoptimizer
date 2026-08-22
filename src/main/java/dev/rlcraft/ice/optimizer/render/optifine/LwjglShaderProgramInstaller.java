package dev.rlcraft.ice.optimizer.render.optifine;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.resource.LwjglRetirementFence;
import dev.rlcraft.ice.optimizer.render.resource.RenderHandle;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import java.util.LinkedHashMap;
import java.util.Map;
import org.lwjgl.opengl.GL20;

/**
 * Retains linked native ShaderPack programs under generation-qualified ledger
 * ownership. Installation never changes the current GL program; activation is
 * a separate, certified safe-boundary decision.
 */
public final class LwjglShaderProgramInstaller {
    private static final int MAX_SOURCE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_LOG_CHARS = 16384;
    private static final long MIN_ACCOUNTED_PROGRAM_BYTES = 64L * 1024L;
    private static final long MIN_ACCOUNTED_SHADER_BYTES = 4L * 1024L;

    private final RenderThreadGuard threadGuard;
    private final ResourceLedger ledger;
    private final ProgramDriver driver;
    private final FenceFactory fenceFactory;
    private final PublicationHook publicationHook;
    private final int maximumPrograms;
    private final LinkedHashMap<ShaderPermutationKey, Entry> programs =
        new LinkedHashMap<ShaderPermutationKey, Entry>(16, 0.75F, true);
    private boolean saturated;
    private boolean closed;

    public LwjglShaderProgramInstaller(RenderThreadGuard threadGuard,
                                       ResourceLedger ledger,
                                       int maximumPrograms) {
        this(threadGuard, ledger, maximumPrograms, new LwjglProgramDriver(),
            new FenceFactory() {
                @Override public ResourceLedger.RetirementFence create() {
                    return LwjglRetirementFence.afterCurrentCommands(ledger);
                }
            }, PublicationHook.NONE);
    }

    LwjglShaderProgramInstaller(RenderThreadGuard threadGuard,
                                ResourceLedger ledger,
                                int maximumPrograms,
                                ProgramDriver driver,
                                FenceFactory fenceFactory) {
        this(threadGuard, ledger, maximumPrograms, driver, fenceFactory,
            PublicationHook.NONE);
    }

    LwjglShaderProgramInstaller(RenderThreadGuard threadGuard,
                                ResourceLedger ledger,
                                int maximumPrograms,
                                ProgramDriver driver,
                                FenceFactory fenceFactory,
                                PublicationHook publicationHook) {
        if (threadGuard == null || ledger == null) {
            throw new IllegalArgumentException("shader installer dependencies");
        }
        if (driver == null || fenceFactory == null || publicationHook == null) {
            throw new IllegalArgumentException("shader installer driver");
        }
        this.threadGuard = threadGuard;
        this.ledger = ledger;
        this.driver = driver;
        this.fenceFactory = fenceFactory;
        this.publicationHook = publicationHook;
        this.maximumPrograms = Math.max(1, Math.min(2048, maximumPrograms));
    }

    public InstallResult install(PreparedShaderPermutation prepared,
                                 long resourceGeneration,
                                 long contextGeneration,
                                 long shaderGeneration) {
        return install(prepared, 0, resourceGeneration, contextGeneration,
            shaderGeneration);
    }

    /**
     * Links a retained clone while mirroring the reviewed legacy program's
     * generic attribute locations and geometry link parameters.  A zero
     * legacy name is accepted only for isolated tests and non-OptiFine users.
     */
    public InstallResult install(PreparedShaderPermutation prepared,
                                 int legacyProgramId,
                                 long resourceGeneration,
                                 long contextGeneration,
                                 long shaderGeneration) {
        threadGuard.check();
        if (closed || prepared == null || legacyProgramId < 0
            || resourceGeneration <= 0L
            || contextGeneration <= 0L || shaderGeneration <= 0L) {
            return InstallResult.failure("invalid shader installation state");
        }
        ShaderPermutationKey key = prepared.getKey();
        if (key.getResourceGeneration() != resourceGeneration
            || key.getShaderGeneration() != shaderGeneration) {
            return InstallResult.failure("stale shader permutation generation");
        }
        Entry existing = programs.get(key);
        if (existing != null && existing.handle.belongsTo(resourceGeneration,
            contextGeneration) && ledger.isLive(existing.handle)) {
            if (existing.legacyProgramId != legacyProgramId) {
                return InstallResult.failure(
                    "legacy shader program identity changed within a generation");
            }
            return InstallResult.success(existing.handle.getNativeId(),
                "already installed");
        }
        if (existing != null) {
            if (existing.handle.getContextGeneration() != contextGeneration) {
                return InstallResult.failure(
                    "stale native shader context requires graph reset");
            }
            programs.remove(key);
            retire(existing);
        }
        if (programs.size() >= maximumPrograms) {
            saturated = true;
            return InstallResult.failure("native shader program limit exceeded");
        }

        String vertexSource = prepared.getVertex().getSource();
        String geometrySource = prepared.getGeometry() == null ? null
            : prepared.getGeometry().getSource();
        String fragmentSource = prepared.getFragment().getSource();
        int sourceBytes;
        try {
            sourceBytes = checkedSourceBytes(vertexSource, geometrySource,
                fragmentSource);
        }
        catch (RuntimeException invalid) {
            FatalErrors.rethrowIfFatal(invalid);
            return InstallResult.failure(invalid.getMessage());
        }
        long accounted = Math.max(MIN_ACCOUNTED_PROGRAM_BYTES,
            (long) sourceBytes);
        CacheBudget.Reservation gpuReservation = ledger.reserveGpu(accounted);
        if (gpuReservation == null) {
            return InstallResult.failure("native shader GPU budget exhausted");
        }
        int shaderStages = geometrySource == null ? 2 : 3;
        long temporaryShaderBytes = Math.multiplyExact(
            MIN_ACCOUNTED_SHADER_BYTES, (long) shaderStages);
        CacheBudget.Reservation shaderReservation = ledger.reserveGpu(
            temporaryShaderBytes);
        if (shaderReservation == null) {
            gpuReservation.close();
            return InstallResult.failure(
                "temporary native shader GPU budget exhausted");
        }

        int vertex = 0;
        int geometry = 0;
        int fragment = 0;
        ShaderAllocation vertexAllocation = new ShaderAllocation();
        ShaderAllocation geometryAllocation = new ShaderAllocation();
        ShaderAllocation fragmentAllocation = new ShaderAllocation();
        int program = 0;
        boolean programCreationAttempted = false;
        boolean programCreationReturned = false;
        RenderHandle handle = null;
        Entry published = null;
        boolean publicationComplete = false;
        Throwable primaryFailure = null;
        try {
            vertex = compile(vertexAllocation, GL20.GL_VERTEX_SHADER,
                vertexSource);
            if (driver.shaderStatus(vertex) == 0) {
                return InstallResult.failure("vertex: "
                    + log(driver.shaderLog(vertex, MAX_LOG_CHARS)));
            }
            if (geometrySource != null) {
                geometry = compile(geometryAllocation, 0x8DD9,
                    geometrySource); // GL_GEOMETRY_SHADER
                if (driver.shaderStatus(geometry) == 0) {
                    return InstallResult.failure("geometry: "
                        + log(driver.shaderLog(geometry, MAX_LOG_CHARS)));
                }
            }
            fragment = compile(fragmentAllocation, GL20.GL_FRAGMENT_SHADER,
                fragmentSource);
            if (driver.shaderStatus(fragment) == 0) {
                return InstallResult.failure("fragment: "
                    + log(driver.shaderLog(fragment, MAX_LOG_CHARS)));
            }
            programCreationAttempted = true;
            program = driver.createProgram();
            programCreationReturned = true;
            if (program <= 0) {
                return InstallResult.failure("glCreateProgram failed");
            }
            driver.attachShader(program, vertex);
            if (geometry != 0) driver.attachShader(program, geometry);
            driver.attachShader(program, fragment);
            driver.mirrorLinkInterface(program, legacyProgramId,
                geometry != 0);
            driver.linkProgram(program);
            String linkerLog = log(driver.programLog(program, MAX_LOG_CHARS));
            if (driver.programStatus(program) == 0) {
                return InstallResult.failure("link: " + linkerLog);
            }
            driver.verifyLinkInterface(program, legacyProgramId,
                geometry != 0);
            handle = ledger.registerReserved(RenderResourceKind.PROGRAM,
                program, accounted, resourceGeneration, contextGeneration,
                gpuReservation);
            if (handle == null) {
                return InstallResult.failure("native shader GPU budget exhausted");
            }
            gpuReservation = null;
            published = new Entry(key, handle, shaderGeneration,
                legacyProgramId);
            InstallResult installed = InstallResult.success(program, linkerLog);
            programs.put(key, published);
            publicationHook.afterPut();
            publicationComplete = true;
            program = 0;
            handle = null;
            return installed;
        } catch (Throwable error) {
            primaryFailure = error;
            FatalErrors.rethrowIfFatal(error);
            return InstallResult.failure(error.getClass().getSimpleName() + ": "
                + log(error.getMessage()));
        } finally {
            Throwable cleanupFailure = null;
            boolean mapMayOwnHandle = false;
            if (!publicationComplete && published != null && handle != null) {
                try {
                    Entry mapped = programs.get(key);
                    if (mapped == published) {
                        Entry removed = programs.remove(key);
                        if (removed != published) {
                            throw new IllegalStateException(
                                "shader publication identity rollback failed");
                        }
                    }
                } catch (Throwable error) {
                    // The Map mutation may already have completed.  Retaining
                    // ledger ownership is safer than retiring a program that
                    // a surviving entry could still publish.
                    mapMayOwnHandle = true;
                    cleanupFailure = append(cleanupFailure, error);
                }
            }
            if (mapMayOwnHandle) {
                program = 0;
                handle = null;
            }
            if (handle != null) {
                try { ledger.retire(handle, null); }
                catch (Throwable error) {
                    cleanupFailure = append(cleanupFailure, error);
                }
                // The ledger still owns a handle when retirement publication
                // fails, so raw deletion would be a double-delete/UAF risk.
                program = 0;
            }
            cleanupFailure = deleteShader(vertexAllocation, cleanupFailure);
            cleanupFailure = deleteShader(geometryAllocation, cleanupFailure);
            cleanupFailure = deleteShader(fragmentAllocation, cleanupFailure);
            if (shaderReservation != null
                && safelyReleased(vertexAllocation)
                && safelyReleased(geometryAllocation)
                && safelyReleased(fragmentAllocation)) try {
                shaderReservation.close();
                shaderReservation = null;
            } catch (Throwable error) {
                cleanupFailure = append(cleanupFailure, error);
            }
            boolean programDeleted = false;
            if (program != 0) try {
                driver.deleteProgram(program);
                programDeleted = true;
            } catch (Throwable error) {
                cleanupFailure = append(cleanupFailure, error);
            }
            boolean noProgramCreated = !programCreationAttempted
                || programCreationReturned && program <= 0;
            if (gpuReservation != null
                && (programDeleted || noProgramCreated)) try {
                gpuReservation.close();
                gpuReservation = null;
            } catch (Throwable error) {
                cleanupFailure = append(cleanupFailure, error);
            }
            if (cleanupFailure != null) {
                rethrow(append(primaryFailure, cleanupFailure));
            }
        }
    }

    public int certifiedProgram(ShaderPermutationKey key,
                                ShaderCertificationRegistry certifications,
                                long resourceGeneration,
                                long contextGeneration,
                                long shaderGeneration) {
        threadGuard.check();
        if (closed || key == null || certifications == null
            || !certifications.isCertified(key)) return 0;
        Entry entry = programs.get(key);
        return entry != null && entry.shaderGeneration == shaderGeneration
            && entry.handle.belongsTo(resourceGeneration, contextGeneration)
            && ledger.isLive(entry.handle) ? entry.handle.getNativeId() : 0;
    }

    /** Returns a retained but not necessarily certified program for A/B only. */
    public int installedProgram(ShaderPermutationKey key,
                                long resourceGeneration,
                                long contextGeneration,
                                long shaderGeneration) {
        threadGuard.check();
        if (closed || key == null) return 0;
        Entry entry = programs.get(key);
        return entry != null && entry.shaderGeneration == shaderGeneration
            && entry.handle.belongsTo(resourceGeneration, contextGeneration)
            && ledger.isLive(entry.handle) ? entry.handle.getNativeId() : 0;
    }

    public boolean isInstalled(ShaderPermutationKey key,
                               long resourceGeneration,
                               long contextGeneration,
                               long shaderGeneration) {
        threadGuard.check();
        if (closed || key == null) return false;
        Entry entry = programs.get(key);
        return entry != null && entry.shaderGeneration == shaderGeneration
            && entry.handle.belongsTo(resourceGeneration, contextGeneration)
            && ledger.isLive(entry.handle);
    }

    public void reset(boolean validContext) {
        threadGuard.check();
        Throwable failure = null;
        if (validContext) {
            for (Entry entry : programs.values()) {
                try { retire(entry); }
                catch (Throwable error) { failure = append(failure, error); }
            }
        }
        programs.clear();
        saturated = false;
        if (failure != null) rethrow(failure);
    }

    public void close(boolean validContext) {
        threadGuard.check();
        if (closed) return;
        try { reset(validContext); }
        finally { closed = true; }
    }

    public int size() { threadGuard.check(); return programs.size(); }
    public boolean isSaturated() { threadGuard.check(); return saturated; }

    private void retire(Entry entry) {
        ResourceLedger.RetirementFence fence;
        Throwable fenceFailure = null;
        try { fence = fenceFactory.create(); }
        catch (Throwable failure) {
            // A missing Fence must never turn into an immediate program delete:
            // the driver may still be consuming it.  A permanently-busy Fence
            // keeps the entry bounded by the ledger until graph destruction.
            fence = NeverReadyFence.INSTANCE;
            fenceFailure = failure;
        }
        if (fence == null) {
            fence = NeverReadyFence.INSTANCE;
            fenceFailure = new IllegalStateException(
                "shader retirement Fence creation failed");
        }
        try { ledger.retire(entry.handle, fence); }
        catch (Throwable retirementFailure) {
            fenceFailure = append(fenceFailure, retirementFailure);
        }
        if (fenceFailure != null) rethrow(fenceFailure);
    }

    private int compile(ShaderAllocation allocation, int type, String source) {
        allocation.creationAttempted = true;
        int shader = driver.createShader(type);
        allocation.creationReturned = true;
        allocation.nativeId = shader;
        if (shader <= 0) throw new IllegalStateException("glCreateShader failed");
        driver.shaderSource(shader, source);
        driver.compileShader(shader);
        return shader;
    }

    private Throwable deleteShader(ShaderAllocation allocation,
                                   Throwable failure) {
        if (allocation.nativeId <= 0 || allocation.deletionCompleted) {
            return failure;
        }
        try {
            driver.deleteShader(allocation.nativeId);
            allocation.deletionCompleted = true;
        } catch (Throwable cleanup) {
            failure = append(failure, cleanup);
        }
        return failure;
    }

    private static boolean safelyReleased(ShaderAllocation allocation) {
        return !allocation.creationAttempted
            || allocation.creationReturned && allocation.nativeId <= 0
            || allocation.deletionCompleted;
    }

    private static int checkedSourceBytes(String vertex, String geometry,
                                          String fragment) {
        if (vertex == null || fragment == null || vertex.indexOf('\0') >= 0
            || fragment.indexOf('\0') >= 0
            || geometry != null && geometry.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid shader source");
        }
        long bytes = utf8Bytes(vertex, 0L);
        bytes = utf8Bytes(fragment, bytes);
        if (geometry != null) bytes = utf8Bytes(geometry, bytes);
        if (bytes <= 0L || bytes > MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("shader source byte limit exceeded");
        }
        return (int) bytes;
    }

    private static long utf8Bytes(String value, long initial) {
        long bytes = initial;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current <= 0x7f) bytes++;
            else if (current <= 0x7ff) bytes += 2L;
            else if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                    || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException("invalid shader Unicode");
                }
                index++;
                bytes += 4L;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException("invalid shader Unicode");
            } else bytes += 3L;
            if (bytes > MAX_SOURCE_BYTES) return bytes;
        }
        return bytes;
    }

    private static String log(String value) {
        if (value == null) return "";
        return value.length() <= MAX_LOG_CHARS ? value
            : value.substring(0, MAX_LOG_CHARS);
    }

    private static Throwable append(Throwable first, Throwable next) {
        if (first == null) return next;
        Throwable nextFatal = FatalErrors.findFatal(next);
        if (nextFatal != null && FatalErrors.findFatal(first) == null) {
            if (nextFatal != first) nextFatal.addSuppressed(first);
            return nextFatal;
        }
        if (next != null && first != next) first.addSuppressed(next);
        return first;
    }

    private static void rethrow(Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        if (error instanceof RuntimeException) throw (RuntimeException) error;
        if (error instanceof Error) throw (Error) error;
        throw new IllegalStateException("shader program cleanup failed", error);
    }

    private static final class Entry {
        @SuppressWarnings("unused") private final ShaderPermutationKey key;
        private final RenderHandle handle;
        private final long shaderGeneration;
        private final int legacyProgramId;
        private Entry(ShaderPermutationKey key, RenderHandle handle,
                      long shaderGeneration, int legacyProgramId) {
            this.key = key;
            this.handle = handle;
            this.shaderGeneration = shaderGeneration;
            this.legacyProgramId = legacyProgramId;
        }
    }

    private static final class ShaderAllocation {
        private boolean creationAttempted;
        private boolean creationReturned;
        private int nativeId;
        private boolean deletionCompleted;
    }

    interface FenceFactory {
        ResourceLedger.RetirementFence create();
    }

    interface PublicationHook {
        PublicationHook NONE = new PublicationHook() {
            @Override public void afterPut() { }
        };
        void afterPut();
    }

    interface ProgramDriver {
        int createShader(int type);
        void shaderSource(int shader, String source);
        void compileShader(int shader);
        int shaderStatus(int shader);
        String shaderLog(int shader, int maximumChars);
        void deleteShader(int shader);
        int createProgram();
        void attachShader(int program, int shader);
        default void mirrorLinkInterface(int program, int legacyProgram,
                                         boolean geometry) { }
        default void verifyLinkInterface(int program, int legacyProgram,
                                         boolean geometry) { }
        void linkProgram(int program);
        int programStatus(int program);
        String programLog(int program, int maximumChars);
        void deleteProgram(int program);
    }

    private static final class LwjglProgramDriver implements ProgramDriver {
        @Override public int createShader(int type) {
            return GL20.glCreateShader(type);
        }
        @Override public void shaderSource(int shader, String source) {
            GL20.glShaderSource(shader, source);
        }
        @Override public void compileShader(int shader) {
            GL20.glCompileShader(shader);
        }
        @Override public int shaderStatus(int shader) {
            return GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS);
        }
        @Override public String shaderLog(int shader, int maximumChars) {
            return GL20.glGetShaderInfoLog(shader, maximumChars);
        }
        @Override public void deleteShader(int shader) {
            GL20.glDeleteShader(shader);
        }
        @Override public int createProgram() { return GL20.glCreateProgram(); }
        @Override public void attachShader(int program, int shader) {
            GL20.glAttachShader(program, shader);
        }
        @Override public void mirrorLinkInterface(int program,
                                                  int legacyProgram,
                                                  boolean geometry) {
            LwjglShaderLinkInterface.mirror(program, legacyProgram, geometry);
        }
        @Override public void verifyLinkInterface(int program,
                                                  int legacyProgram,
                                                  boolean geometry) {
            if (legacyProgram > 0) {
                LwjglShaderLinkInterface.verify(program, legacyProgram,
                    geometry);
            }
        }
        @Override public void linkProgram(int program) {
            GL20.glLinkProgram(program);
        }
        @Override public int programStatus(int program) {
            return GL20.glGetProgrami(program, GL20.GL_LINK_STATUS);
        }
        @Override public String programLog(int program, int maximumChars) {
            return GL20.glGetProgramInfoLog(program, maximumChars);
        }
        @Override public void deleteProgram(int program) {
            GL20.glDeleteProgram(program);
        }
    }

    private enum NeverReadyFence implements ResourceLedger.RetirementFence {
        INSTANCE;
        @Override public boolean isSignaled() { return false; }
        @Override public void destroy() { }
    }

    public static final class InstallResult {
        private final boolean installed;
        private final int programId;
        private final String detail;
        private InstallResult(boolean installed, int programId, String detail) {
            this.installed = installed;
            this.programId = programId;
            this.detail = log(detail);
        }
        private static InstallResult success(int programId, String detail) {
            return new InstallResult(true, programId, detail);
        }
        private static InstallResult failure(String detail) {
            return new InstallResult(false, 0, detail);
        }
        public boolean isInstalled() { return installed; }
        public int getProgramId() { return programId; }
        public String getDetail() { return detail; }
    }
}
