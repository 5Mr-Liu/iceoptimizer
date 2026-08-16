package dev.rlcraft.ice.optimizer;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** A per-module fuse.  Failures disable only the affected optimization. */
public final class ModuleCircuitBreaker {
    private final OptimizationModule module;
    private final AtomicReference<ModuleState> state = new AtomicReference<ModuleState>(ModuleState.DISABLED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong successes = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicInteger observedTargets = new AtomicInteger();
    private final AtomicInteger patchedTargets = new AtomicInteger();
    private volatile int failureLimit = 3;
    private volatile String detail = "未初始化";
    private volatile boolean packLockRejected;
    private volatile String packLockDetail = "";

    public ModuleCircuitBreaker(OptimizationModule module) {
        this.module = module;
    }

    public synchronized void configure(boolean enabled, int configuredFailureLimit) {
        failureLimit = Math.max(1, configuredFailureLimit);
        consecutiveFailures.set(0);
        if (!enabled) {
            state.set(ModuleState.DISABLED);
            detail = "配置已关闭";
        } else if (packLockRejected) {
            state.set(ModuleState.INCOMPATIBLE);
            detail = packLockDetail;
        } else if (module == OptimizationModule.CORE_RUNTIME || module == OptimizationModule.RENDER_SUBMISSION) {
            state.set(ModuleState.VERIFIED);
            detail = "内建模块已验证";
        } else if (patchedTargets.get() > 0) {
            state.set(ModuleState.VERIFIED);
            detail = "目标字节码已通过结构验证";
        } else if (observedTargets.get() > 0) {
            state.set(ModuleState.INCOMPATIBLE);
            if (detail.isEmpty()) detail = "发现目标，但调用图与适配器不兼容";
        } else {
            state.set(ModuleState.WAITING_FOR_TARGET);
            detail = "等待目标模组类";
        }
    }

    public synchronized void resetRuntimeState() {
        state.set(ModuleState.DISABLED);
        consecutiveFailures.set(0);
        packLockRejected = false;
        packLockDetail = "";
        detail = "未初始化";
    }

    public synchronized void disable(String reason) {
        state.set(ModuleState.DISABLED);
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
            if (state.get() != ModuleState.DISABLED && state.get() != ModuleState.TRIPPED) state.set(ModuleState.VERIFIED);
        } else if (state.get() != ModuleState.DISABLED && patchedTargets.get() == 0) {
            state.set(ModuleState.INCOMPATIBLE);
        }
    }

    public synchronized void patchInstalled(String className, String fingerprint) {
        if (patchedTargets.get() == 0) patchedTargets.incrementAndGet();
        if (packLockRejected) return;
        detail = "补丁已安装，等待首次运行：" + className + " @ " + fingerprint;
        if (state.get() != ModuleState.DISABLED && state.get() != ModuleState.TRIPPED) {
            state.set(ModuleState.VERIFIED);
        }
    }

    public synchronized void activate(String activationDetail) {
        if (packLockRejected) return;
        ModuleState current = state.get();
        if (current == ModuleState.VERIFIED || current == ModuleState.DEGRADED) {
            state.set(ModuleState.ACTIVE);
            if (activationDetail != null && !activationDetail.isEmpty()) detail = activationDetail;
        }
    }

    public synchronized void recordSuccess() {
        successes.incrementAndGet();
        consecutiveFailures.set(0);
        if (packLockRejected) return;
        ModuleState current = state.get();
        if (current == ModuleState.DEGRADED) {
            state.set(ModuleState.ACTIVE);
            detail = "已从瞬时错误恢复";
        } else if (current == ModuleState.VERIFIED) {
            state.set(ModuleState.ACTIVE);
            detail = "已实际执行优化路径";
        }
    }

    public synchronized void recordRejected(String reason) {
        rejected.incrementAndGet();
        if (packLockRejected) return;
        if (reason != null && !reason.isEmpty()) detail = reason;
    }

    public synchronized void recordFailure(Throwable error) {
        failures.incrementAndGet();
        int consecutive = consecutiveFailures.incrementAndGet();
        if (packLockRejected) return;
        detail = compactError(error);
        if (consecutive >= failureLimit) state.set(ModuleState.TRIPPED);
        else if (!state.compareAndSet(ModuleState.ACTIVE, ModuleState.DEGRADED)) {
            state.compareAndSet(ModuleState.VERIFIED, ModuleState.DEGRADED);
        }
    }

    public synchronized void forceIncompatible(String reason) {
        if (module == OptimizationModule.CORE_RUNTIME || module == OptimizationModule.RENDER_SUBMISSION) return;
        if (state.get() == ModuleState.DISABLED) return;
        state.set(ModuleState.INCOMPATIBLE);
        detail = reason == null ? "目标运行时不兼容" : reason;
    }

    public synchronized void rejectByPackLock(String reason) {
        if (module == OptimizationModule.CORE_RUNTIME || module == OptimizationModule.RENDER_SUBMISSION) return;
        packLockRejected = true;
        packLockDetail = reason == null ? "包锁拒绝启用" : reason;
        if (state.get() != ModuleState.DISABLED) {
            state.set(ModuleState.INCOMPATIBLE);
            detail = packLockDetail;
        }
    }

    public boolean isOperational() {
        if (packLockRejected) return false;
        ModuleState current = state.get();
        return current == ModuleState.ACTIVE || current == ModuleState.VERIFIED || current == ModuleState.DEGRADED;
    }

    public ModuleStatus snapshot() {
        return new ModuleStatus(module, state.get(), successes.get(), failures.get(), rejected.get(),
            observedTargets.get(), patchedTargets.get(), detail);
    }

    private static String compactError(Throwable error) {
        if (error == null) return "未知错误";
        String message = error.getMessage();
        String value = error.getClass().getSimpleName() + (message == null || message.isEmpty() ? "" : ": " + message);
        return value.length() <= 160 ? value : value.substring(0, 159) + "…";
    }
}
