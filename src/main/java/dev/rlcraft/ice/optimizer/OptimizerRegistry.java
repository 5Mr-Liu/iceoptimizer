package dev.rlcraft.ice.optimizer;

import dev.rlcraft.ice.optimizer.lock.PackLockStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/** Shared status bridge between the early Coremod and the regular runtime. */
public final class OptimizerRegistry {
    private static final OptimizationModule[] MODULES = OptimizationModule.values();
    private static final ModuleCircuitBreaker[] BREAKERS = new ModuleCircuitBreaker[MODULES.length];
    private static final Object MASK_LOCK = new Object();
    private static volatile boolean runtimePaused;
    private static volatile Consumer<Boolean> pauseObserver;
    private static volatile long operationalMask;
    private static volatile long operationalMaskHigh;

    static {
        if (MODULES.length > Long.SIZE * 2) {
            throw new IllegalStateException("ICE operational masks support at most "
                + (Long.SIZE * 2) + " modules");
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
        setRuntimePaused(false);
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

    /** Session switch; leaves configuration, failures and compatibility decisions intact. */
    public static void setRuntimePaused(boolean paused) {
        boolean changed;
        synchronized (MASK_LOCK) {
            changed = runtimePaused != paused;
            runtimePaused = paused;
            refreshOperationalMask();
        }
        Consumer<Boolean> observer = pauseObserver;
        if (changed && observer != null) notifyPauseObserver(observer, paused);
    }

    public static boolean isRuntimePaused() { return runtimePaused; }

    /** Optional recorder subscription; no polling or work at optimized call sites. */
    public static void observeRuntimePause(Consumer<Boolean> observer) {
        pauseObserver = observer;
        if (observer != null) notifyPauseObserver(observer, runtimePaused);
    }

    private static void notifyPauseObserver(Consumer<Boolean> observer, boolean paused) {
        try {
            observer.accept(Boolean.valueOf(paused));
        } catch (Throwable error) {
            FatalErrors.rethrowIfFatal(error);
            // Recording must not interrupt F6 resource handoff or change the module mask.
            org.apache.logging.log4j.LogManager.getLogger("ICE Optimizer")
                .warn("ICE pause recorder callback failed", error);
        }
    }

    /** For retaining model source during a pause, never for executing optimizations. */
    public static boolean isConfiguredOperational(OptimizationModule module) {
        return module != null && BREAKERS[module.ordinal()].isOperational();
    }

    /** One volatile read; used by injected and other high-frequency call sites. */
    public static boolean isOperational(int moduleOrdinal) {
        if (moduleOrdinal < 0 || moduleOrdinal >= MODULES.length) return false;
        return moduleOrdinal < Long.SIZE
            ? (operationalMask & (1L << moduleOrdinal)) != 0L
            : (operationalMaskHigh & (1L << (moduleOrdinal - Long.SIZE))) != 0L;
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

    static long operationalMaskHighForTest() { return operationalMaskHigh; }

    private static void refreshOperationalMask() {
        // Separate from the Registry monitor: breaker callbacks can hold a breaker lock.
        synchronized (MASK_LOCK) {
            long next = 0L;
            long nextHigh = 0L;
            if (!runtimePaused) {
                for (int i = 0; i < BREAKERS.length; i++) {
                    ModuleCircuitBreaker breaker = BREAKERS[i];
                    if (breaker == null || !breaker.isOperational()) continue;
                    if (i < Long.SIZE) next |= 1L << i;
                    else nextHigh |= 1L << (i - Long.SIZE);
                }
            }
            operationalMask = next;
            operationalMaskHigh = nextHigh;
        }
    }
}
