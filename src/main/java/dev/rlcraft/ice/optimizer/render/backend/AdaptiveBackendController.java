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
    private static final long DEFAULT_TRANSITION_TIMEOUT_FRAMES = 600L;
    private static final int MAX_CONSECUTIVE_EMPTY_MODERN_ARMS = 8;
    private static final long REGRESSION_SAMPLE_INTERVAL = 16L;
    private static final double SEVERE_MEDIAN_REGRESSION = -0.25D;
    private static final double SEVERE_P95_REGRESSION = 0.25D;

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
    private final CandidateDataSource candidateDataSource;
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
    private boolean workloadRetestsDisabled;
    private BackendLifecycleState timedState;
    private long timedStateStartedFrame = Long.MIN_VALUE;
    private int timedStateProgress = Integer.MIN_VALUE;
    private long timedOutTransitions;
    private int consecutiveEmptyModernArms;
    private long lastEmptyModernFrame = Long.MIN_VALUE;
    private BackendLifecycleState awaitingCandidateState;
    private BackendLifecycleState parentSuspendedState;
    private boolean modernWorkObserved;

    public AdaptiveBackendController(ModuleCircuitBreaker breaker,
                                     EnumSet<ModernCapability> requiredCapabilities) {
        this(breaker, requiredCapabilities, CandidateDataSource.NONE);
    }

    public AdaptiveBackendController(ModuleCircuitBreaker breaker,
                                     EnumSet<ModernCapability> requiredCapabilities,
                                     CandidateDataSource candidateDataSource) {
        this(breaker, requiredCapabilities, 120, 8, 8, 0.05D, 0.02D,
            DEFAULT_PROFILE_WARMUP_FRAMES, DEFAULT_RETEST_COOLDOWN_FRAMES,
            DEFAULT_MAX_WORKLOAD_RETESTS, candidateDataSource);
    }

    public AdaptiveBackendController(ModuleCircuitBreaker breaker,
                                     EnumSet<ModernCapability> requiredCapabilities,
                                     int warmupFrames, int validationFrames,
                                     int requiredAbbaCycles,
                                     double minimumMedianImprovement,
                                     double maximumP95Regression) {
        this(breaker, requiredCapabilities, warmupFrames, validationFrames,
            requiredAbbaCycles, minimumMedianImprovement, maximumP95Regression,
            CandidateDataSource.NONE);
    }

    public AdaptiveBackendController(ModuleCircuitBreaker breaker,
                                     EnumSet<ModernCapability> requiredCapabilities,
                                     int warmupFrames, int validationFrames,
                                     int requiredAbbaCycles,
                                     double minimumMedianImprovement,
                                     double maximumP95Regression,
                                     CandidateDataSource candidateDataSource) {
        this(breaker, requiredCapabilities, warmupFrames, validationFrames,
            requiredAbbaCycles, minimumMedianImprovement, maximumP95Regression,
            DEFAULT_PROFILE_WARMUP_FRAMES, DEFAULT_RETEST_COOLDOWN_FRAMES,
            DEFAULT_MAX_WORKLOAD_RETESTS, candidateDataSource);
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
        this(breaker, requiredCapabilities, warmupFrames, validationFrames,
            requiredAbbaCycles, minimumMedianImprovement, maximumP95Regression,
            profileWarmupFrames, retestCooldownFrames, maximumWorkloadRetests,
            CandidateDataSource.NONE);
    }

    AdaptiveBackendController(ModuleCircuitBreaker breaker,
                              EnumSet<ModernCapability> requiredCapabilities,
                              int warmupFrames, int validationFrames,
                              int requiredAbbaCycles,
                              double minimumMedianImprovement,
                              double maximumP95Regression,
                              int profileWarmupFrames,
                              long retestCooldownFrames,
                              int maximumWorkloadRetests,
                              CandidateDataSource candidateDataSource) {
        if (breaker == null || requiredCapabilities == null || candidateDataSource == null) {
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
        this.candidateDataSource = candidateDataSource;
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
        workloadRetestsDisabled = false;
        resetTransitionTimer();
        timedOutTransitions = 0L;
        consecutiveEmptyModernArms = 0;
        lastEmptyModernFrame = Long.MIN_VALUE;
        awaitingCandidateState = null;
        parentSuspendedState = null;
        modernWorkObserved = false;
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

    /** Qualification measures every frame; certified rendering only samples. */
    public boolean shouldMeasureFrame(long frameId) {
        return frameId > 0L && (state == BackendLifecycleState.PAIRED_MEASURE
            || state == BackendLifecycleState.REGRESSION_MONITOR
                && frameId % REGRESSION_SAMPLE_INTERVAL == 0L);
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
            || state == BackendLifecycleState.WARMUP
            || awaitingCandidateState != null) return false;
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
            if (state == BackendLifecycleState.LEGACY
                && retestCooldownElapsed(frameId)) deferRetest(frameId);
            return false;
        }
        if (state != BackendLifecycleState.LEGACY
            && state != BackendLifecycleState.MODERN
            && state != BackendLifecycleState.REGRESSION_MONITOR) return false;
        if (evaluatedWorkloadProfiles == 0 || bucket <= highestEvaluatedBucket
            || workloadRetestsDisabled
            || workloadRetests >= maximumWorkloadRetests
            || !retestCooldownElapsed(frameId)) {
            // A due probe which still sees an equal/lighter workload has
            // learned everything it can this frame.  Re-arm the cooldown so
            // terminal Legacy rendering does not keep every transformed hot
            // hook alive indefinitely while waiting for a denser scene.
            if (state == BackendLifecycleState.LEGACY
                && evaluatedWorkloadProfiles > 0
                && bucket <= highestEvaluatedBucket
                && retestCooldownElapsed(frameId)) deferRetest(frameId);
            return false;
        }
        if (!observeWarmBucket(bucket, frameId)) return false;
        workloadRetests++;
        modernWorkObserved = false;
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

    /**
     * Includes the one Legacy case that can still enter a bounded, heavier
     * workload remeasurement. Terminal Legacy and quarantined controllers are
     * deliberately false so transformed hot paths can bypass ICE entirely.
     */
    public boolean mayInspectOrRetestCandidate() {
        return mayInspectOrRetestCandidate(Long.MAX_VALUE);
    }

    /** Frame-aware hot-path form which sleeps between bounded retest probes. */
    public boolean mayInspectOrRetestCandidate(long frameId) {
        if (shouldInspectCandidate()) return true;
        return state == BackendLifecycleState.LEGACY && awaitingCandidateState == null && profiledWorkloads
            && evaluatedWorkloadProfiles > 0 && !workloadRetestsDisabled
            && workloadRetests < maximumWorkloadRetests
            && frameId > 0L && retestCooldownElapsed(frameId);
    }

    /** True when no ordinary render callback can make this controller useful. */
    public boolean isDormant() {
        return state == BackendLifecycleState.QUARANTINED
            || state == BackendLifecycleState.LEGACY
                && !mayInspectOrRetestCandidate();
    }

    /** True when this controller needs no observed render work this frame. */
    public boolean isDormant(long frameId) {
        return state == BackendLifecycleState.QUARANTINED
            || state == BackendLifecycleState.LEGACY
                && !mayInspectOrRetestCandidate(frameId);
    }

    /**
     * Advances bounded lifecycle watchdogs without allocating. Validation and
     * ABBA states which receive no useful progress fall back to dormant Legacy
     * instead of charging every remaining frame in the session.
     */
    public boolean advanceFrame(long frameId) {
        if (frameId <= 0L || !isTimedTransition(state)) {
            resetTransitionTimer();
            return false;
        }
        int progress = transitionProgress();
        if (timedState != state || timedStateProgress != progress
            || timedStateStartedFrame == Long.MIN_VALUE
            || frameId < timedStateStartedFrame) {
            timedState = state;
            timedStateProgress = progress;
            timedStateStartedFrame = frameId;
            return false;
        }
        if (frameId - timedStateStartedFrame < DEFAULT_TRANSITION_TIMEOUT_FRAMES) {
            return false;
        }
        BackendLifecycleState expired = state;
        timedOutTransitions++;
        if (candidateDataSource != CandidateDataSource.NONE && profiledWorkloads
            && (expired == BackendLifecycleState.OUTPUT_VALIDATE
            || expired == BackendLifecycleState.PAIRED_MEASURE && !modernWorkObserved)) {
            waitForCandidateData(expired, frameId);
            return true;
        }
        workloadRetestsDisabled = true;
        fallback(expired.name() + " made no progress for "
            + DEFAULT_TRANSITION_TIMEOUT_FRAMES + " frames; dormant Legacy", false);
        resetTransitionTimer();
        return true;
    }

    /**
     * Records a MODERN ABBA arm which had no eligible work to submit. Eight
     * distinct frames suspend unproductive measurement. Model data may arrive
     * later, so an unpopulated model arm waits for a new capture instead of
     * recording an unmeasured load bucket as a permanent rejection.
     */
    public boolean recordEmptyModernArm(MeasurementArm arm, long frameId) {
        if (state != BackendLifecycleState.PAIRED_MEASURE
            || arm != MeasurementArm.MODERN
            || expectedMeasurementArm() != MeasurementArm.MODERN
            || frameId <= 0L) return false;
        if (lastEmptyModernFrame == frameId) return false;
        lastEmptyModernFrame = frameId;
        ignoredUnstableSamples++;
        if (consecutiveEmptyModernArms < MAX_CONSECUTIVE_EMPTY_MODERN_ARMS) {
            consecutiveEmptyModernArms++;
        }
        if (consecutiveEmptyModernArms < MAX_CONSECUTIVE_EMPTY_MODERN_ARMS) {
            return false;
        }
        if (candidateDataSource != CandidateDataSource.NONE
            && profiledWorkloads && !modernWorkObserved) {
            waitForCandidateData(BackendLifecycleState.PAIRED_MEASURE, frameId);
            return true;
        }
        workloadRetestsDisabled = true;
        fallback("modern measurement produced no candidate work for "
            + MAX_CONSECUTIVE_EMPTY_MODERN_ARMS + " frames; dormant Legacy", false);
        return true;
    }

    /** Cold compiler/admission paths keep collecting data while render hooks sleep. */
    public boolean isAwaitingCandidateData() { return awaitingCandidateState != null; }

    /** Actual submission is independent of delayed or unstable GPU measurements. */
    public void recordModernWork() {
        if (state != BackendLifecycleState.PAIRED_MEASURE) return;
        modernWorkObserved = true;
        consecutiveEmptyModernArms = 0;
        lastEmptyModernFrame = Long.MIN_VALUE;
    }

    /** A successful new model publication can resume the already-validated path. */
    public boolean candidateDataPublished() {
        if (candidateDataSource != CandidateDataSource.MODEL_PUBLICATION
            || awaitingCandidateState == null || state != BackendLifecycleState.LEGACY
            || !breaker.isOperational()) return false;
        resumeCandidate("new model capture published");
        return true;
    }

    /** New chunk uploads schedule a bounded retry at the next frame boundary. */
    public boolean terrainCandidateAvailable(long frameId) {
        if (candidateDataSource != CandidateDataSource.TERRAIN_UPLOAD
            || awaitingCandidateState == null || state != BackendLifecycleState.LEGACY
            || !breaker.isOperational() || frameId <= 0L
            || !retestCooldownElapsed(frameId)) return false;
        // The same quota covers both heavier-workload probes and data retries.
        if (workloadRetestsDisabled || workloadRetests >= maximumWorkloadRetests) return false;
        workloadRetests++;
        resumeCandidate("new terrain upload observed");
        return true;
    }

    private void resumeCandidate(String reason) {
        BackendLifecycleState resumed = awaitingCandidateState;
        awaitingCandidateState = null;
        modernWorkObserved = false;
        active = false;
        measurementBucket = -1;
        selectedModernBucket = -1;
        resetBucketObservation();
        clearMeasurements();
        resetTransitionTimer();
        state = resumed;
        detail = reason + "; resuming " + detailForState(resumed);
    }

    private void waitForCandidateData(BackendLifecycleState resumed, long frameId) {
        boolean terrain = candidateDataSource == CandidateDataSource.TERRAIN_UPLOAD;
        if (terrain && (workloadRetestsDisabled || workloadRetests >= maximumWorkloadRetests)) {
            workloadRetestsDisabled = true;
            fallback("terrain qualification retry budget exhausted; dormant Legacy", false);
            resetTransitionTimer();
            return;
        }
        awaitingCandidateState = resumed;
        active = false;
        clearMeasurements();
        resetBucketObservation();
        resetTransitionTimer();
        state = BackendLifecycleState.LEGACY;
        detail = terrain ? "terrain qualification awaits a new chunk upload"
            : "model qualification awaits newly captured geometry";
        if (terrain) nextRetestFrame = frameId > Long.MAX_VALUE - retestCooldownFrames
            ? Long.MAX_VALUE : frameId + retestCooldownFrames;
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
        else {
            modernWorkObserved = true;
            modernSamples[modernCount++] = frameNanos;
            consecutiveEmptyModernArms = 0;
            lastEmptyModernFrame = Long.MIN_VALUE;
        }
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
            if (improvement <= SEVERE_MEDIAN_REGRESSION
                || regression >= SEVERE_P95_REGRESSION) {
                workloadRetestsDisabled = true;
            }
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
        Arrays.sort(regressionSamples, 0, regressionCount);
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
        parentSuspendedState = null;
        awaitingCandidateState = null;
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
            workloadRetests, nextRetestFrame, workloadRetestsDisabled,
            timedOutTransitions);
    }

    private void evaluateMeasurements() {
        if (legacyCount == 0 || modernCount == 0) {
            fallback("insufficient paired samples", false);
            return;
        }
        Arrays.sort(legacySamples, 0, legacyCount);
        Arrays.sort(modernSamples, 0, modernCount);
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
        if (!beneficial && (learnedImprovement <= SEVERE_MEDIAN_REGRESSION
            || learnedP95Regression >= SEVERE_P95_REGRESSION)) {
            workloadRetestsDisabled = true;
        }
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
        parentSuspendedState = state;
        active = false;
        state = BackendLifecycleState.LEGACY;
        resetTransitionTimer();
        detail = reason == null ? "parent backend unavailable" : reason;
    }

    /** Restore only a dependency pause, never a measured or correctness rejection. */
    public boolean resumeAfterParent() {
        if (parentSuspendedState == null || state != BackendLifecycleState.LEGACY
            || !breaker.isOperational()) return false;
        BackendLifecycleState resumed = parentSuspendedState;
        parentSuspendedState = null;
        active = false;
        state = resumed == BackendLifecycleState.REGRESSION_MONITOR
            ? BackendLifecycleState.MODERN : resumed;
        if (state == BackendLifecycleState.PAIRED_MEASURE) clearMeasurements();
        resetTransitionTimer();
        detail = "terrain parent resumed; " + detailForState(state);
        return true;
    }

    private void quarantine(String reason) {
        parentSuspendedState = null;
        awaitingCandidateState = null;
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
        consecutiveEmptyModernArms = 0;
        lastEmptyModernFrame = Long.MIN_VALUE;
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

    private void deferRetest(long frameId) {
        nextRetestFrame = frameId > Long.MAX_VALUE - retestCooldownFrames
            ? Long.MAX_VALUE : frameId + retestCooldownFrames;
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

    private int transitionProgress() {
        if (state == BackendLifecycleState.WARMUP) return warmupSeen;
        if (state == BackendLifecycleState.OUTPUT_VALIDATE) return validationsSeen;
        if (state == BackendLifecycleState.PAIRED_MEASURE) return abbaIndex;
        return 0;
    }

    private static boolean isTimedTransition(BackendLifecycleState value) {
        return value == BackendLifecycleState.WARMUP
            || value == BackendLifecycleState.OUTPUT_VALIDATE
            || value == BackendLifecycleState.PAIRED_MEASURE;
    }

    private void resetTransitionTimer() {
        timedState = null;
        timedStateStartedFrame = Long.MIN_VALUE;
        timedStateProgress = Integer.MIN_VALUE;
    }

    private void requireState(BackendLifecycleState expected) {
        if (state != expected) throw new IllegalStateException("backend state "
            + state + ", expected " + expected);
    }

    private static long percentile(long[] values, int length, double quantile) {
        int index = (int) Math.ceil(quantile * length) - 1;
        return values[Math.max(0, Math.min(length - 1, index))];
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
