package dev.rlcraft.ice.optimizer.render.backend;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.ModuleCircuitBreaker;
import java.util.Arrays;
import java.util.EnumSet;

/**
 * Independent self-test, correctness and online-benefit loop for one backend.
 * Measurements use workload fingerprints and ABBA order only; hardware labels
 * are neither accepted nor stored.
 */
public final class AdaptiveBackendController {
    private static final MeasurementArm[] ABBA = {
        MeasurementArm.LEGACY, MeasurementArm.MODERN,
        MeasurementArm.MODERN, MeasurementArm.LEGACY
    };
    private static final int WORKLOAD_BUCKETS = 8;
    private static final byte PROFILE_UNKNOWN = 0;
    private static final byte PROFILE_LEGACY = 1;
    private static final byte PROFILE_MODERN = 2;
    private static final int DEFAULT_PROFILE_WARMUP_FRAMES = 30;
    private static final long DEFAULT_RETEST_COOLDOWN_FRAMES = 600L;
    private static final int DEFAULT_MAX_WORKLOAD_RETESTS = 3;

    private final ModuleCircuitBreaker breaker;
    private final EnumSet<ModernCapability> requiredCapabilities;
    private final int warmupFrames;
    private final int validationFrames;
    private final int requiredAbbaCycles;
    private final double minimumMedianImprovement;
    private final double maximumP95Regression;
    private final int profileWarmupFrames;
    private final long retestCooldownFrames;
    private final int maximumWorkloadRetests;
    private BackendLifecycleState state = BackendLifecycleState.LEGACY;
    private long generation;
    private int warmupSeen;
    private int validationsSeen;
    private int abbaIndex;
    private SceneFingerprint pairedScene;
    private final long[] legacySamples;
    private final long[] modernSamples;
    private int legacyCount;
    private int modernCount;
    private boolean active;
    private String detail = "legacy";
    private double learnedImprovement;
    private double learnedP95Regression;
    private long ignoredUnstableSamples;
    private long learnedLegacyMedian;
    private long learnedLegacyP95;
    private final long[] regressionSamples = new long[16];
    private int regressionCount;
    private boolean recoverableFailurePending;
    private final byte[] workloadProfiles = new byte[WORKLOAD_BUCKETS];
    private boolean profiledWorkloads;
    private int measurementBucket = -1;
    private int selectedModernBucket = -1;
    private int observedBucket = -1;
    private long observedFrame = Long.MIN_VALUE;
    private int observedStableFrames;
    private long lastPreparedFrame = Long.MIN_VALUE;
    private long nextRetestFrame = Long.MIN_VALUE;
    private int evaluatedWorkloadProfiles;
    private int workloadRetests;
    private int highestEvaluatedBucket = -1;

    public AdaptiveBackendController(ModuleCircuitBreaker breaker,
                                     EnumSet<ModernCapability> requiredCapabilities) {
        this(breaker, requiredCapabilities, 120, 8, 8, 0.05D, 0.02D,
            DEFAULT_PROFILE_WARMUP_FRAMES, DEFAULT_RETEST_COOLDOWN_FRAMES,
            DEFAULT_MAX_WORKLOAD_RETESTS);
    }

    public AdaptiveBackendController(ModuleCircuitBreaker breaker,
                                     EnumSet<ModernCapability> requiredCapabilities,
                                     int warmupFrames, int validationFrames,
                                     int requiredAbbaCycles,
                                     double minimumMedianImprovement,
                                     double maximumP95Regression) {
        this(breaker, requiredCapabilities, warmupFrames, validationFrames,
            requiredAbbaCycles, minimumMedianImprovement, maximumP95Regression,
            DEFAULT_PROFILE_WARMUP_FRAMES, DEFAULT_RETEST_COOLDOWN_FRAMES,
            DEFAULT_MAX_WORKLOAD_RETESTS);
    }

