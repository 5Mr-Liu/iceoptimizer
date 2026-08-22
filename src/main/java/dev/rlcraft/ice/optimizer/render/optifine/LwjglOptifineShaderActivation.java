package dev.rlcraft.ice.optimizer.render.optifine;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/**
 * Atomic, render-thread-only switch between one OptiFine program and its
 * certified retained clone.  The logical Program object remains unchanged;
 * every GL/uniform-manager mutation is rolled back together on failure.
 */
public final class LwjglOptifineShaderActivation {
    static final int UNIFORM_WORKSPACE_BYTES = 80;
    private final RenderThreadGuard threadGuard;
    private final Driver driver;
    private final UniformTransfer uniformTransfer;
    private UniformWorkspace uniformWorkspace;

    public LwjglOptifineShaderActivation(RenderThreadGuard threadGuard) {
        if (threadGuard == null) throw new IllegalArgumentException(
            "shader activation thread guard");
        this.threadGuard = threadGuard;
        this.driver = new ReflectionDriver();
        this.uniformWorkspace = null;
        this.uniformTransfer = new LwjglUniformTransfer(null);
    }

    public LwjglOptifineShaderActivation(RenderThreadGuard threadGuard,
                                         CacheBudget budget) {
        if (threadGuard == null || budget == null) {
            throw new IllegalArgumentException(
                "shader activation dependencies");
        }
        this.threadGuard = threadGuard;
        this.driver = new ReflectionDriver();
        this.uniformWorkspace = UniformWorkspace.tryCreate(budget);
        this.uniformTransfer = new LwjglUniformTransfer(uniformWorkspace);
    }

    LwjglOptifineShaderActivation(RenderThreadGuard threadGuard, Driver driver,
                                  UniformTransfer uniformTransfer) {
        if (threadGuard == null || driver == null || uniformTransfer == null) {
            throw new IllegalArgumentException("shader activation dependencies");
        }
        this.threadGuard = threadGuard;
        this.driver = driver;
        this.uniformTransfer = uniformTransfer;
        this.uniformWorkspace = null;
    }

    /**
     * Verifies the reflection-only OptiFine activation ABI without
     * initializing {@code Shaders} or issuing any OpenGL call.  Kept
     * package-private so the reviewed G5 fixture can exercise the exact
     * production resolver without widening the runtime API.
     */
    static void verifyAbi(Class<?> concreteProgram) {
        if (concreteProgram == null) throw new IllegalArgumentException("program type");
        new Access(concreteProgram);
    }

