package dev.rlcraft.ice.optimizer.bridge;

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
            return module != null && OptimizerRegistry.isOperational(module);
        } catch (LinkageError | RuntimeException ignored) {
            // Injected call sites must retain the original mod path while the runtime is absent
            // or still becoming visible to LaunchClassLoader.
            return false;
        }
    }

    public static long currentFrameId() {
        try {
            ClientRuntimeAccess runtime = clientRuntime;
            return runtime == null ? 0L : runtime.currentFrameId();
        } catch (LinkageError | RuntimeException ignored) {
            return 0L;
        }
    }

    public static long currentClientTickId() {
        try {
            ClientRuntimeAccess runtime = clientRuntime;
            return runtime == null ? 0L : runtime.currentClientTickId();
        } catch (LinkageError | RuntimeException ignored) {
            return 0L;
        }
    }

    public static long currentWorldGeneration() {
        try {
            ClientRuntimeAccess runtime = clientRuntime;
            return runtime == null ? 0L : runtime.currentWorldGeneration();
        } catch (LinkageError | RuntimeException ignored) {
            return 0L;
        }
    }

    public static long currentResourceGeneration() {
        try {
            ClientRuntimeAccess runtime = clientRuntime;
            return runtime == null ? 0L : runtime.currentResourceGeneration();
        } catch (LinkageError | RuntimeException ignored) {
            return 0L;
        }
    }

    public static long currentGlContextGeneration() {
        try {
            ClientRuntimeAccess runtime = clientRuntime;
            return runtime == null ? 0L : runtime.currentGlContextGeneration();
        } catch (LinkageError | RuntimeException ignored) {
            return 0L;
        }
    }

    public static void success(String moduleId) {
        try {
            OptimizationModule module = OptimizationModule.byId(moduleId);
            if (module != null) OptimizerRegistry.breaker(module).recordSuccess();
        } catch (LinkageError | RuntimeException ignored) {
            // Fail open: optimizer bookkeeping must never break an adapted mod.
        }
    }

    public static void activate(String moduleId, String detail) {
        try {
            OptimizationModule module = OptimizationModule.byId(moduleId);
            if (module != null) OptimizerRegistry.breaker(module).activate(detail);
        } catch (LinkageError | RuntimeException ignored) {
            // Fail open: optimizer bookkeeping must never break an adapted mod.
        }
    }

    public static void failure(String moduleId, Throwable error) {
        try {
            OptimizationModule module = OptimizationModule.byId(moduleId);
            if (module != null) OptimizerRegistry.breaker(module).recordFailure(error);
        } catch (LinkageError | RuntimeException ignored) {
            // Fail open: optimizer bookkeeping must never break an adapted mod.
        }
    }

    public static void incompatible(String moduleId, String detail) {
        try {
            OptimizationModule module = OptimizationModule.byId(moduleId);
            if (module != null) OptimizerRegistry.breaker(module).forceIncompatible(detail);
        } catch (LinkageError | RuntimeException ignored) {
            // Fail open: optimizer bookkeeping must never break an adapted mod.
        }
    }
}