    AdaptiveBackendController(ModuleCircuitBreaker breaker,
                              EnumSet<ModernCapability> requiredCapabilities,
                              int warmupFrames, int validationFrames,
                              int requiredAbbaCycles,
                              double minimumMedianImprovement,
                              double maximumP95Regression,
                              int profileWarmupFrames,
                              long retestCooldownFrames,
                              int maximumWorkloadRetests) {
        if (breaker == null || requiredCapabilities == null) {
            throw new IllegalArgumentException("backend dependencies");
        }
        this.breaker = breaker;
        this.requiredCapabilities = requiredCapabilities.isEmpty()
            ? EnumSet.noneOf(ModernCapability.class)
            : EnumSet.copyOf(requiredCapabilities);
        this.warmupFrames = Math.max(1, warmupFrames);
        this.validationFrames = Math.max(1, validationFrames);
        this.requiredAbbaCycles = Math.max(1, requiredAbbaCycles);
        this.minimumMedianImprovement = clamp(minimumMedianImprovement, 0.0D, 0.95D);
        this.maximumP95Regression = clamp(maximumP95Regression, 0.0D, 0.95D);
        this.profileWarmupFrames = Math.max(1, profileWarmupFrames);
        this.retestCooldownFrames = Math.max(1L, retestCooldownFrames);
        this.maximumWorkloadRetests = Math.max(0, maximumWorkloadRetests);
        int armCapacity = this.requiredAbbaCycles * 2;
        legacySamples = new long[armCapacity];
        modernSamples = new long[armCapacity];
    }

    public void begin(long value) {
        if (value <= 0L) throw new IllegalArgumentException("generation");
        generation = value;
        state = BackendLifecycleState.CAPABILITY_SELF_TEST;
        active = false;
        warmupSeen = 0;
        validationsSeen = 0;
        clearMeasurements();
        Arrays.fill(workloadProfiles, PROFILE_UNKNOWN);
        profiledWorkloads = false;
        measurementBucket = -1;
        selectedModernBucket = -1;
        resetBucketObservation();
        lastPreparedFrame = Long.MIN_VALUE;
        nextRetestFrame = Long.MIN_VALUE;
        evaluatedWorkloadProfiles = 0;
        workloadRetests = 0;
        highestEvaluatedBucket = -1;
        learnedImprovement = 0.0D;
        learnedP95Regression = 0.0D;
        recoverableFailurePending = false;
        detail = "capability self-test";
    }

    public void capabilityResult(CapabilityReport report) {
        requireState(BackendLifecycleState.CAPABILITY_SELF_TEST);
        if (report == null || !report.satisfies(requiredCapabilities)) {
            quarantine("required executable capability self-test failed");
            return;
        }
        state = BackendLifecycleState.WARMUP;
        detail = "warmup";
    }

    public void warmupFrame(boolean stable) {
        requireState(BackendLifecycleState.WARMUP);
        if (!stable) {
            ignoredUnstableSamples++;
            return;
        }
        if (++warmupSeen >= warmupFrames) {
            state = BackendLifecycleState.OUTPUT_VALIDATE;
            detail = "output validation";
        }
    }

    public void validationResult(boolean equivalent, String failureDetail) {
        requireState(BackendLifecycleState.OUTPUT_VALIDATE);
        if (!equivalent) {
            quarantine(failureDetail == null ? "output mismatch" : failureDetail);
            return;
        }
        if (++validationsSeen >= validationFrames) {
            state = BackendLifecycleState.PAIRED_MEASURE;
            detail = "ABBA paired measurement";
        }
    }

    public MeasurementArm expectedMeasurementArm() {
        return ABBA[abbaIndex & 3];
    }

    /**
     * Selects a scene-load profile without allocating on the render thread.
     * Initial and retry measurements wait for the same bucket to remain warm;
     * a rejected ordinary-load bucket therefore neither enables nor
     * permanently rejects a later dense scene.  Only previously unseen,
     * strictly heavier buckets may consume one of the bounded retry slots.
     */
    public boolean prepareProfiledWorkload(int requestedBucket, long frameId) {
        profiledWorkloads = true;
        int bucket = Math.max(0, Math.min(WORKLOAD_BUCKETS - 1,
            requestedBucket));
        if (frameId <= 0L) return false;
        lastPreparedFrame = frameId;
        if (state == BackendLifecycleState.QUARANTINED
            || state == BackendLifecycleState.CAPABILITY_SELF_TEST
            || state == BackendLifecycleState.WARMUP) return false;
        if (state == BackendLifecycleState.OUTPUT_VALIDATE) return true;
        if (state == BackendLifecycleState.PAIRED_MEASURE) {
            return prepareMeasurementBucket(bucket, frameId);
        }

        byte profile = workloadProfiles[bucket];
        if (profile == PROFILE_MODERN) {
            selectedModernBucket = bucket;
            resetBucketObservation();
            if (state == BackendLifecycleState.LEGACY) {
                active = false;
                state = BackendLifecycleState.MODERN;
                detail = "cached workload bucket " + bucket
                    + " passed; awaiting safe activation";
            }
            return state == BackendLifecycleState.MODERN
                || state == BackendLifecycleState.REGRESSION_MONITOR;
        }
        if (profile == PROFILE_LEGACY) {
            resetBucketObservation();
            return false;
        }
        if (state != BackendLifecycleState.LEGACY
            && state != BackendLifecycleState.MODERN
            && state != BackendLifecycleState.REGRESSION_MONITOR) return false;
        if (evaluatedWorkloadProfiles == 0 || bucket <= highestEvaluatedBucket
            || workloadRetests >= maximumWorkloadRetests
            || !retestCooldownElapsed(frameId)) {
            return false;
        }
        if (!observeWarmBucket(bucket, frameId)) return false;
        workloadRetests++;
        active = false;
        state = BackendLifecycleState.PAIRED_MEASURE;
        measurementBucket = bucket;
        selectedModernBucket = -1;
        clearMeasurements();
        detail = "ABBA paired remeasurement for workload bucket " + bucket;
        return true;
    }