    public Result switchProgram(Object optifineProgram, int sourceProgram,
                                int targetProgram) {
        threadGuard.check();
        if (optifineProgram == null || sourceProgram <= 0 || targetProgram <= 0) {
            return Result.rejected("invalid shader activation programs");
        }
        if (sourceProgram == targetProgram) {
            return Result.success("shader program already selected");
        }

        try {
            if (!driver.ownsLogicalProgram(optifineProgram)) {
                return Result.rejected("OptiFine activeProgram identity changed");
            }
            if (driver.activeProgramId(optifineProgram) != sourceProgram
                || driver.currentGlProgram() != sourceProgram) {
                return Result.rejected(
                    "OptiFine and GL source program state is not synchronized");
            }
        } catch (Throwable error) {
            return Result.failure("activation ABI check failed: " + compact(error),
                true, true);
        }

        Transfer transfer;
        try {
            transfer = uniformTransfer.snapshot(sourceProgram, targetProgram);
        } catch (Throwable error) {
            return Result.failure("uniform snapshot failed: " + compact(error), true,
                true);
        }
        if (transfer == null || !transfer.isValid()) {
            return Result.rejected(transfer == null
                ? "uniform snapshot returned null" : transfer.getDetail());
        }

        try {
            driver.useProgram(targetProgram);
            transfer.apply();
            driver.setActiveProgramId(optifineProgram, targetProgram);
            driver.setUniformPrograms(optifineProgram, targetProgram);
            if (driver.currentGlProgram() != targetProgram
                || driver.activeProgramId(optifineProgram) != targetProgram) {
                throw new IllegalStateException(
                    "activation postcondition did not publish target program");
            }
            return Result.success("shader program and uniforms switched");
        } catch (Throwable activationFailure) {
            Throwable rollbackFailure = null;
            try { driver.useProgram(sourceProgram); }
            catch (Throwable error) { rollbackFailure = append(rollbackFailure, error); }
            try { driver.setActiveProgramId(optifineProgram, sourceProgram); }
            catch (Throwable error) { rollbackFailure = append(rollbackFailure, error); }
            try { driver.setUniformPrograms(optifineProgram, sourceProgram); }
            catch (Throwable error) { rollbackFailure = append(rollbackFailure, error); }
            try {
                if (driver.currentGlProgram() != sourceProgram
                    || driver.activeProgramId(optifineProgram) != sourceProgram) {
                    rollbackFailure = append(rollbackFailure,
                        new IllegalStateException("shader activation rollback mismatch"));
                }
            } catch (Throwable error) {
                rollbackFailure = append(rollbackFailure, error);
            }
            String detail = "shader activation failed: " + compact(activationFailure);
            if (rollbackFailure != null) {
                detail += "; rollback failed: " + compact(rollbackFailure);
            }
            return Result.failure(detail, true, rollbackFailure == null);
        }
    }

    /**
     * Read-only reconciliation used after OptiFine's preserved useProgram
     * implementation has run. A positive result proves that a previously
     * outcome-uncertain candidate is no longer current and its ownership guard
     * may be released safely.
     */
    public boolean isProgramSynchronized(Object optifineProgram,
                                         int expectedProgram) {
        threadGuard.check();
        if (optifineProgram == null || expectedProgram < 0) return false;
        try {
            return driver.ownsLogicalProgram(optifineProgram)
                && driver.activeProgramId(optifineProgram) == expectedProgram
                && driver.currentGlProgram() == expectedProgram;
        } catch (Throwable unavailable) {
            dev.rlcraft.ice.optimizer.FatalErrors.rethrowIfFatal(unavailable);
            return false;
        }
    }

    public void close() {
        UniformWorkspace owned = uniformWorkspace;
        uniformWorkspace = null;
        if (owned != null) owned.close();
    }

