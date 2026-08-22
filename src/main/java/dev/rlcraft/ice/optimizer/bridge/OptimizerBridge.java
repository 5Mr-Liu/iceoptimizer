package dev.rlcraft.ice.optimizer.bridge;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.ModuleCircuitBreaker;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;

/** Stable, tiny ABI used by exact bytecode adapters. */
public final class OptimizerBridge {
    private static volatile ClientRuntimeAccess clientRuntime;

    private OptimizerBridge() {
    }

    public static void attachClientRuntime(ClientRuntimeAccess runtime) {
        clientRuntime = runtime;
    }

    public static void detachClientRuntime(ClientRuntimeAccess runtime) {
        if (clientRuntime == runtime) clientRuntime = null;
    }

    public static boolean isEnabled(String moduleId) {
        try {
            OptimizationModule module = OptimizationModule.byId(moduleId);
            return module != null && isEnabled(module.ordinal());
        } catch (LinkageError | RuntimeException ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            return false;
        }
    }

    /** Ordinal ABI: no String hashing and one volatile operational-mask read. */
    public static boolean isEnabled(int moduleOrdinal) {
        try {
            return OptimizerRegistry.isOperational(moduleOrdinal);
        } catch (LinkageError | RuntimeException ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            return false;
        }
    }

    public static long currentFrameId() {
        try {
            ClientRuntimeAccess runtime = clientRuntime;
            return runtime == null ? 0L : runtime.currentFrameId();
        } catch (LinkageError | RuntimeException ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            return 0L;
        }
    }

    public static long currentClientTickId() {
        try {
            ClientRuntimeAccess runtime = clientRuntime;
            return runtime == null ? 0L : runtime.currentClientTickId();
        } catch (LinkageError | RuntimeException ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            return 0L;
        }
    }

    public static long currentWorldGeneration() {
        try {
            ClientRuntimeAccess runtime = clientRuntime;
            return runtime == null ? 0L : runtime.currentWorldGeneration();
        } catch (LinkageError | RuntimeException ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            return 0L;
        }
    }

    public static long currentResourceGeneration() {
        try {
            ClientRuntimeAccess runtime = clientRuntime;
            return runtime == null ? 0L : runtime.currentResourceGeneration();
        } catch (LinkageError | RuntimeException ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            return 0L;
        }
    }

    public static long currentGlContextGeneration() {
        try {
            ClientRuntimeAccess runtime = clientRuntime;
            return runtime == null ? 0L : runtime.currentGlContextGeneration();
        } catch (LinkageError | RuntimeException ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            return 0L;
        }
    }

    public static void success(String moduleId) {
        try {
            OptimizationModule module = OptimizationModule.byId(moduleId);
            if (module != null) success(module.ordinal());
        } catch (LinkageError | RuntimeException ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            // Fail open: bookkeeping must never break an adapted mod.
        }
    }

    public static void success(int moduleOrdinal) {
        try {
            ModuleCircuitBreaker breaker = OptimizerRegistry.breaker(moduleOrdinal);
            if (breaker != null) breaker.recordSuccess();
        } catch (LinkageError | RuntimeException ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            // Fail open: bookkeeping must never break an adapted mod.
        }
    }

    public static void activate(String moduleId, String detail) {
        try {
            OptimizationModule module = OptimizationModule.byId(moduleId);
            if (module != null) activate(module.ordinal(), detail);
        } catch (LinkageError | RuntimeException ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            // Fail open: bookkeeping must never break an adapted mod.
        }
    }

    public static void activate(int moduleOrdinal, String detail) {
        try {
            ModuleCircuitBreaker breaker = OptimizerRegistry.breaker(moduleOrdinal);
            if (breaker != null) breaker.activate(detail);
        } catch (LinkageError | RuntimeException ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            // Fail open: bookkeeping must never break an adapted mod.
        }
    }

    public static void failure(String moduleId, Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        try {
            OptimizationModule module = OptimizationModule.byId(moduleId);
            if (module != null) failure(module.ordinal(), error);
        } catch (LinkageError | RuntimeException ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            // Fail open: bookkeeping must never break an adapted mod.
        }
    }

    public static void failure(int moduleOrdinal, Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        try {
            ModuleCircuitBreaker breaker = OptimizerRegistry.breaker(moduleOrdinal);
            if (breaker != null) breaker.recordFailure(error);
        } catch (LinkageError | RuntimeException ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            // Fail open: bookkeeping must never break an adapted mod.
        }
    }

    public static void incompatible(String moduleId, String detail) {
        try {
            OptimizationModule module = OptimizationModule.byId(moduleId);
            if (module != null) incompatible(module.ordinal(), detail);
        } catch (LinkageError | RuntimeException ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            // Fail open: bookkeeping must never break an adapted mod.
        }
    }

    public static void incompatible(int moduleOrdinal, String detail) {
        try {
            ModuleCircuitBreaker breaker = OptimizerRegistry.breaker(moduleOrdinal);
            if (breaker != null) breaker.forceIncompatible(detail);
        } catch (LinkageError | RuntimeException ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            // Fail open: bookkeeping must never break an adapted mod.
        }
    }
}
