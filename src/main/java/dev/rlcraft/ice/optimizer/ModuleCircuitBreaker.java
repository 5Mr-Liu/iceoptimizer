package dev.rlcraft.ice.optimizer;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/** A per-module fuse. Failures disable only the affected optimization. */
public final class ModuleCircuitBreaker {
    private final OptimizationModule module;
    private final Runnable stateChangeListener;
    private final AtomicReference<ModuleState> state =
        new AtomicReference<ModuleState>(ModuleState.DISABLED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final LongAdder successes = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final AtomicInteger observedTargets = new AtomicInteger();
    private final AtomicInteger patchedTargets = new AtomicInteger();
    private volatile int failureLimit = 3;
    private volatile String detail = "未初始化";
    private volatile boolean packLockRejected;
    private volatile String packLockDetail = "";

    public ModuleCircuitBreaker(OptimizationModule module) {
        this(module, null);
    }

    ModuleCircuitBreaker(OptimizationModule module, Runnable stateChangeListener) {
        this.module = module;
        this.stateChangeListener = stateChangeListener;
    }

    public synchronized void configure(boolean enabled, int configuredFailureLimit) {
        failureLimit = Math.max(1, configuredFailureLimit);
        consecutiveFailures.set(0);
        if (!enabled) {
            setState(ModuleState.DISABLED);
            detail = "配置已关闭";
        } else if (packLockRejected) {
            setState(ModuleState.INCOMPATIBLE);
            detail = packLockDetail;
        } else if (module == OptimizationModule.CORE_RUNTIME
            || module == OptimizationModule.RENDER_SUBMISSION) {
            setState(ModuleState.VERIFIED);
            detail = "内建模块已验证";
        } else if (patchedTargets.get() > 0) {
            setState(ModuleState.VERIFIED);
            detail = "目标字节码已通过结构验证";
        } else if (observedTargets.get() > 0) {
            setState(ModuleState.INCOMPATIBLE);
            if (detail.isEmpty()) detail = "发现目标，但调用图与适配器不兼容";
        } else {
            setState(ModuleState.WAITING_FOR_TARGET);
            detail = "等待目标模组类";
        }
    }

    public synchronized void resetRuntimeState() {
        setState(ModuleState.DISABLED);
        consecutiveFailures.set(0);
        packLockRejected = false;
        packLockDetail = "";
        detail = "未初始化";
    }

    public synchronized void disable(String reason) {
        setState(ModuleState.DISABLED);
        consecutiveFailures.set(0);
        packLockRejected = false;
        packLockDetail = "";
        detail = reason == null || reason.isEmpty() ? "运行时已停止" : reason;
    }

    public synchronized void targetObserved(String className, String fingerprint, boolean supported) {
        observedTargets.incrementAndGet();
        if (packLockRejected) return;
        detail = className + " @ " + fingerprint;
        if (supported) {
            patchedTargets.incrementAndGet();
            ModuleState current = state.get();
            if (current != ModuleState.DISABLED && current != ModuleState.TRIPPED) {
                setState(ModuleState.VERIFIED);
            }
        } else if (state.get() != ModuleState.DISABLED && patchedTargets.get() == 0) {
            setState(ModuleState.INCOMPATIBLE);
        }
    }

    public synchronized void patchInstalled(String className, String fingerprint) {
        if (patchedTargets.get() == 0) patchedTargets.incrementAndGet();
        if (packLockRejected) return;
        detail = "补丁已安装，等待首次运行：" + className + " @ " + fingerprint;
        ModuleState current = state.get();
        if (current != ModuleState.DISABLED && current != ModuleState.TRIPPED) {
            setState(ModuleState.VERIFIED);
        }
    }

    public void activate(String activationDetail) {
        if (packLockRejected) return;
        for (;;) {
            ModuleState current = state.get();
            if (current != ModuleState.VERIFIED && current != ModuleState.DEGRADED) return;
            if (compareAndSetState(current, ModuleState.ACTIVE)) {
                if (!packLockRejected && activationDetail != null && !activationDetail.isEmpty()) {
                    detail = activationDetail;
                }
                return;
            }
        }
    }

    /** Hot path: striped accounting and no monitor acquisition. */
    public void recordSuccess() {
        successes.increment();
        consecutiveFailures.set(0);
        if (packLockRejected) return;
        for (;;) {
            ModuleState current = state.get();
            if (current != ModuleState.DEGRADED && current != ModuleState.VERIFIED) return;
            if (compareAndSetState(current, ModuleState.ACTIVE)) {
                if (!packLockRejected) {
                    detail = current == ModuleState.DEGRADED
                        ? "已从瞬时错误恢复" : "已实际执行优化路径";
                }
                return;
            }
        }
    }

    /** Hot path: rejection accounting must never serialize callers. */
    public void recordRejected(String reason) {
        rejected.increment();
        if (!packLockRejected && reason != null && !reason.isEmpty()) detail = reason;
    }

    public void recordFailure(Throwable error) {
        failures.increment();
        int consecutive = consecutiveFailures.incrementAndGet();
        if (packLockRejected) return;
        detail = compactError(error);
        for (;;) {
            ModuleState current = state.get();
            if (current == ModuleState.DISABLED || current == ModuleState.WAITING_FOR_TARGET
                || current == ModuleState.INCOMPATIBLE || current == ModuleState.TRIPPED) return;
            ModuleState next = consecutive >= failureLimit
                ? ModuleState.TRIPPED : ModuleState.DEGRADED;
            if (current == next || compareAndSetState(current, next)) return;
        }
    }

    public synchronized void forceIncompatible(String reason) {
        if (module == OptimizationModule.CORE_RUNTIME || module == OptimizationModule.RENDER_SUBMISSION) return;
        if (state.get() == ModuleState.DISABLED) return;
        setState(ModuleState.INCOMPATIBLE);
        detail = reason == null ? "目标运行时不兼容" : reason;
    }

    public synchronized void rejectByPackLock(String reason) {
        if (module == OptimizationModule.CORE_RUNTIME || module == OptimizationModule.RENDER_SUBMISSION) return;
        packLockRejected = true;
        packLockDetail = reason == null ? "包锁拒绝启用" : reason;
        if (state.get() != ModuleState.DISABLED) {
            setState(ModuleState.INCOMPATIBLE);
            detail = packLockDetail;
        }
    }

    public boolean isOperational() {
        return !packLockRejected && isOperationalState(state.get());
    }

    public ModuleStatus snapshot() {
        return new ModuleStatus(module, state.get(), successes.sum(), failures.sum(), rejected.sum(),
            observedTargets.get(), patchedTargets.get(), detail);
    }

    private void setState(ModuleState next) {
        ModuleState previous = state.getAndSet(next);
        if (previous != next) publishStateChange();
    }

    private boolean compareAndSetState(ModuleState expected, ModuleState next) {
        if (!state.compareAndSet(expected, next)) return false;
        if (expected != next) publishStateChange();
        return true;
    }

    private void publishStateChange() {
        Runnable listener = stateChangeListener;
        if (listener != null) listener.run();
    }

    private static boolean isOperationalState(ModuleState value) {
        return value == ModuleState.ACTIVE || value == ModuleState.VERIFIED
            || value == ModuleState.DEGRADED;
    }

    private static String compactError(Throwable error) {
        if (error == null) return "未知错误";
        String message = error.getMessage();
        String value = error.getClass().getSimpleName()
            + (message == null || message.isEmpty() ? "" : ": " + message);
        return value.length() <= 160 ? value : value.substring(0, 159) + "…";
    }
}
