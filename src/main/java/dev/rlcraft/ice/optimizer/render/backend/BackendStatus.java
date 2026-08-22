package dev.rlcraft.ice.optimizer.render.backend;

public final class BackendStatus {
    private final BackendLifecycleState state;
    private final long generation;
    private final boolean active;
    private final String detail;
    private final double medianImprovement;
    private final double p95Regression;
    private final int measurementSamples;
    private final long ignoredUnstableSamples;
    private final int workloadBucket;
    private final int evaluatedWorkloadProfiles;
    private final int workloadRetests;
    private final long nextRetestFrame;

    BackendStatus(BackendLifecycleState state, long generation, boolean active,
                  String detail, double medianImprovement, double p95Regression,
                  int measurementSamples, long ignoredUnstableSamples,
                  int workloadBucket, int evaluatedWorkloadProfiles,
                  int workloadRetests, long nextRetestFrame) {
        this.state = state;
        this.generation = generation;
        this.active = active;
        this.detail = detail;
        this.medianImprovement = medianImprovement;
        this.p95Regression = p95Regression;
        this.measurementSamples = measurementSamples;
        this.ignoredUnstableSamples = ignoredUnstableSamples;
        this.workloadBucket = workloadBucket;
        this.evaluatedWorkloadProfiles = evaluatedWorkloadProfiles;
        this.workloadRetests = workloadRetests;
        this.nextRetestFrame = nextRetestFrame;
    }

    public BackendLifecycleState getState() { return state; }
    public long getGeneration() { return generation; }
    public boolean isActive() { return active; }
    public String getDetail() { return detail; }
    public double getMedianImprovement() { return medianImprovement; }
    public double getP95Regression() { return p95Regression; }
    public int getMeasurementSamples() { return measurementSamples; }
    public long getIgnoredUnstableSamples() { return ignoredUnstableSamples; }
    public int getWorkloadBucket() { return workloadBucket; }
    public int getEvaluatedWorkloadProfiles() {
        return evaluatedWorkloadProfiles;
    }
    public int getWorkloadRetests() { return workloadRetests; }
    public long getNextRetestFrame() { return nextRetestFrame; }
}
