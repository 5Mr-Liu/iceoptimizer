package dev.rlcraft.ice.optimizer.render.optifine;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import org.lwjgl.opengl.ARBGeometryShader4;
import org.lwjgl.opengl.GL20;

/** Render-thread-only bounded GLSL compile/link gate with no retained GL names. */
public final class LwjglShaderCompilationDriver implements ShaderCompilationDriver {
    private static final int MAX_LOG_CHARS = 16384;
    private static final int MAX_SOURCE_BYTES = 8 * 1024 * 1024;
    private static final long MIN_NATIVE_CHARGE = 64L * 1024L;
    private static final long SHADER_OBJECT_CHARGE = 4L * 1024L;
    private final RenderThreadGuard threadGuard;
    private final CacheBudget budget;

    public LwjglShaderCompilationDriver(RenderThreadGuard threadGuard) {
        this(threadGuard, null);
    }

    public LwjglShaderCompilationDriver(RenderThreadGuard threadGuard,
                                        CacheBudget budget) {
        if (threadGuard == null) throw new IllegalArgumentException("shader thread guard");
        this.threadGuard = threadGuard;
        this.budget = budget;
    }

    @Override
    public ShaderCompilationResult compile(String vertexSource,
                                           String fragmentSource) {
        threadGuard.check();
        return compileInternal(vertexSource, null, fragmentSource, 0, budget);
    }

    @Override
    public ShaderCompilationResult compile(String vertexSource,
                                           String geometrySource,
                                           String fragmentSource) {
        threadGuard.check();
        return compileInternal(vertexSource, geometrySource, fragmentSource, 0,
            budget);
    }

    @Override
    public ShaderCompilationResult compile(String vertexSource,
                                           String geometrySource,
                                           String fragmentSource,
                                           int legacyProgramId) {
        threadGuard.check();
        return compileInternal(vertexSource, geometrySource, fragmentSource,
            legacyProgramId, budget);
    }

    /** Executable capability probe; all temporary objects are deleted. */
    public static boolean selfTest() {
        return selfTest(null);
    }

    public static boolean selfTest(CacheBudget budget) {
        return selfTestResult(budget).isPassed();
    }

    public static SelfTestResult selfTestResult(CacheBudget budget) {
        try {
            ShaderCompilationResult result = compileInternal(
                "#version 120\nvoid main(){gl_Position=gl_Vertex;}\n",
                null,
                "#version 120\nvoid main(){gl_FragColor=vec4(0.25,0.5,0.75,1.0);}\n",
                0, budget);
            return result.isLinked() ? SelfTestResult.success()
                : SelfTestResult.failure(result.getLog().isEmpty()
                    ? "bounded GLSL compile/link self-test failed"
                    : result.getLog(), "");
        } catch (Throwable unavailable) {
            FatalErrors.rethrowIfFatal(unavailable);
            String message = unavailable.getMessage();
            return SelfTestResult.failure(message == null || message.isEmpty()
                ? unavailable.getClass().getSimpleName() : message,
                unavailable.getClass().getName());
        }
    }

    public static final class SelfTestResult {
        private final boolean passed;
        private final String detail;
        private final String exceptionType;

        private SelfTestResult(boolean passed, String detail,
                               String exceptionType) {
            this.passed = passed;
            this.detail = detail == null ? "" : detail;
            this.exceptionType = exceptionType == null ? "" : exceptionType;
        }

        private static SelfTestResult success() {
            return new SelfTestResult(true, "", "");
        }

        private static SelfTestResult failure(String detail,
                                              String exceptionType) {
            return new SelfTestResult(false, detail, exceptionType);
        }

        public boolean isPassed() { return passed; }
        public String getDetail() { return detail; }
        public String getExceptionType() { return exceptionType; }
    }

