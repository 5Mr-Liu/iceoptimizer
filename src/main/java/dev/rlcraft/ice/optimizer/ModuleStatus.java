package dev.rlcraft.ice.optimizer;

public final class ModuleStatus {
    private final OptimizationModule module;
    private final ModuleState state;
    private final long successes;
    private final long failures;
    private final long rejected;
    private final int observedTargets;
    private final int patchedTargets;
    private final String detail;

    public ModuleStatus(OptimizationModule module, ModuleState state, long successes, long failures, long rejected,
                        int observedTargets, int patchedTargets, String detail) {
        this.module = module;
        this.state = state;
        this.successes = successes;
        this.failures = failures;
        this.rejected = rejected;
        this.observedTargets = observedTargets;
        this.patchedTargets = patchedTargets;
        this.detail = detail == null ? "" : detail;
    }

    public OptimizationModule getModule() { return module; }
    public ModuleState getState() { return state; }
    public long getSuccesses() { return successes; }
    public long getFailures() { return failures; }
    public long getRejected() { return rejected; }
    public int getObservedTargets() { return observedTargets; }
    public int getPatchedTargets() { return patchedTargets; }
    public String getDetail() { return detail; }

    public boolean isOperational() {
        return state == ModuleState.ACTIVE || state == ModuleState.VERIFIED || state == ModuleState.DEGRADED;
    }
}