    /** Render-thread predicate used before entering per-draw diagnostics. */
    public boolean shouldInspectCandidate() {
        return state == BackendLifecycleState.OUTPUT_VALIDATE
            || state == BackendLifecycleState.PAIRED_MEASURE
            || state == BackendLifecycleState.MODERN
            || state == BackendLifecycleState.REGRESSION_MONITOR;
    }

    public void recordMeasurement(SceneFingerprint scene, MeasurementArm arm,
                                  long frameNanos, boolean stable) {
        requireState(BackendLifecycleState.PAIRED_MEASURE);
        if (!stable || scene == null || frameNanos <= 0L) {
            ignoredUnstableSamples++;
            return;
        }
        if (pairedScene == null) pairedScene = scene;
        if (!pairedScene.isPairingCompatible(scene)) {
            ignoredUnstableSamples++;
            clearMeasurements();
            pairedScene = scene;
        }
        MeasurementArm expected = expectedMeasurementArm();
        if (arm != expected) {
            ignoredUnstableSamples++;
            return;
        }
        if (arm == MeasurementArm.LEGACY) legacySamples[legacyCount++] = frameNanos;
        else modernSamples[modernCount++] = frameNanos;
        abbaIndex++;
        if (abbaIndex >= requiredAbbaCycles * ABBA.length) evaluateMeasurements();
    }

    /** Must be called only at a frame/pass ownership boundary. */
    public boolean activateAtSafeBoundary() {
        if (state != BackendLifecycleState.MODERN) return false;
        active = true;
        state = BackendLifecycleState.REGRESSION_MONITOR;
        detail = "modern active; regression monitoring";
        breaker.activate(detail);
        return true;
    }

    public void recordRegressionWindow(long legacyEquivalentMedianNanos,
                                       long modernMedianNanos,
                                       long legacyP95Nanos, long modernP95Nanos,
                                       boolean stable) {
        requireState(BackendLifecycleState.REGRESSION_MONITOR);
        if (!stable || legacyEquivalentMedianNanos <= 0L || modernMedianNanos <= 0L
            || legacyP95Nanos <= 0L || modernP95Nanos <= 0L) {
            ignoredUnstableSamples++;
            return;
        }
        double improvement = 1.0D - modernMedianNanos / (double) legacyEquivalentMedianNanos;
        double regression = modernP95Nanos / (double) legacyP95Nanos - 1.0D;
        if (improvement < minimumMedianImprovement
            || regression > maximumP95Regression) {
            fallback("online benefit regressed", false);
        }
    }

    /** Rolling modern sample checked against the same-scene paired baseline. */
    public void recordRegressionSample(SceneFingerprint scene, long modernNanos,
                                       boolean stable) {
        requireState(BackendLifecycleState.REGRESSION_MONITOR);
        if (!stable || scene == null || pairedScene == null
            || !scene.isPairingCompatible(pairedScene) || modernNanos <= 0L
            || learnedLegacyMedian <= 0L || learnedLegacyP95 <= 0L) {
            ignoredUnstableSamples++;
            regressionCount = 0;
            return;
        }
        regressionSamples[regressionCount++] = modernNanos;
        if (regressionCount < regressionSamples.length) return;
        long median = percentile(regressionSamples, regressionCount, 0.50D);
        long p95 = percentile(regressionSamples, regressionCount, 0.95D);
        regressionCount = 0;
        recordRegressionWindow(learnedLegacyMedian, median,
            learnedLegacyP95, p95, true);
    }

    public void correctnessFailure(String reason) {
        quarantine(reason == null ? "runtime correctness validation failed" : reason);
    }

    public void runtimeFailure(Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        recoverableFailurePending = false;
        breaker.recordFailure(error);
        fallback(compactError(error), !breaker.isOperational());
    }