    private static ShaderCompilationResult compileInternal(String vertexSource,
                                                            String geometrySource,
                                                            String fragmentSource,
                                                            int legacyProgramId,
                                                            CacheBudget budget) {
        if (vertexSource == null || fragmentSource == null
            || vertexSource.indexOf('\0') >= 0 || fragmentSource.indexOf('\0') >= 0
            || geometrySource != null && geometrySource.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("shader source");
        }
        long sourceBytes = utf8Bytes(vertexSource, 0L);
        sourceBytes = utf8Bytes(fragmentSource, sourceBytes);
        if (geometrySource != null) {
            sourceBytes = utf8Bytes(geometrySource, sourceBytes);
        }
        if (sourceBytes <= 0L || sourceBytes > MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("shader source byte limit exceeded");
        }
        int shaderCount = geometrySource == null ? 2 : 3;
        long nativeCharge = Math.addExact(Math.max(MIN_NATIVE_CHARGE,
            sourceBytes), Math.multiplyExact((long) shaderCount,
                SHADER_OBJECT_CHARGE));
        if (budget == null) {
            return new ShaderCompilationResult(false,
                "temporary shader GPU budget unavailable");
        }
        CacheBudget.Reservation reservation = budget.tryReserve(
            BudgetKind.GPU, nativeCharge);
        if (reservation == null) {
            return new ShaderCompilationResult(false,
                "temporary shader GPU budget exhausted");
        }
        int vertex = 0;
        int geometry = 0;
        int fragment = 0;
        int program = 0;
        boolean vertexAttempted = false;
        boolean vertexReturned = false;
        boolean geometryAttempted = false;
        boolean geometryReturned = false;
        boolean fragmentAttempted = false;
        boolean fragmentReturned = false;
        boolean programAttempted = false;
        boolean programReturned = false;
        Throwable primaryFailure = null;
        try {
            vertexAttempted = true;
            vertex = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
            vertexReturned = true;
            if (vertex <= 0) throw new IllegalStateException(
                "glCreateShader failed");
            GL20.glShaderSource(vertex, vertexSource);
            GL20.glCompileShader(vertex);
            if (GL20.glGetShaderi(vertex, GL20.GL_COMPILE_STATUS) == 0) {
                return new ShaderCompilationResult(false,
                    "vertex: " + GL20.glGetShaderInfoLog(vertex, MAX_LOG_CHARS));
            }
            if (geometrySource != null) {
                geometryAttempted = true;
                geometry = GL20.glCreateShader(
                    ARBGeometryShader4.GL_GEOMETRY_SHADER_ARB);
                geometryReturned = true;
                if (geometry <= 0) throw new IllegalStateException(
                    "glCreateShader failed");
                GL20.glShaderSource(geometry, geometrySource);
                GL20.glCompileShader(geometry);
                if (GL20.glGetShaderi(geometry, GL20.GL_COMPILE_STATUS) == 0) {
                    return new ShaderCompilationResult(false,
                        "geometry: " + GL20.glGetShaderInfoLog(geometry,
                            MAX_LOG_CHARS));
                }
            }
            fragmentAttempted = true;
            fragment = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
            fragmentReturned = true;
            if (fragment <= 0) throw new IllegalStateException(
                "glCreateShader failed");
            GL20.glShaderSource(fragment, fragmentSource);
            GL20.glCompileShader(fragment);
            if (GL20.glGetShaderi(fragment, GL20.GL_COMPILE_STATUS) == 0) {
                return new ShaderCompilationResult(false,
                    "fragment: " + GL20.glGetShaderInfoLog(fragment, MAX_LOG_CHARS));
            }
            programAttempted = true;
            program = GL20.glCreateProgram();
            programReturned = true;
            if (program == 0) throw new IllegalStateException("glCreateProgram failed");
            GL20.glAttachShader(program, vertex);
            if (geometry != 0) GL20.glAttachShader(program, geometry);
            GL20.glAttachShader(program, fragment);
            LwjglShaderLinkInterface.mirror(program, legacyProgramId,
                geometry != 0);
            GL20.glLinkProgram(program);
            boolean linked = GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) != 0;
            if (linked && legacyProgramId > 0) {
                LwjglShaderLinkInterface.verify(program, legacyProgramId,
                    geometry != 0);
            }
            return new ShaderCompilationResult(linked,
                GL20.glGetProgramInfoLog(program, MAX_LOG_CHARS));
        } catch (Throwable error) {
            primaryFailure = error;
            throw error;
        } finally {
            Throwable cleanupFailure = null;
            boolean programSafe = !programAttempted
                || programReturned && program <= 0;
            if (program != 0) {
                if (vertex != 0) try { GL20.glDetachShader(program, vertex); }
                    catch (Throwable error) {
                        cleanupFailure = append(cleanupFailure, error);
                    }
                if (fragment != 0) try { GL20.glDetachShader(program, fragment); }
                    catch (Throwable error) {
                        cleanupFailure = append(cleanupFailure, error);
                    }
                if (geometry != 0) try { GL20.glDetachShader(program, geometry); }
                    catch (Throwable error) {
                        cleanupFailure = append(cleanupFailure, error);
                    }
                try {
                    GL20.glDeleteProgram(program);
                    programSafe = true;
                }
                catch (Throwable error) {
                    cleanupFailure = append(cleanupFailure, error);
                }
            }
            boolean vertexSafe = !vertexAttempted
                || vertexReturned && vertex <= 0;
            if (vertex != 0) try {
                GL20.glDeleteShader(vertex);
                vertexSafe = true;
            } catch (Throwable error) {
                cleanupFailure = append(cleanupFailure, error);
            }
            boolean geometrySafe = !geometryAttempted
                || geometryReturned && geometry <= 0;
            if (geometry != 0) try {
                GL20.glDeleteShader(geometry);
                geometrySafe = true;
            } catch (Throwable error) {
                cleanupFailure = append(cleanupFailure, error);
            }
            boolean fragmentSafe = !fragmentAttempted
                || fragmentReturned && fragment <= 0;
            if (fragment != 0) try {
                GL20.glDeleteShader(fragment);
                fragmentSafe = true;
            } catch (Throwable error) {
                cleanupFailure = append(cleanupFailure, error);
            }
            if (reservation != null && programSafe && vertexSafe
                && geometrySafe && fragmentSafe) try {
                reservation.close();
                reservation = null;
            } catch (Throwable error) {
                cleanupFailure = append(cleanupFailure, error);
            }
            if (cleanupFailure != null) {
                if (primaryFailure != null) {
                    Throwable combined = append(primaryFailure, cleanupFailure);
                    // A non-fatal primary is already escaping from the catch.
                    // Re-throw only when cleanup uncovered a fatal signal that
                    // must not be hidden by Java's pending exception.
                    FatalErrors.rethrowIfFatal(combined);
                } else {
                    rethrow(cleanupFailure);
                }
            }
        }
    }

    private static long utf8Bytes(String source, long initial) {
        long bytes = initial;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current <= 0x7f) bytes++;
            else if (current <= 0x7ff) bytes += 2L;
            else if (Character.isHighSurrogate(current)) {
                if (index + 1 >= source.length()
                    || !Character.isLowSurrogate(source.charAt(index + 1))) {
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

    private static Throwable append(Throwable first, Throwable next) {
        if (first == null) return next;
        Throwable nextFatal = FatalErrors.findFatal(next);
        if (nextFatal != null && FatalErrors.findFatal(first) == null) {
            if (nextFatal != first) nextFatal.addSuppressed(first);
            return nextFatal;
        }
        if (first != next) first.addSuppressed(next);
        return first;
    }

    private static void rethrow(Throwable failure) {
        FatalErrors.rethrowIfFatal(failure);
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
        throw new IllegalStateException("temporary shader cleanup failed", failure);
    }
}
