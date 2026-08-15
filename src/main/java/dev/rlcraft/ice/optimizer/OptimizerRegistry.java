package dev.rlcraft.ice.optimizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import dev.rlcraft.ice.optimizer.lock.PackLockStatus;

/**
 * Shared status bridge between the very-early Coremod transformer and the
 * normal Forge client or dedicated-server runtime.
 */
public final class OptimizerRegistry {
    private static final Map<OptimizationModule, ModuleCircuitBreaker> BREAKERS =
        new EnumMap<OptimizationModule, ModuleCircuitBreaker>(OptimizationModule.class);

    static {
        for (OptimizationModule module : OptimizationModule.values()) {
            BREAKERS.put(module, new ModuleCircuitBreaker(module));
        }
    }

    private OptimizerRegistry() {
    }

    public static synchronized void beginRuntime() {
        for (OptimizationModule module : OptimizationModule.values()) {
            BREAKERS.get(module).resetRuntimeState();
        }
    }

    public static synchronized void configure(OptimizerRuntimeConfig config) {
        for (OptimizationModule module : OptimizationModule.values()) {
            BREAKERS.get(module).configure(config.enabled(module), config.getCircuitBreakerFailures());
        }
    }

    public static ModuleCircuitBreaker breaker(OptimizationModule module) {
        return BREAKERS.get(module);
    }

    public static boolean isOperational(OptimizationModule module) {
        ModuleCircuitBreaker breaker = BREAKERS.get(module);
        return breaker != null && breaker.isOperational();
    }

    public static void targetObserved(String moduleId, String className, String fingerprint, boolean supported) {
        OptimizationModule module = OptimizationModule.byId(moduleId);
        if (module != null) BREAKERS.get(module).targetObserved(className, fingerprint, supported);
    }

    public static void patchInstalled(String moduleId, String className, String fingerprint) {
        OptimizationModule module = OptimizationModule.byId(moduleId);
        if (module != null) BREAKERS.get(module).patchInstalled(className, fingerprint);
    }

    public static List<ModuleStatus> snapshot() {
        List<ModuleStatus> result = new ArrayList<ModuleStatus>(OptimizationModule.values().length);
        for (OptimizationModule module : OptimizationModule.values()) result.add(BREAKERS.get(module).snapshot());
        return Collections.unmodifiableList(result);
    }

    public static void enforcePackLock(PackLockStatus status) {
        if (status == null || status.permitsPatches()) return;
        for (OptimizationModule module : OptimizationModule.values()) {
            BREAKERS.get(module).rejectByPackLock(status.getDetail());
        }
    }

    public static synchronized void shutdown(String detail) {
        for (OptimizationModule module : OptimizationModule.values()) {
            BREAKERS.get(module).disable(detail);
        }
    }
}