    /**
     * Records a failure that happened before any modern draw was submitted.
     * The current operation may use its exact Legacy path, while an isolated
     * transient error does not throw away a fully certified backend.  Only the
     * configured consecutive-failure limit quarantines the controller.
     */
    public boolean recoverableRuntimeFailure(Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        breaker.recordFailure(error);
        if (!breaker.isOperational()) {
            fallback(compactError(error), true);
            return false;
        }
        recoverableFailurePending = true;
        detail = "transient pre-draw failure; exact Legacy fallback: "
            + compactError(error);
        return true;
    }

    /** Clears the consecutive transient-failure streak after reauthentication. */
    public void recoverableRuntimeSuccess() {
        if (!recoverableFailurePending) return;
        recoverableFailurePending = false;
        breaker.recordSuccess();
        detail = detailForState(state);
    }

    public void fallback(String reason, boolean quarantine) {
        rejectCurrentWorkloadProfile();
        active = false;
        recoverableFailurePending = false;
        detail = reason == null ? "fallback" : reason;
        if (quarantine) {
            state = BackendLifecycleState.QUARANTINED;
            breaker.forceIncompatible(detail);
        } else {
            state = BackendLifecycleState.LEGACY;
            breaker.recordRejected(detail);
        }
    }

    public boolean isModernActive() {
        return active && state == BackendLifecycleState.REGRESSION_MONITOR
            && breaker.isOperational();
    }

    /** Render-thread fast path; unlike snapshot(), this performs no allocation. */
    public BackendLifecycleState lifecycleState() {
        return state;
    }

    public BackendStatus snapshot() {
        return new BackendStatus(state, generation, isModernActive(), detail,
            learnedImprovement, learnedP95Regression, legacyCount + modernCount,
            ignoredUnstableSamples, measurementBucket, evaluatedWorkloadProfiles,
            workloadRetests, nextRetestFrame);
    }

    private void evaluateMeasurements() {
        if (legacyCount == 0 || modernCount == 0) {
            fallback("insufficient paired samples", false);
            return;
        }
        long legacyMedian = percentile(legacySamples, legacyCount, 0.50D);
        long modernMedian = percentile(modernSamples, modernCount, 0.50D);
        long legacyP95 = percentile(legacySamples, legacyCount, 0.95D);
        long modernP95 = percentile(modernSamples, modernCount, 0.95D);
        learnedImprovement = 1.0D - modernMedian / (double) legacyMedian;
        learnedP95Regression = modernP95 / (double) legacyP95 - 1.0D;
        learnedLegacyMedian = legacyMedian;
        learnedLegacyP95 = legacyP95;
        boolean beneficial = learnedImprovement >= minimumMedianImprovement
            && learnedP95Regression <= maximumP95Regression;
        rememberMeasuredProfile(beneficial);
        if (beneficial) {
            state = BackendLifecycleState.MODERN;
            detail = profiledWorkloads && measurementBucket >= 0
                ? "workload bucket " + measurementBucket
                    + " paired measurement passed"
                : "paired measurement passed";
        } else {
            fallback("paired measurement found no safe benefit", false);
        }
    }

    /** Parent/child dependency convergence without converting quarantine. */
    public void suspendByParent(String reason) {
        if (state == BackendLifecycleState.LEGACY
            || state == BackendLifecycleState.QUARANTINED) return;
        fallback(reason == null ? "parent backend unavailable" : reason, false);
    }

    private void quarantine(String reason) {
        active = false;
        recoverableFailurePending = false;
        state = BackendLifecycleState.QUARANTINED;
        detail = reason;
        breaker.forceIncompatible(reason);
    }

    private void clearMeasurements() {
        abbaIndex = 0;
        legacyCount = 0;
        modernCount = 0;
        pairedScene = null;
        learnedLegacyMedian = 0L;
        learnedLegacyP95 = 0L;
        regressionCount = 0;
        Arrays.fill(legacySamples, 0L);
        Arrays.fill(modernSamples, 0L);
        Arrays.fill(regressionSamples, 0L);
    }

    private boolean prepareMeasurementBucket(int bucket, long frameId) {
        if (measurementBucket < 0) {
            measurementBucket = bucket;
            resetBucketObservation();
            detail = "workload bucket " + bucket + " cache warmup";
        }
        if (bucket != measurementBucket) {
            if (!observeWarmBucket(bucket, frameId)) return false;
            clearMeasurements();
            measurementBucket = bucket;
            detail = "ABBA paired measurement for workload bucket " + bucket;
            return true;
        }
        if (!observeWarmBucket(bucket, frameId)) return false;
        detail = "ABBA paired measurement for workload bucket " + bucket;
        return true;
    }

