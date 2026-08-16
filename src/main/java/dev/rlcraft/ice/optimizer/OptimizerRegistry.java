package dev.rlcraft.ice.optimizer;

import dev.rlcraft.ice.optimizer.lock.PackLockStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Shared status bridge between the early Coremod and the regular runtime. */
public final class OptimizerRegistry {
    private static final OptimizationModule[] MODULES = OptimizationModule.values();
    private static final ModuleCircuitBreaker[] BREAKERS = new ModuleCircuitBreaker[MODULES.length];
    private static volatile long operationalMask;

    static {
        if (MODULES.length > Long.SIZE) {
            throw new IllegalStateException("ICE operational mask supports at most "
                + Long.SIZE + " modules");
        }
        Runnable publisher = new Runnable() {
            @Override public void run() { refreshOperationalMask(); }
        };
        for (OptimizationModule module : MODULES) {
            BREAKERS[module.ordinal()] = new ModuleCircuitBreaker(module, publisher);
        }
    }

    private OptimizerRegistry() {
    }

    public static synchronized void beginRuntime() {
        for (ModuleCircuitBreaker breaker : BREAKERS) breaker.resetRuntimeState();
        refreshOperationalMask();
    }

    public static synchronized void configure(OptimizerRuntimeConfig config) {
        for (OptimizationModule module : MODULES) {
            BREAKERS[module.ordinal()].configure(config.enabled(module),
                config.getCircuitBreakerFailures());
        }
        refreshOperationalMask();
    }

    public static ModuleCircuitBreaker breaker(OptimizationModule module) {
        return module == null ? null : BREAKERS[module.ordinal()];
    }

    public static ModuleCircuitBreaker breaker(int moduleOrdinal) {
        return moduleOrdinal >= 0 && moduleOrdinal < BREAKERS.length
            ? BREAKERS[moduleOrdinal] : null;
    }

    public static boolean isOperational(OptimizationModule module) {
        return module != null && isOperational(module.ordinal());
    }

    /** One volatile read; used by injected and other high-frequency call sites. */
    public static boolean isOperational(int moduleOrdinal) {
        return moduleOrdinal >= 0 && moduleOrdinal < MODULES.length
            && (operationalMask & (1L << moduleOrdinal)) != 0L;
    }

    public static void targetObserved(String moduleId, String className,
                                      String fingerprint, boolean supported) {
        OptimizationModule module = OptimizationModule.byId(moduleId);
        if (module != null) {
            BREAKERS[module.ordinal()].targetObserved(className, fingerprint, supported);
        }
    }

    public static void patchInstalled(String moduleId, String className, String fingerprint) {
        OptimizationModule module = OptimizationModule.byId(moduleId);
        if (module != null) BREAKERS[module.ordinal()].patchInstalled(className, fingerprint);
    }

    public static List<ModuleStatus> snapshot() {
        List<ModuleStatus> result = new ArrayList<ModuleStatus>(MODULES.length);
        for (ModuleCircuitBreaker breaker : BREAKERS) result.add(breaker.snapshot());
        return Collections.unmodifiableList(result);
    }

    public static void enforcePackLock(PackLockStatus status) {
        if (status == null || status.permitsPatches()) return;
        for (ModuleCircuitBreaker breaker : BREAKERS) {
            breaker.rejectByPackLock(status.getDetail());
        }
        refreshOperationalMask();
    }

    public static synchronized void shutdown(String detail) {
        for (ModuleCircuitBreaker breaker : BREAKERS) breaker.disable(detail);
        refreshOperationalMask();
    }

    static long operationalMaskForTest() {
        return operationalMask;
    }

    private static void refreshOperationalMask() {
        long next = 0L;
        for (int i = 0; i < BREAKERS.length; i++) {
            ModuleCircuitBreaker breaker = BREAKERS[i];
            if (breaker != null && breaker.isOperational()) next |= 1L << i;
        }
        operationalMask = next;
    }
}