    boolean isWorkspaceAvailableForTest() {
        UniformWorkspace value = uniformWorkspace;
        return value != null && !value.isClosed();
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

    private static String compact(Throwable error) {
        if (error == null) return "unknown";
        dev.rlcraft.ice.optimizer.FatalErrors.rethrowIfFatal(error);
        String message = error.getMessage();
        String value = error.getClass().getSimpleName()
            + (message == null || message.isEmpty() ? "" : ": " + message);
        return value.length() <= 256 ? value : value.substring(0, 256);
    }

    interface Driver {
        int currentGlProgram();
        boolean ownsLogicalProgram(Object program) throws Exception;
        int activeProgramId(Object program) throws Exception;
        void useProgram(int program);
        void setActiveProgramId(Object program, int value) throws Exception;
        void setUniformPrograms(Object program, int value) throws Exception;
    }

    interface UniformTransfer {
        Transfer snapshot(int sourceProgram, int targetProgram);
    }

    interface Transfer {
        boolean isValid();
        String getDetail();
        void apply();
    }

    private static final class LwjglUniformTransfer implements UniformTransfer {
        private final UniformWorkspace workspace;

        private LwjglUniformTransfer(UniformWorkspace workspace) {
            this.workspace = workspace;
        }

        @Override public Transfer snapshot(int sourceProgram, int targetProgram) {
            final UniformWorkspace work = workspace;
            if (work == null || work.isClosed()) {
                return new Transfer() {
                    @Override public boolean isValid() { return false; }
                    @Override public String getDetail() {
                        return "uniform mirror Direct budget unavailable";
                    }
                    @Override public void apply() {
                        throw new IllegalStateException(
                            "uniform mirror workspace unavailable");
                    }
                };
            }
            final LwjglShaderUniformMirror.Snapshot snapshot =
                LwjglShaderUniformMirror.snapshot(sourceProgram, targetProgram,
                    work.floats(), work.integers());
            return new Transfer() {
                @Override public boolean isValid() { return snapshot.isValid(); }
                @Override public String getDetail() { return snapshot.getDetail(); }
                @Override public void apply() {
                    snapshot.apply(work.floats(), work.integers());
                }
            };
        }
    }

    private static final class UniformWorkspace implements AutoCloseable {
        private CacheBudget.Reservation reservation;
        private ByteBuffer storage;
        private FloatBuffer floats;
        private IntBuffer integers;

        private UniformWorkspace(CacheBudget.Reservation reservation) {
            ByteBuffer allocated = BufferUtils.createByteBuffer(
                UNIFORM_WORKSPACE_BYTES).order(ByteOrder.nativeOrder());
            storage = allocated;
            floats = slice(allocated, 0, 64).asFloatBuffer();
            integers = slice(allocated, 64, 16).asIntBuffer();
            this.reservation = reservation;
        }

        private static UniformWorkspace tryCreate(CacheBudget budget) {
            CacheBudget.Reservation reservation = budget.tryReserve(
                BudgetKind.DIRECT, UNIFORM_WORKSPACE_BYTES);
            if (reservation == null) return null;
            try {
                return new UniformWorkspace(reservation);
            } catch (Throwable failure) {
                try { reservation.close(); }
                catch (Throwable cleanup) {
                    failure = append(failure, cleanup);
                }
                FatalErrors.rethrowIfFatal(failure);
                if (failure instanceof RuntimeException) {
                    throw (RuntimeException) failure;
                }
                if (failure instanceof Error) throw (Error) failure;
                throw new IllegalStateException(
                    "uniform mirror workspace allocation failed", failure);
            }
        }

        private FloatBuffer floats() {
            checkOpen();
            floats.clear();
            return floats;
        }

        private IntBuffer integers() {
            checkOpen();
            integers.clear();
            return integers;
        }

        private boolean isClosed() { return storage == null; }

        @Override public void close() {
            CacheBudget.Reservation owned = reservation;
            reservation = null;
            storage = null;
            floats = null;
            integers = null;
            if (owned != null) owned.close();
        }

        private void checkOpen() {
            if (storage == null) throw new IllegalStateException(
                "uniform mirror workspace is closed");
        }

        private static ByteBuffer slice(ByteBuffer source, int offset,
                                        int count) {
            ByteBuffer view = source.duplicate().order(ByteOrder.nativeOrder());
            view.position(offset);
            view.limit(Math.addExact(offset, count));
            return view.slice().order(ByteOrder.nativeOrder());
        }
    }

    private static final class ReflectionDriver implements Driver {
        private static final ClassValue<Access> ACCESS = new ClassValue<Access>() {
            @Override protected Access computeValue(Class<?> type) {
                return new Access(type);
            }
        };

        @Override public int currentGlProgram() {
            return GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        }

        @Override public boolean ownsLogicalProgram(Object program)
            throws Exception {
            return access(program).activeProgram.get(null) == program;
        }

        @Override public int activeProgramId(Object program) throws Exception {
            return access(program).activeProgramId.getInt(null);
        }

        @Override public void useProgram(int program) {
            GL20.glUseProgram(program);
        }

        @Override public void setActiveProgramId(Object program, int value)
            throws Exception {
            access(program).activeProgramId.setInt(null, value);
        }

        @Override public void setUniformPrograms(Object program, int value)
            throws Exception {
            Access access = access(program);
            Object uniforms = access.shaderUniforms.get(null);
            if (uniforms == null) {
                throw new IllegalStateException("OptiFine ShaderUniforms unavailable");
            }
            access.shaderSetProgram.invoke(uniforms, Integer.valueOf(value));
            Object custom = access.customUniforms.get(null);
            if (custom != null) {
                access.customSetProgram.invoke(custom, Integer.valueOf(value));
            }
        }

        private static Access access(Object program) {
            if (program == null) throw new IllegalArgumentException("program");
            return ACCESS.get(program.getClass());
        }
    }

    private static final class Access {
        private final Field activeProgram;
        private final Field activeProgramId;
        private final Field shaderUniforms;
        private final Field customUniforms;
        private final Method shaderSetProgram;
        private final Method customSetProgram;

        private Access(Class<?> concreteProgram) {
            try {
                Class<?> program = programBaseType(concreteProgram);
                Method getId = program.getMethod("getId");
                if (getId.getReturnType() != Integer.TYPE
                    || Modifier.isStatic(getId.getModifiers())
                    || getId.getParameterTypes().length != 0) {
                    throw new IllegalStateException("OptiFine Program.getId changed");
                }
                ClassLoader loader = program.getClassLoader();
                Class<?> shaders = Class.forName("net.optifine.shaders.Shaders",
                    false, loader);
                activeProgram = staticField(shaders, "activeProgram", program);
                activeProgramId = staticField(shaders, "activeProgramID",
                    Integer.TYPE);
                shaderUniforms = staticField(shaders, "shaderUniforms", null);
                customUniforms = staticField(shaders, "customUniforms", null);
                shaderSetProgram = setProgram(shaderUniforms.getType());
                customSetProgram = setProgram(customUniforms.getType());
            } catch (ReflectiveOperationException incompatible) {
                throw new IllegalStateException("OptiFine shader activation ABI",
                    incompatible);
            }
        }

        private static Class<?> programBaseType(Class<?> type) {
            for (Class<?> current = type; current != null;
                 current = current.getSuperclass()) {
                if ("net.optifine.shaders.Program".equals(current.getName())) {
                    return current;
                }
            }
            throw new IllegalStateException("unknown OptiFine Program type");
        }

        private static Field staticField(Class<?> owner, String name,
                                         Class<?> exactType)
            throws ReflectiveOperationException {
            Field field = owner.getDeclaredField(name);
            if (!Modifier.isStatic(field.getModifiers())
                || exactType != null && field.getType() != exactType) {
                throw new IllegalStateException("OptiFine field changed " + name);
            }
            field.setAccessible(true);
            return field;
        }

        private static Method setProgram(Class<?> owner)
            throws ReflectiveOperationException {
            Method method = owner.getMethod("setProgram", Integer.TYPE);
            if (method.getReturnType() != Void.TYPE
                || Modifier.isStatic(method.getModifiers())) {
                throw new IllegalStateException(
                    "OptiFine uniform setProgram changed");
            }
            return method;
        }
    }

    public static final class Result {
        private final boolean switched;
        private final boolean infrastructureFailure;
        private final boolean rollbackSucceeded;
        private final String detail;

        private Result(boolean switched, boolean infrastructureFailure,
                       boolean rollbackSucceeded, String detail) {
            this.switched = switched;
            this.infrastructureFailure = infrastructureFailure;
            this.rollbackSucceeded = rollbackSucceeded;
            this.detail = detail == null ? "" : detail;
        }

        private static Result success(String detail) {
            return new Result(true, false, true, detail);
        }

        private static Result rejected(String detail) {
            return new Result(false, false, true, detail);
        }

        private static Result failure(String detail, boolean infrastructure,
                                      boolean rollbackSucceeded) {
            return new Result(false, infrastructure, rollbackSucceeded, detail);
        }

        public boolean isSwitched() { return switched; }
        public boolean isInfrastructureFailure() { return infrastructureFailure; }
        public boolean isRollbackSucceeded() { return rollbackSucceeded; }
        public String getDetail() { return detail; }
    }
}