    private boolean observeWarmBucket(int bucket, long frameId) {
        if (observedBucket != bucket || observedFrame <= 0L
            || observedFrame == Long.MAX_VALUE
            || frameId != observedFrame && frameId != observedFrame + 1L) {
            observedBucket = bucket;
            observedFrame = frameId;
            observedStableFrames = 1;
            return observedStableFrames >= profileWarmupFrames;
        }
        if (frameId != observedFrame) {
            observedFrame = frameId;
            if (observedStableFrames < profileWarmupFrames) {
                observedStableFrames++;
            }
        }
        return observedStableFrames >= profileWarmupFrames;
    }

    private void resetBucketObservation() {
        observedBucket = -1;
        observedFrame = Long.MIN_VALUE;
        observedStableFrames = 0;
    }

    private boolean retestCooldownElapsed(long frameId) {
        return nextRetestFrame == Long.MIN_VALUE
            || frameId - nextRetestFrame >= 0L;
    }

    private void rememberMeasuredProfile(boolean modern) {
        if (!profiledWorkloads || measurementBucket < 0
            || measurementBucket >= workloadProfiles.length) return;
        if (workloadProfiles[measurementBucket] == PROFILE_UNKNOWN) {
            evaluatedWorkloadProfiles++;
        }
        workloadProfiles[measurementBucket] = modern
            ? PROFILE_MODERN : PROFILE_LEGACY;
        highestEvaluatedBucket = Math.max(highestEvaluatedBucket,
            measurementBucket);
        selectedModernBucket = modern ? measurementBucket : -1;
        long base = lastPreparedFrame <= 0L ? 1L : lastPreparedFrame;
        nextRetestFrame = base > Long.MAX_VALUE - retestCooldownFrames
            ? Long.MAX_VALUE : base + retestCooldownFrames;
        resetBucketObservation();
    }

    private void rejectCurrentWorkloadProfile() {
        if (!profiledWorkloads) return;
        int bucket = state == BackendLifecycleState.PAIRED_MEASURE
            ? measurementBucket : selectedModernBucket;
        if (bucket < 0 || bucket >= workloadProfiles.length) return;
        if (workloadProfiles[bucket] == PROFILE_UNKNOWN) {
            evaluatedWorkloadProfiles++;
        }
        workloadProfiles[bucket] = PROFILE_LEGACY;
        highestEvaluatedBucket = Math.max(highestEvaluatedBucket, bucket);
        selectedModernBucket = -1;
        long base = lastPreparedFrame <= 0L ? 1L : lastPreparedFrame;
        nextRetestFrame = base > Long.MAX_VALUE - retestCooldownFrames
            ? Long.MAX_VALUE : base + retestCooldownFrames;
        resetBucketObservation();
    }

    private void requireState(BackendLifecycleState expected) {
        if (state != expected) throw new IllegalStateException("backend state "
            + state + ", expected " + expected);
    }

    private static long percentile(long[] values, int length, double quantile) {
        long[] copy = Arrays.copyOf(values, length);
        Arrays.sort(copy);
        int index = (int) Math.ceil(quantile * length) - 1;
        return copy[Math.max(0, Math.min(copy.length - 1, index))];
    }

    private static double clamp(double value, double minimum, double maximum) {
        if (Double.isNaN(value)) return minimum;
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String detailForState(BackendLifecycleState value) {
        if (value == BackendLifecycleState.CAPABILITY_SELF_TEST) {
            return "capability self-test";
        }
        if (value == BackendLifecycleState.WARMUP) return "warmup";
        if (value == BackendLifecycleState.OUTPUT_VALIDATE) {
            return "output validation";
        }
        if (value == BackendLifecycleState.PAIRED_MEASURE) {
            return "ABBA paired measurement";
        }
        if (value == BackendLifecycleState.MODERN) {
            return "paired measurement passed";
        }
        if (value == BackendLifecycleState.REGRESSION_MONITOR) {
            return "modern active; regression monitoring";
        }
        return value == BackendLifecycleState.QUARANTINED
            ? "quarantined" : "legacy";
    }

    private static String compactError(Throwable error) {
        if (error == null) return "runtime failure";
        String message = error.getMessage();
        String value = error.getClass().getSimpleName()
            + (message == null || message.isEmpty() ? "" : ": " + message);
        return value.length() <= 160 ? value : value.substring(0, 159) + "…";
    }
}
