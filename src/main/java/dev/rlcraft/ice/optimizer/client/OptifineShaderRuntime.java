package dev.rlcraft.ice.optimizer.client;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.ModuleCircuitBreaker;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyMatrixStateTracker;
import dev.rlcraft.ice.optimizer.render.backend.AdaptiveBackendController;
import dev.rlcraft.ice.optimizer.render.backend.BackendLifecycleState;
import dev.rlcraft.ice.optimizer.render.backend.MeasurementArm;
import dev.rlcraft.ice.optimizer.render.backend.ModernCapability;
import dev.rlcraft.ice.optimizer.render.backend.RenderBackendId;
import dev.rlcraft.ice.optimizer.render.frame.RenderPass;
import dev.rlcraft.ice.optimizer.render.optifine.LwjglShaderImageCertification;
import dev.rlcraft.ice.optimizer.render.optifine.LwjglOptifineShaderActivation;
import dev.rlcraft.ice.optimizer.render.optifine.OptifineProgramIntrospector;
import dev.rlcraft.ice.optimizer.render.optifine.OptifineProgramState;
import dev.rlcraft.ice.optimizer.render.optifine.PreparedShaderPermutation;
import dev.rlcraft.ice.optimizer.render.optifine.ShaderPermutationKey;
import dev.rlcraft.ice.optimizer.render.optifine.ShaderCompileInstallGate;
import dev.rlcraft.ice.optimizer.render.optifine.ShaderTerrainLayoutCertification;
import dev.rlcraft.ice.optimizer.render.telemetry.CorrelatedRenderProfiler;
import dev.rlcraft.ice.optimizer.render.telemetry.CpuWorkKind;
import dev.rlcraft.ice.optimizer.render.telemetry.RenderCounter;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import net.minecraft.util.BlockRenderLayer;

import dev.rlcraft.ice.optimizer.client.ModernRendererRuntime.RenderBackendSample;
import dev.rlcraft.ice.optimizer.client.ModernRendererRuntime.OptifineRegionDrawSample;

/** Owns OptiFine source queues, binding publication and certified program intervals. */
final class OptifineShaderRuntime {
    private final ModernRendererRuntime runtime;

    OptifineShaderRuntime(ModernRendererRuntime runtime) {
        this.runtime = runtime;
    }

    private static final int MAX_PENDING_SHADER_CANDIDATES = 64;

    private static final long MAX_PENDING_SHADER_BYTES = 32L * 1024L * 1024L;

    private static final int MAX_PENDING_SHADER_VALIDATIONS = 64;

    private static final int MAX_SHADER_BINDINGS = 256;

    private final ArrayDeque<CapturedShaderSources> pendingShaderCandidates =
        new ArrayDeque<CapturedShaderSources>();

    private long pendingShaderCandidateBytes;

    private final ArrayDeque<ShaderValidationRequest> pendingShaderValidations =
        new ArrayDeque<ShaderValidationRequest>();

    private final IdentityHashMap<Object, ShaderProgramBinding> shaderBindings =
        new IdentityHashMap<Object, ShaderProgramBinding>();

    private final HashMap<Integer, ShaderProgramBinding> shaderBindingsById =
        new HashMap<Integer, ShaderProgramBinding>();

    private boolean shaderBindingsPoisoned;

    private ShaderProgramBinding shaderBindingPublicationWitness;

    private ShaderProgramBinding[] shaderBindingCleanupWitness;

    private ModernRendererRuntime.ShaderBindingPublicationFault shaderBindingPublicationFault;

    private final OptifineProgramIntrospector shaderIntrospector =
        new OptifineProgramIntrospector();

    OptifineProgramState optifineProgramState;

    Object optifineProgramIdentity;

    private RenderBackendSample activeShaderSample;

    private ShaderProgramBinding activeShaderSampleBinding;

    private ShaderProgramBinding activeNativeShaderBinding;

    volatile boolean shaderPackStateKnown;

    volatile boolean shaderPackActive;

    long optifineRegionObservedGeneration;

    void setShaderPackState(boolean known, boolean active) {
        shaderPackStateKnown = known;
        shaderPackActive = known && active;
        if (!shaderPackActive) {
            if (runtime.initialized && runtime.components.threadGuard != null && runtime.components.threadGuard.isRenderThread()) {
                finishActiveShaderInterval(false, true);
            }
            optifineProgramState = null;
            optifineProgramIdentity = null;
        }
        if ((!known || active) && runtime.components.depthHistory != null && runtime.components.threadGuard != null
            && runtime.components.threadGuard.isRenderThread()) {
            runtime.components.depthHistory.invalidateScene();
            runtime.measurements.clearHzbGpuSamples();
        }
    }

    boolean isShaderPackSafeForNativeVertexFormats() {
        return shaderPackStateKnown && !shaderPackActive;
    }

    boolean isShaderPackSafeForTerrainArena() {
        if (!shaderPackStateKnown) return false;
        if (!shaderPackActive) return true;
        ShaderProgramBinding binding = activeNativeShaderBinding;
        EarlyGlStateTracker.Snapshot gl = EarlyGlStateTracker.snapshot();
        boolean bindingPresent = binding != null
            && activeShaderSampleBinding == binding
            && shaderBindings.get(binding.programIdentity) == binding
            && shaderBindingsById.get(Integer.valueOf(binding.legacyProgram))
                == binding;
        boolean identityMatches = bindingPresent
            && optifineProgramIdentity == binding.programIdentity;
        boolean programMatches = identityMatches && optifineProgramState != null
            && optifineProgramState.getProgramId() == binding.candidateProgram
            && gl != null && gl.getProgram() == binding.candidateProgram;
        boolean activationStateMatches = bindingPresent
            && shaderTerrainActivationStateMatches(binding.certifiedState,
                optifineProgramState, gl == null ? -1 : gl.getDrawFramebuffer());
        boolean generationsCurrent = bindingPresent
            && bindingGenerationsCurrent(binding);
        boolean certified = generationsCurrent && runtime.components.shaders != null
            && runtime.components.shaders.isCertified(binding.key);
        return shaderTerrainGate(shaderPackStateKnown, shaderPackActive,
            bindingPresent, identityMatches, programMatches,
            activationStateMatches, generationsCurrent, certified,
            bindingPresent && binding.terrainLayoutCertified);
    }

    static boolean shaderTerrainGate(boolean known, boolean active,
                                     boolean bindingPresent,
                                     boolean identityMatches,
                                     boolean programMatches,
                                     boolean activationStateMatches,
                                     boolean generationsCurrent,
                                     boolean outputCertified,
                                     boolean layoutCertified) {
        return known && (!active || bindingPresent && identityMatches
            && programMatches && activationStateMatches && generationsCurrent
            && outputCertified && layoutCertified);
    }

    static boolean shaderTerrainActivationStateMatches(
        OptifineProgramState certified, OptifineProgramState current,
        int trackedDrawFramebuffer) {
        return certified != null && current != null
            && current.getFramebuffer() > 0
            && current.getFramebuffer() == trackedDrawFramebuffer
            && certified.isLogicalActivationEquivalent(current);
    }

    synchronized boolean queueOptifineShaderSources(
        String packId, String program, String permutation,
        long resourceGeneration, long shaderGeneration,
        String vertexPath, String vertexSource,
        String geometryPath, String geometrySource,
        String fragmentPath, String fragmentSource,
        String propertiesSource) {
        return queueOptifineShaderSources(null, packId, program, permutation,
            resourceGeneration, shaderGeneration, vertexPath, vertexSource,
            geometryPath, geometrySource, fragmentPath, fragmentSource,
            propertiesSource);
    }

    synchronized boolean queueOptifineShaderSources(
        Object programIdentity, String packId, String program,
        String permutation, long resourceGeneration, long shaderGeneration,
        String vertexPath, String vertexSource,
        String geometryPath, String geometrySource,
        String fragmentPath, String fragmentSource,
        String propertiesSource) {
        if (runtime.shutdownRequested) return false;
        long currentResources = runtime.epochs.currentResourceGeneration();
        long currentShaders = runtime.epochs.currentShaderPackGeneration();
        purgeStaleShaderCandidates(currentResources, currentShaders);
        if (resourceGeneration != currentResources
            || shaderGeneration != currentShaders
            || pendingShaderCandidates.size() >= MAX_PENDING_SHADER_CANDIDATES) {
            return false;
        }
        long bytes;
        try {
            bytes = capturedShaderBytes(vertexSource, geometrySource,
                fragmentSource, propertiesSource);
        } catch (RuntimeException rejected) {
            FatalErrors.rethrowIfFatal(rejected);
            return false;
        }
        if (bytes > MAX_PENDING_SHADER_BYTES - pendingShaderCandidateBytes) {
            return false;
        }
        long heapBytes;
        try {
            heapBytes = capturedShaderHeapBytes(packId, program, permutation,
                vertexPath, vertexSource, geometryPath, geometrySource,
                fragmentPath, fragmentSource, propertiesSource);
        } catch (RuntimeException rejected) {
            FatalErrors.rethrowIfFatal(rejected);
            return false;
        }
        CacheBudget.Reservation reservation = runtime.budget.tryReserve(
            BudgetKind.HEAP, heapBytes);
        if (reservation == null) return false;
        CapturedShaderSources captured;
        try {
            captured = new CapturedShaderSources(programIdentity, packId, program,
                permutation, resourceGeneration, shaderGeneration, vertexPath,
                vertexSource, geometryPath, geometrySource, fragmentPath,
                fragmentSource, propertiesSource, bytes, reservation);
            pendingShaderCandidates.addLast(captured);
            reservation = null;
        } catch (Throwable publicationFailure) {
            Throwable failure = publicationFailure;
            if (reservation != null) try { reservation.close(); }
            catch (Throwable cleanupFailure) {
                failure = ModernRendererRuntime.appendRuntimeFailure(failure, cleanupFailure);
            }
            FatalErrors.rethrowIfFatal(failure);
            return false;
        }
        pendingShaderCandidateBytes += bytes;
        return true;
    }

    boolean compileShaderPermutation(PreparedShaderPermutation prepared) {
        return compileShaderPermutation(prepared, 0);
    }

    boolean compileShaderPermutation(PreparedShaderPermutation prepared,
                                             int legacyProgramId) {
        if (runtime.shutdownRequested || !runtime.initialized || prepared == null || runtime.components.shaderPipeline == null
            || runtime.components.shaderCompiler == null || runtime.components.shaderInstaller == null || runtime.components.shaders == null
            || runtime.components.capabilities == null || legacyProgramId < 0
            || !runtime.components.capabilities.passed(ModernCapability.SHADER_PROGRAM)
            || !OptimizerRegistry.isOperational(
                OptimizationModule.OPTIFINE_SHADER_COMPILE)
            || runtime.components.threadGuard == null || !runtime.components.threadGuard.isRenderThread()
            || runtime.currentStamp != null) return false;
        if (prepared.getKey().getResourceGeneration()
                != runtime.epochs.currentResourceGeneration()
            || prepared.getKey().getShaderGeneration()
                != runtime.epochs.currentShaderPackGeneration()) return false;
        ShaderCompileInstallGate.Outcome outcome = ShaderCompileInstallGate.execute(
            prepared, runtime.components.shaderPipeline, runtime.components.shaderCompiler, runtime.components.shaderInstaller,
            legacyProgramId, runtime.epochs.currentResourceGeneration(),
            runtime.epochs.currentGlContextGeneration(),
            runtime.epochs.currentShaderPackGeneration());
        if (!outcome.isInstalled()) {
            if (!outcome.wasCompileAttempted() || outcome.isCompiled()) {
                runtime.components.shaders.recordCompile(prepared.getKey(), false,
                    outcome.getDetail());
            }
            if (outcome.isInfrastructureFailure()) {
                shaderDomainFailure(OptimizationModule.OPTIFINE_SHADER_COMPILE,
                    new IllegalStateException(outcome.getDetail()));
            } else {
                shaderDomainRejected(OptimizationModule.OPTIFINE_SHADER_COMPILE,
                    outcome.getDetail());
            }
            return false;
        }
        shaderDomainSuccess(OptimizationModule.OPTIFINE_SHADER_COMPILE);
        return true;
    }

    RenderBackendId selectOptifineShaderBackend(
        ShaderPermutationKey permutation, boolean optifineRegionAvailable,
        boolean nativeVertexLayoutCompatible, boolean nativeBackendProfitable) {
        boolean regionAvailable = optifineRegionAvailable
            || isOptifineRegionBackendAvailable();
        if (runtime.insideLegacyIsland()) {
            return regionAvailable ? RenderBackendId.OF_COMPAT_REGION
                : RenderBackendId.LEGACY;
        }
        if (!shaderPackStateKnown || !runtime.initialized || runtime.components.shaderBackendSelector == null
            || runtime.components.shaderInstaller == null || runtime.components.threadGuard == null
            || !runtime.components.threadGuard.isRenderThread() || !shaderDomainsOperational()) {
            return regionAvailable ? RenderBackendId.OF_COMPAT_REGION
                : RenderBackendId.LEGACY;
        }
        boolean installed = permutation != null && runtime.components.shaderInstaller.isInstalled(
            permutation, runtime.epochs.currentResourceGeneration(),
            runtime.epochs.currentGlContextGeneration(),
            runtime.epochs.currentShaderPackGeneration());
        return runtime.components.shaderBackendSelector.select(shaderPackActive,
            regionAvailable, permutation, installed,
            nativeVertexLayoutCompatible, nativeBackendProfitable);
    }

    boolean isOptifineRegionBackendAvailable() {
        return runtime.initialized && runtime.components.threadGuard != null && runtime.components.threadGuard.isRenderThread()
            && optifineRegionObservedGeneration == runtime.combinedGeneration()
            && OptimizerRegistry.isOperational(
                OptimizationModule.OPTIFINE_REGION_BACKEND);
    }

    OptifineRegionDrawSample beginOptifineRegionDraw(
        Object layer, int indexPosition, int countPosition, int commandCapacity,
        int drawMode, int bufferId, int positionTop, int sizeUsed) {
        if (!runtime.initialized || runtime.currentStamp == null || runtime.components.profiler == null
            || runtime.insideLegacyIsland()
            || runtime.components.threadGuard == null || !runtime.components.threadGuard.isRenderThread()) return null;
        AdaptiveBackendController backend = runtime.components.backends.get(
            OptimizationModule.OPTIFINE_REGION_BACKEND);
        if (backend == null || !backend.shouldInspectCandidate()) return null;
        RenderPass pass = layer instanceof BlockRenderLayer
            ? ModernRendererRuntime.terrainPass((BlockRenderLayer) layer) : RenderPass.MAIN_SOLID;
        boolean valid = indexPosition > 0 && indexPosition == countPosition
            && commandCapacity > 0 && indexPosition <= commandCapacity
            && drawMode > 0 && bufferId > 0 && positionTop >= 0
            && sizeUsed >= 0 && sizeUsed <= positionTop;
        CorrelatedRenderProfiler.CpuScope cpu = runtime.components.profiler.beginCpu(runtime.currentStamp,
            pass, RenderBackendId.OF_COMPAT_REGION, CpuWorkKind.SUBMISSION);
        CorrelatedRenderProfiler.GpuScope gpu = runtime.components.profiler.beginGpu(runtime.currentStamp,
            pass, RenderBackendId.OF_COMPAT_REGION);
        return new OptifineRegionDrawSample(pass, indexPosition,
            commandCapacity, valid, cpu, gpu);
    }

    void endOptifineRegionDraw(OptifineRegionDrawSample sample,
                                      int indexPosition, int countPosition,
                                      int commandCapacity, Throwable error) {
        if (sample == null) return;
        Throwable completionError = error;
        try { if (sample.gpuScope != null) sample.gpuScope.close(); }
        catch (Throwable scopeFailure) {
            completionError = ModernRendererRuntime.appendRuntimeFailure(completionError, scopeFailure);
        }
        try { sample.cpuScope.close(); }
        catch (Throwable scopeFailure) {
            completionError = ModernRendererRuntime.appendRuntimeFailure(completionError, scopeFailure);
        }
        FatalErrors.rethrowIfFatal(completionError);
        error = completionError;
        AdaptiveBackendController backend = runtime.components.backends.get(
            OptimizationModule.OPTIFINE_REGION_BACKEND);
        if (error != null) {
            if (backend != null) backend.runtimeFailure(error);
            if (runtime.components.profiler != null && runtime.currentStamp != null) {
                runtime.components.profiler.addCounter(runtime.currentStamp, sample.pass,
                    RenderBackendId.OF_COMPAT_REGION,
                    RenderCounter.LEGACY_FALLBACK, 1L);
            }
            runtime.detail = "OptiFine VboRegion 原提交异常；ICE 观察器已独立回退："
                + error.getClass().getSimpleName();
            return;
        }
        boolean postValid = indexPosition == 0 && countPosition == 0
            && commandCapacity == sample.commandCapacity;
        boolean equivalent = sample.preDrawValid && postValid;
        if (backend != null && backend.lifecycleState()
            == BackendLifecycleState.OUTPUT_VALIDATE) {
            runtime.measurements.recordValidationOnce(OptimizationModule.OPTIFINE_REGION_BACKEND,
                equivalent, equivalent ? null
                    : "OptiFine VboRegion queue/reset invariant mismatch");
        }
        if (!equivalent) {
            if (backend != null && backend.lifecycleState()
                != BackendLifecycleState.QUARANTINED) {
                backend.correctnessFailure(
                    "OptiFine VboRegion queue/reset invariant mismatch");
            }
            return;
        }
        optifineRegionObservedGeneration = runtime.combinedGeneration();
        if (runtime.components.profiler != null && runtime.currentStamp != null) {
            runtime.components.profiler.addCounter(runtime.currentStamp, sample.pass,
                RenderBackendId.OF_COMPAT_REGION, RenderCounter.MULTI_DRAW, 1L);
            runtime.components.profiler.addCounter(runtime.currentStamp, sample.pass,
                RenderBackendId.OF_COMPAT_REGION, RenderCounter.DRAW,
                sample.commandCount);
        }
        // One successful observation establishes this generation's OptiFine
        // fallback. Further draw timing and queue scans add no readiness evidence.
        if (backend != null) {
            backend.fallback("OptiFine-owned compatibility backend observed; "
                + "no ICE replacement selected", false);
        }
    }

    int certifiedNativeShaderProgram(ShaderPermutationKey permutation) {
        if (!runtime.initialized || permutation == null || runtime.components.shaderInstaller == null
            || runtime.components.shaders == null || runtime.components.threadGuard == null
            || !runtime.components.threadGuard.isRenderThread() || !shaderDomainsOperational()) return 0;
        return runtime.components.shaderInstaller.certifiedProgram(permutation, runtime.components.shaders,
            runtime.epochs.currentResourceGeneration(), runtime.epochs.currentGlContextGeneration(),
            runtime.epochs.currentShaderPackGeneration());
    }

    OptifineProgramState currentOptifineProgramState() {
        return optifineProgramState;
    }

    void shaderBridgeFailure(Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        AdaptiveBackendController backend = runtime.components.backends.get(
            OptimizationModule.OPTIFINE_SHADER_BRIDGE);
        if (backend != null) backend.runtimeFailure(error);
        runtime.detail = "OptiFine Shader 生命周期桥异常；未认证程序保持 OF/Legacy："
            + (error == null ? "unknown" : error.getClass().getSimpleName());
    }

    void beforeOptifineProgramSwitch() {
        beforeOptifineProgramSwitch(null, true);
    }

    void beforeOptifineProgramSwitch(Object requestedProgram,
                                            boolean logicalProgramChanged) {
        if (!runtime.initialized || runtime.components.threadGuard == null || !runtime.components.threadGuard.isRenderThread()) return;
        boolean intervalOpen = activeShaderSample != null
            || activeNativeShaderBinding != null;
        if (logicalProgramChanged || intervalOpen) {
            runtime.flushBatches();
            if (runtime.currentStamp != null) runtime.components.coordinator.observableBarrier(
                "OptiFine useProgram");
        }
        finishActiveShaderInterval(true, true);
        if (logicalProgramChanged || intervalOpen) {
            EarlyGlStateTracker.invalidate();
            EarlyMatrixStateTracker.invalidate();
        }
    }

    void observeOptifineProgram(OptifineProgramState state) {
        observeOptifineProgram(null, state);
    }

    void observeOptifineProgram(Object programIdentity,
                                       OptifineProgramState state) {
        if (!runtime.initialized || state == null || runtime.components.threadGuard == null
            || !runtime.components.threadGuard.isRenderThread()) return;
        optifineProgramState = state;
        optifineProgramIdentity = programIdentity;
        EarlyGlStateTracker.useProgram(state.getProgramId());
        boolean synchronizedProgram = runtime.components.shaderActivation != null
            && runtime.components.shaderActivation.isProgramSynchronized(programIdentity,
                state.getProgramId());
        reconcileObservedShaderProgram(state.getProgramId(),
            synchronizedProgram);
        if (runtime.currentStamp != null && runtime.components.profiler != null) {
            runtime.components.profiler.addCounter(runtime.currentStamp, RenderPass.FINAL,
                RenderBackendId.OF_COMPAT_REGION, RenderCounter.PROGRAM_SWITCH, 1L);
        }
        if (runtime.shutdownRequested) return;
        if (runtime.insideLegacyIsland()) return;
        if (programIdentity == null || !shaderPackStateKnown || !shaderPackActive) {
            return;
        }
        if (shaderBindingsPoisoned) return;
        ShaderProgramBinding binding = shaderBindings.get(programIdentity);
        if (!bindingCurrent(binding, programIdentity, state.getProgramId())) return;
        queueShaderValidation(binding, state);
        activateCertifiedShaderForInterval(binding, state);
    }

    boolean reconcileObservedShaderProgram(int observedProgram,
                                           boolean synchronizedProgram) {
        ShaderProgramBinding uncertain = activeNativeShaderBinding;
        if (uncertain == null || !synchronizedProgram
            || observedProgram < 0
            || observedProgram == uncertain.candidateProgram) return false;
        activeNativeShaderBinding = null;
        runtime.detail = "OptiFine 原 useProgram 已确认恢复；candidate 所有权已对账";
        return true;
    }

    void abortOptifineProgramSwitch() {
        finishActiveShaderInterval(false, true);
        EarlyGlStateTracker.invalidate();
        EarlyMatrixStateTracker.invalidate();
        optifineProgramState = null;
        optifineProgramIdentity = null;
    }

    void drainPendingShaderCandidates(int maximum) {
        if (runtime.shutdownRequested || !runtime.initialized || runtime.components.shaderPipeline == null
            || runtime.components.shaderInstaller == null
            || runtime.currentStamp != null || runtime.components.threadGuard == null
            || !runtime.components.threadGuard.isRenderThread() || shaderBindingsPoisoned) return;
        int limit = Math.max(0, maximum);
        for (int index = 0; index < limit; index++) {
            CapturedShaderSources captured;
            synchronized (this) {
                captured = pendingShaderCandidates.pollFirst();
                if (captured == null) return;
                pendingShaderCandidateBytes = Math.max(0L,
                    pendingShaderCandidateBytes - captured.bytes);
            }
            try {
            if (captured.resourceGeneration != runtime.epochs.currentResourceGeneration()
                || captured.shaderGeneration
                    != runtime.epochs.currentShaderPackGeneration()) continue;
            if (captured.programIdentity == null) {
                shaderDomainRejected(OptimizationModule.OPTIFINE_SHADER_COMPILE,
                    "captured Shader candidate has no OptiFine Program identity");
                continue;
            }
            int legacyProgramId;
            try {
                legacyProgramId = shaderIntrospector.programId(
                    captured.programIdentity);
            } catch (Throwable introspectionFailure) {
                shaderDomainFailure(OptimizationModule.OPTIFINE_SHADER_COMPILE,
                    introspectionFailure);
                continue;
            }
            if (legacyProgramId <= 0) {
                shaderDomainRejected(OptimizationModule.OPTIFINE_SHADER_COMPILE,
                    "captured OptiFine Program is not linked");
                continue;
            }
            if (!shaderBindings.containsKey(captured.programIdentity)
                && shaderBindings.size() >= MAX_SHADER_BINDINGS) {
                shaderDomainRejected(OptimizationModule.OPTIFINE_SHADER_COMPILE,
                    "Shader Program binding table is full");
                continue;
            }
            try {
                PreparedShaderPermutation prepared = runtime.components.shaderPipeline.prepareResolved(
                    captured.packId, captured.program, captured.permutation,
                    captured.resourceGeneration, captured.shaderGeneration,
                    captured.vertexPath, captured.vertexSource,
                    captured.geometryPath, captured.geometrySource,
                    captured.fragmentPath, captured.fragmentSource,
                    captured.propertiesSource);
                if (!compileShaderPermutation(prepared, legacyProgramId)) {
                    runtime.detail = "OptiFine Shader 候选编译/安装被拒绝；原程序保持有效";
                    continue;
                }
                int candidateProgram = runtime.components.shaderInstaller.installedProgram(
                    prepared.getKey(), runtime.epochs.currentResourceGeneration(),
                    runtime.epochs.currentGlContextGeneration(),
                    runtime.epochs.currentShaderPackGeneration());
                if (candidateProgram <= 0) {
                    runtime.components.shaders.recordCompile(prepared.getKey(), false,
                        "retained shader program disappeared after installation");
                    shaderDomainFailure(OptimizationModule.OPTIFINE_SHADER_COMPILE,
                        new IllegalStateException(
                            "retained shader program publication failed"));
                    continue;
                }
                ShaderProgramBinding binding = registerShaderBinding(
                    captured.programIdentity, legacyProgramId, candidateProgram,
                    prepared);
                if (binding != null && optifineProgramIdentity
                    == captured.programIdentity && optifineProgramState != null
                    && optifineProgramState.getProgramId() == legacyProgramId) {
                    queueShaderValidation(binding, optifineProgramState);
                }
            } catch (Throwable rejected) {
                FatalErrors.rethrowIfFatal(rejected);
                ModuleCircuitBreaker breaker = OptimizerRegistry.breaker(
                    OptimizationModule.OPTIFINE_SHADER_BRIDGE);
                if (breaker != null) breaker.recordRejected(
                    "resolved Shader candidate rejected: "
                        + rejected.getClass().getSimpleName());
                runtime.detail = "OptiFine Shader 候选输入被安全拒绝："
                    + rejected.getClass().getSimpleName();
            }
            } finally {
                captured.close();
            }
        }
    }

    void drainPendingShaderValidations(int maximum) {
        if (runtime.shutdownRequested || !runtime.initialized || runtime.currentStamp != null
            || runtime.components.shaderPipeline == null
            || runtime.components.shaderImageCertification == null || runtime.components.shaderInstaller == null
            || runtime.components.shaders == null || runtime.components.threadGuard == null
            || !runtime.components.threadGuard.isRenderThread() || shaderBindingsPoisoned) return;
        int limit = Math.max(0, maximum);
        for (int index = 0; index < limit; index++) {
            ShaderValidationRequest request = pendingShaderValidations.pollFirst();
            if (request == null) return;
            ShaderProgramBinding binding = request.binding;
            binding.validationQueued = false;
            if (!bindingCurrent(binding, binding.programIdentity,
                request.state.getProgramId()) || runtime.components.shaders.hasFailed(binding.key)) {
                binding.closePrepared();
                continue;
            }
            int candidateProgram = runtime.components.shaderInstaller.installedProgram(binding.key,
                runtime.epochs.currentResourceGeneration(),
                runtime.epochs.currentGlContextGeneration(),
                runtime.epochs.currentShaderPackGeneration());
            if (candidateProgram != binding.candidateProgram
                || candidateProgram <= 0) {
                removeShaderBinding(binding);
                continue;
            }

            boolean statePassed;
            try {
                statePassed = runtime.components.shaderPipeline.validateState(binding.key,
                    request.state, request.state.withProgramId(candidateProgram))
                    .isEquivalent();
            } catch (Throwable error) {
                binding.closePrepared();
                FatalErrors.rethrowIfFatal(error);
                runtime.components.shaders.recordStateValidation(binding.key, false);
                shaderDomainFailure(OptimizationModule.OPTIFINE_SHADER_STATE,
                    error);
                continue;
            }
            if (!statePassed) {
                shaderDomainRejected(OptimizationModule.OPTIFINE_SHADER_STATE,
                    "OptiFine Shader logical state mismatch");
                binding.closePrepared();
                continue;
            }
            shaderDomainSuccess(OptimizationModule.OPTIFINE_SHADER_STATE);

            try {
                LwjglShaderImageCertification.Result images =
                    runtime.components.shaderImageCertification.certify(binding.prepared,
                        binding.legacyProgram, candidateProgram, request.state);
                if (!images.wasExecuted() || !images.hasSignal()) {
                    runtime.components.shaders.recordImageValidation(binding.key, false);
                    if (images.isInfrastructureFailure()) {
                        shaderDomainFailure(OptimizationModule.OPTIFINE_SHADER_IMAGE,
                            new IllegalStateException(images.getDetail()));
                    } else {
                        shaderDomainRejected(
                            OptimizationModule.OPTIFINE_SHADER_IMAGE,
                            images.getDetail());
                    }
                    continue;
                }
                boolean equivalent = runtime.components.shaderPipeline.validateImages(binding.key,
                    images.getLegacyImages(), images.getCandidateImages(), 0);
                if (!equivalent) {
                    shaderDomainRejected(OptimizationModule.OPTIFINE_SHADER_IMAGE,
                        "OptiFine Shader scratch attachment A/B mismatch");
                    continue;
                }
                // Image equivalence is meaningful only for the exact logical
                // OptiFine pass/FBO layout that was exercised.  Publish that
                // immutable state only after a final generation/program
                // authentication; later activation and every terrain draw
                // must match it again.
                int retainedProgram = runtime.components.shaderInstaller.certifiedProgram(
                    binding.key, runtime.components.shaders,
                    runtime.epochs.currentResourceGeneration(),
                    runtime.epochs.currentGlContextGeneration(),
                    runtime.epochs.currentShaderPackGeneration());
                if (!bindingGenerationsCurrent(binding)
                    || retainedProgram != binding.candidateProgram) {
                    removeShaderBinding(binding);
                    continue;
                }
                binding.certifiedState = request.state;
                shaderDomainSuccess(OptimizationModule.OPTIFINE_SHADER_IMAGE);
                runtime.detail = "OptiFine Shader permutation 已通过编译、状态与图像认证";
            } catch (Throwable error) {
                runtime.components.shaders.recordImageValidation(binding.key, false);
                shaderDomainFailure(OptimizationModule.OPTIFINE_SHADER_IMAGE,
                    error);
            } finally {
                binding.closePrepared();
                EarlyGlStateTracker.invalidate();
                EarlyMatrixStateTracker.invalidate();
            }
        }
    }

    synchronized void clearPendingShaderCandidates() {
        Throwable failure = null;
        for (CapturedShaderSources captured : pendingShaderCandidates) {
            try { captured.close(); }
            catch (Throwable closeFailure) {
                failure = ModernRendererRuntime.appendRuntimeFailure(failure, closeFailure);
            }
        }
        try { pendingShaderCandidates.clear(); }
        catch (Throwable clearFailure) {
            failure = ModernRendererRuntime.appendRuntimeFailure(failure, clearFailure);
        }
        pendingShaderCandidateBytes = 0L;
        if (failure != null) ModernRendererRuntime.rethrowRuntimeFailure(failure);
    }

    private void purgeStaleShaderCandidates(long resources, long shaders) {
        Iterator<CapturedShaderSources> iterator =
            pendingShaderCandidates.iterator();
        while (iterator.hasNext()) {
            CapturedShaderSources captured = iterator.next();
            if (captured.resourceGeneration == resources
                && captured.shaderGeneration == shaders) continue;
            iterator.remove();
            captured.close();
            pendingShaderCandidateBytes = Math.max(0L,
                pendingShaderCandidateBytes - captured.bytes);
        }
    }

    synchronized int pendingShaderCandidateCountForTest() {
        return pendingShaderCandidates.size();
    }

    synchronized long pendingShaderCandidateBytesForTest() {
        return pendingShaderCandidateBytes;
    }

    int pendingShaderValidationCountForTest() {
        return pendingShaderValidations.size();
    }

    int shaderBindingCountForTest() {
        return shaderBindingsPoisoned ? 0 : shaderBindings.size();
    }

    boolean shaderBindingsPoisonedForTest() { return shaderBindingsPoisoned; }

    void shaderBindingPublicationFaultForTest(
        ModernRendererRuntime.ShaderBindingPublicationFault fault) {
        shaderBindingPublicationFault = fault;
    }

    private ShaderProgramBinding registerShaderBinding(Object programIdentity,
                                                       int legacyProgram,
                                                       int candidateProgram,
        PreparedShaderPermutation prepared) {
        if (programIdentity == null || legacyProgram <= 0 || candidateProgram <= 0
            || prepared == null || shaderBindingsPoisoned) return null;
        if (activeNativeShaderBinding != null) {
            throw new IllegalStateException(
                "cannot replace shader bindings while a candidate may be active");
        }
        ShaderProgramBinding current = shaderBindings.get(programIdentity);
        if (current != null && current.legacyProgram == legacyProgram
            && current.candidateProgram == candidateProgram
            && current.key.equals(prepared.getKey())
            && bindingGenerationsCurrent(current)) return current;
        long heapBytes;
        try { heapBytes = preparedShaderHeapBytes(prepared); }
        catch (RuntimeException invalid) {
            FatalErrors.rethrowIfFatal(invalid);
            if (runtime.components.shaders != null) {
                runtime.components.shaders.recordCompile(prepared.getKey(), false,
                    "prepared Shader retained-Heap accounting failed");
            }
            return null;
        }
        CacheBudget.Reservation reservation = runtime.budget.tryReserve(
            BudgetKind.HEAP, heapBytes);
        if (reservation == null) {
            if (runtime.components.shaders != null) {
                runtime.components.shaders.recordCompile(prepared.getKey(), false,
                    "prepared Shader Heap budget exhausted");
            }
            shaderDomainRejected(OptimizationModule.OPTIFINE_SHADER_COMPILE,
                "prepared Shader Heap budget exhausted");
            return null;
        }
        ShaderProgramBinding binding;
        try {
            ShaderTerrainLayoutCertification.Result terrainLayout =
                runtime.initialized && runtime.components.threadGuard != null && runtime.components.threadGuard.isRenderThread()
                    ? ShaderTerrainLayoutCertification.certify(candidateProgram,
                        prepared.getVertex().getSource())
                    : null;
            binding = new ShaderProgramBinding(programIdentity,
                legacyProgram, candidateProgram, prepared,
                runtime.epochs.currentResourceGeneration(),
                runtime.epochs.currentGlContextGeneration(),
                runtime.epochs.currentShaderPackGeneration(), reservation,
                terrainLayout != null && terrainLayout.isCertified(),
                terrainLayout == null ? "terrain layout not queried"
                    : terrainLayout.getDetail());
            reservation = null;
        } finally {
            if (reservation != null) reservation.close();
        }
        Integer legacyKey = Integer.valueOf(legacyProgram);
        boolean hadPrevious = shaderBindings.containsKey(programIdentity);
        ShaderProgramBinding previous = shaderBindings.get(programIdentity);
        boolean hadCollision = shaderBindingsById.containsKey(legacyKey);
        ShaderProgramBinding collision = shaderBindingsById.get(legacyKey);
        try {
            shaderBindings.put(programIdentity, binding);
            shaderBindingPublicationCheckpoint("after-identity");
            shaderBindingsById.put(legacyKey, binding);
            shaderBindingPublicationCheckpoint("after-id");
            if (shaderBindings.get(programIdentity) != binding
                || shaderBindingsById.get(legacyKey) != binding) {
                throw new IllegalStateException(
                    "Shader binding publication verification failed");
            }
        } catch (Throwable publicationFailure) {
            Throwable failure = publicationFailure;
            boolean rollbackCertain = true;
            try {
                shaderBindingPublicationCheckpoint("before-identity-rollback");
                restoreMapEntry(shaderBindings, programIdentity, hadPrevious,
                    previous);
            } catch (Throwable rollbackFailure) {
                rollbackCertain = false;
                failure = ModernRendererRuntime.appendRuntimeFailure(failure, rollbackFailure);
            }
            try {
                shaderBindingPublicationCheckpoint("before-id-rollback");
                restoreMapEntry(shaderBindingsById, legacyKey, hadCollision,
                    collision);
            } catch (Throwable rollbackFailure) {
                rollbackCertain = false;
                failure = ModernRendererRuntime.appendRuntimeFailure(failure, rollbackFailure);
            }
            if (rollbackCertain) {
                try { binding.closePrepared(); }
                catch (Throwable closeFailure) {
                    failure = ModernRendererRuntime.appendRuntimeFailure(failure, closeFailure);
                }
            } else {
                shaderBindingPublicationWitness = binding;
                failure = poisonShaderBindingTables(failure);
            }
            ModernRendererRuntime.rethrowRuntimeFailure(failure);
            return null;
        }
        if (previous != null && previous.legacyProgram != legacyProgram
            && shaderBindingsById.get(Integer.valueOf(previous.legacyProgram))
                == previous) {
            shaderBindingsById.remove(Integer.valueOf(previous.legacyProgram));
        }
        if (collision != null && collision != previous
            && shaderBindings.get(collision.programIdentity) == collision) {
            shaderBindings.remove(collision.programIdentity);
        }
        if (previous != null) previous.validationQueued = false;
        if (collision != null) collision.validationQueued = false;
        if (previous != null) previous.closePrepared();
        if (collision != null && collision != previous) collision.closePrepared();
        return binding;
    }

    private void shaderBindingPublicationCheckpoint(String point) {
        ModernRendererRuntime.ShaderBindingPublicationFault fault = shaderBindingPublicationFault;
        if (fault != null) fault.checkpoint(point);
    }

    private static <K, V> void restoreMapEntry(Map<K, V> map, K key,
                                                boolean present, V value) {
        if (present) map.put(key, value);
        else map.remove(key);
        if (present) {
            if (!map.containsKey(key) || map.get(key) != value) {
                throw new IllegalStateException(
                    "Shader binding rollback verification failed");
            }
        } else if (map.containsKey(key)) {
            throw new IllegalStateException(
                "Shader binding rollback left an unexpected entry");
        }
    }

    private Throwable poisonShaderBindingTables(Throwable failure) {
        shaderBindingsPoisoned = true;
        runtime.detail = "Shader binding publication outcome uncertain; native Shader path fused";
        try { clearPendingShaderCandidates(); }
        catch (Throwable cleanupFailure) {
            failure = ModernRendererRuntime.appendRuntimeFailure(failure, cleanupFailure);
        }
        try { cleanupShaderBindingTables(false); }
        catch (Throwable cleanupFailure) {
            failure = ModernRendererRuntime.appendRuntimeFailure(failure, cleanupFailure);
        }
        if (FatalErrors.findFatal(failure) == null) {
            try {
                shaderDomainFailure(OptimizationModule.OPTIFINE_SHADER_BRIDGE,
                    new IllegalStateException(
                        "Shader binding table publication was not atomic", failure));
            } catch (Throwable reportingFailure) {
                failure = ModernRendererRuntime.appendRuntimeFailure(failure, reportingFailure);
            }
        }
        return failure;
    }

    private boolean bindingCurrent(ShaderProgramBinding binding,
                                   Object programIdentity,
                                   int observedProgram) {
        if (shaderBindingsPoisoned || binding == null || programIdentity == null
            || binding.programIdentity != programIdentity
            || shaderBindings.get(programIdentity) != binding
            || shaderBindingsById.get(Integer.valueOf(binding.legacyProgram))
                != binding) {
            return false;
        }
        if (binding.resourceGeneration != runtime.epochs.currentResourceGeneration()
            || binding.contextGeneration != runtime.epochs.currentGlContextGeneration()
            || binding.shaderGeneration != runtime.epochs.currentShaderPackGeneration()) {
            removeShaderBinding(binding);
            return false;
        }
        if (observedProgram != binding.legacyProgram) {
            removeShaderBinding(binding);
            shaderDomainRejected(OptimizationModule.OPTIFINE_SHADER_STATE,
                "OptiFine Program GL name changed within one generation");
            return false;
        }
        try {
            if (shaderIntrospector.programId(programIdentity)
                == binding.legacyProgram) return true;
            removeShaderBinding(binding);
            shaderDomainRejected(OptimizationModule.OPTIFINE_SHADER_STATE,
                "OptiFine Program GL name changed within one generation");
        } catch (Throwable error) {
            Throwable failure = error;
            try { removeShaderBinding(binding); }
            catch (Throwable removalFailure) {
                failure = ModernRendererRuntime.appendRuntimeFailure(failure, removalFailure);
            }
            shaderDomainFailure(OptimizationModule.OPTIFINE_SHADER_STATE,
                failure);
        }
        return false;
    }

    private boolean bindingGenerationsCurrent(ShaderProgramBinding binding) {
        return !shaderBindingsPoisoned && binding != null
            && shaderBindings.get(binding.programIdentity) == binding
            && binding.resourceGeneration == runtime.epochs.currentResourceGeneration()
            && binding.contextGeneration == runtime.epochs.currentGlContextGeneration()
            && binding.shaderGeneration == runtime.epochs.currentShaderPackGeneration();
    }

    private void removeShaderBinding(ShaderProgramBinding binding) {
        if (binding == null) return;
        if (shaderBindingsPoisoned) {
            binding.validationQueued = false;
            binding.closePrepared();
            return;
        }
        try {
            if (shaderBindings.get(binding.programIdentity) == binding) {
                shaderBindings.remove(binding.programIdentity);
            }
            Integer legacyKey = Integer.valueOf(binding.legacyProgram);
            if (shaderBindingsById.get(legacyKey) == binding) {
                shaderBindingsById.remove(legacyKey);
            }
            if (shaderBindings.get(binding.programIdentity) == binding
                || shaderBindingsById.get(legacyKey) == binding) {
                throw new IllegalStateException(
                    "Shader binding removal verification failed");
            }
            binding.validationQueued = false;
            binding.closePrepared();
        } catch (Throwable removalFailure) {
            Throwable failure = poisonShaderBindingTables(removalFailure);
            ModernRendererRuntime.rethrowRuntimeFailure(failure);
        }
    }

    private void queueShaderValidation(ShaderProgramBinding binding,
                                       OptifineProgramState state) {
        if (binding == null || state == null || binding.validationQueued
            || binding.prepared == null
            || runtime.components.shaders == null || !runtime.components.shaders.compilePassed(binding.key)
            || runtime.components.shaders.isCertified(binding.key) || runtime.components.shaders.hasFailed(binding.key)
            || !OptimizerRegistry.isOperational(
                OptimizationModule.OPTIFINE_SHADER_STATE)
            || !OptimizerRegistry.isOperational(
                OptimizationModule.OPTIFINE_SHADER_IMAGE)) return;
        if (pendingShaderValidations.size() >= MAX_PENDING_SHADER_VALIDATIONS) {
            shaderDomainRejected(OptimizationModule.OPTIFINE_SHADER_IMAGE,
                "Shader image validation queue is full");
            return;
        }
        binding.validationQueued = true;
        try {
            pendingShaderValidations.addLast(
                new ShaderValidationRequest(binding, state));
        } catch (Throwable publicationFailure) {
            binding.validationQueued = false;
            FatalErrors.rethrowIfFatal(publicationFailure);
            throw publicationFailure;
        }
    }

    void promoteCertifiedShaderBackend() {
        AdaptiveBackendController backend = runtime.components.backends.get(
            OptimizationModule.OPTIFINE_SHADER_BRIDGE);
        if (shaderBindingsPoisoned || backend == null || backend.lifecycleState()
            != BackendLifecycleState.OUTPUT_VALIDATE || runtime.components.shaders == null) return;
        for (ShaderProgramBinding binding : shaderBindings.values()) {
            if (bindingGenerationsCurrent(binding)
                && runtime.components.shaders.isCertified(binding.key)) {
                backend.validationResult(true,
                    "至少一个当前代际 Shader permutation 已通过三门认证");
                return;
            }
        }
    }

    private void activateCertifiedShaderForInterval(
        ShaderProgramBinding binding, OptifineProgramState state) {
        if (runtime.shutdownRequested || binding == null || state == null
            || runtime.currentStamp == null
            || activeShaderSample != null || activeNativeShaderBinding != null
            || runtime.components.shaderActivation == null || runtime.components.shaderInstaller == null
            || runtime.components.shaders == null || !shaderDomainsOperational()
            || !runtime.components.shaders.isCertified(binding.key)) return;
        AdaptiveBackendController backend = runtime.components.backends.get(
            OptimizationModule.OPTIFINE_SHADER_BRIDGE);
        if (backend == null) return;
        BackendLifecycleState lifecycle = backend.lifecycleState();
        if (lifecycle != BackendLifecycleState.PAIRED_MEASURE
            && lifecycle != BackendLifecycleState.MODERN
            && lifecycle != BackendLifecycleState.REGRESSION_MONITOR) return;
        int candidateProgram = runtime.components.shaderInstaller.certifiedProgram(binding.key,
            runtime.components.shaders, runtime.epochs.currentResourceGeneration(),
            runtime.epochs.currentGlContextGeneration(),
            runtime.epochs.currentShaderPackGeneration());
        if (candidateProgram != binding.candidateProgram
            || state.getProgramId() != binding.legacyProgram
            || binding.certifiedState == null
            || !binding.certifiedState.isLogicalActivationEquivalent(state)) return;

        RenderBackendSample sample = runtime.measurements.beginRenderBackendSample(
            OptimizationModule.OPTIFINE_SHADER_BRIDGE, RenderPass.FINAL,
            0x40000000 | (binding.key.hashCode() & 0x3FFFFFFF));
        if (sample == null) return;
        activeShaderSample = sample;
        activeShaderSampleBinding = binding;
        if (sample.arm != MeasurementArm.MODERN) return;

        LwjglOptifineShaderActivation.Result result = runtime.components.shaderActivation.switchProgram(
            binding.programIdentity, binding.legacyProgram, candidateProgram);
        if (result.isSwitched()) {
            activeNativeShaderBinding = binding;
            optifineProgramState = state.withProgramId(candidateProgram);
            EarlyGlStateTracker.useProgram(candidateProgram);
            shaderDomainSuccess(OptimizationModule.OPTIFINE_SHADER_ACTIVATION);
            return;
        }
        handleShaderActivationFailure(result);
        if (!result.isRollbackSucceeded()) activeNativeShaderBinding = binding;
        runtime.measurements.endRenderBackendSample(sample, false, false);
        activeShaderSample = null;
        activeShaderSampleBinding = null;
    }

    void finishActiveShaderInterval(boolean stable,
                                            boolean restoreOriginal) {
        ShaderProgramBinding nativeBinding = activeNativeShaderBinding;
        ShaderProgramBinding sampledBinding = activeShaderSampleBinding;
        boolean modernWork = nativeBinding != null
            && nativeBinding == sampledBinding;
        boolean restored = true;
        if (restoreOriginal && nativeBinding != null) {
            if (runtime.components.shaderActivation == null) {
                restored = false;
                shaderDomainFailure(OptimizationModule.OPTIFINE_SHADER_ACTIVATION,
                    new IllegalStateException("shader activation component unavailable"));
            } else {
                LwjglOptifineShaderActivation.Result result =
                    runtime.components.shaderActivation.switchProgram(nativeBinding.programIdentity,
                        nativeBinding.candidateProgram,
                        nativeBinding.legacyProgram);
                restored = result.isSwitched();
                if (restored) {
                    activeNativeShaderBinding = null;
                    if (optifineProgramIdentity == nativeBinding.programIdentity
                        && optifineProgramState != null) {
                        optifineProgramState = optifineProgramState.withProgramId(
                            nativeBinding.legacyProgram);
                    }
                    EarlyGlStateTracker.useProgram(nativeBinding.legacyProgram);
                    shaderDomainSuccess(
                        OptimizationModule.OPTIFINE_SHADER_ACTIVATION);
                } else {
                    handleShaderActivationFailure(result);
                }
            }
        }
        RenderBackendSample sample = activeShaderSample;
        activeShaderSample = null;
        activeShaderSampleBinding = null;
        if (sample != null) {
            runtime.measurements.endRenderBackendSample(sample, stable && restored, modernWork);
        }
        if (restoreOriginal && nativeBinding != null) {
            EarlyGlStateTracker.invalidate();
            EarlyMatrixStateTracker.invalidate();
        }
    }

    private void handleShaderActivationFailure(
        LwjglOptifineShaderActivation.Result result) {
        String failure = result == null ? "shader activation returned null"
            : result.getDetail();
        if (result == null || result.isInfrastructureFailure()
            || !result.isRollbackSucceeded()) {
            IllegalStateException error = new IllegalStateException(failure);
            shaderDomainFailure(OptimizationModule.OPTIFINE_SHADER_ACTIVATION,
                error);
            AdaptiveBackendController backend = runtime.components.backends.get(
                OptimizationModule.OPTIFINE_SHADER_BRIDGE);
            if (backend != null) backend.runtimeFailure(error);
        } else {
            shaderDomainRejected(OptimizationModule.OPTIFINE_SHADER_ACTIVATION,
                failure);
        }
        runtime.detail = "OptiFine Shader candidate 激活被安全回退：" + failure;
        EarlyGlStateTracker.invalidate();
        EarlyMatrixStateTracker.invalidate();
    }

    void abandonActiveShaderInterval() {
        activeShaderSample = null;
        activeShaderSampleBinding = null;
        activeNativeShaderBinding = null;
    }

    void clearShaderBindings() {
        cleanupShaderBindingTables(true);
    }

    private void cleanupShaderBindingTables(boolean resetFuse) {
        if (activeNativeShaderBinding != null) {
            throw new IllegalStateException(
                "cannot clear bindings while a native shader may be active");
        }
        shaderBindingsPoisoned = true;
        ShaderProgramBinding[] detached = snapshotShaderBindings();
        shaderBindingCleanupWitness = detached;
        Throwable failure = null;
        try { pendingShaderValidations.clear(); }
        catch (Throwable cleanupFailure) {
            failure = ModernRendererRuntime.appendRuntimeFailure(failure, cleanupFailure);
        }
        try { shaderBindings.clear(); }
        catch (Throwable cleanupFailure) {
            failure = ModernRendererRuntime.appendRuntimeFailure(failure, cleanupFailure);
        }
        try { shaderBindingsById.clear(); }
        catch (Throwable cleanupFailure) {
            failure = ModernRendererRuntime.appendRuntimeFailure(failure, cleanupFailure);
        }
        if (!shaderBindings.isEmpty() || !shaderBindingsById.isEmpty()
            || !pendingShaderValidations.isEmpty()) {
            failure = ModernRendererRuntime.appendRuntimeFailure(failure,
                new IllegalStateException(
                    "Shader binding cleanup did not detach every owner"));
        }
        if (failure == null) {
            shaderBindingPublicationWitness = null;
            for (ShaderProgramBinding binding : detached) {
                if (binding == null) continue;
                try {
                    binding.validationQueued = false;
                    binding.closePrepared();
                } catch (Throwable cleanupFailure) {
                    failure = ModernRendererRuntime.appendRuntimeFailure(failure, cleanupFailure);
                }
            }
        }
        abandonActiveShaderInterval();
        optifineProgramIdentity = null;
        if (failure != null) {
            shaderBindingsPoisoned = true;
            ModernRendererRuntime.rethrowRuntimeFailure(failure);
        }
        shaderBindingCleanupWitness = null;
        if (resetFuse) shaderBindingsPoisoned = false;
    }

    private ShaderProgramBinding[] snapshotShaderBindings() {
        int capacity = Math.addExact(shaderBindings.size(),
            shaderBindingsById.size());
        capacity = Math.addExact(capacity,
            pendingShaderValidations.size());
        if (shaderBindingPublicationWitness != null) capacity++;
        if (shaderBindingCleanupWitness != null) {
            capacity = Math.addExact(capacity,
                shaderBindingCleanupWitness.length);
        }
        ShaderProgramBinding[] values = new ShaderProgramBinding[capacity];
        int count = 0;
        if (shaderBindingCleanupWitness != null) {
            for (ShaderProgramBinding binding : shaderBindingCleanupWitness) {
                count = addUniqueBinding(values, count, binding);
            }
        }
        count = addUniqueBinding(values, count,
            shaderBindingPublicationWitness);
        for (ShaderProgramBinding binding : shaderBindings.values()) {
            count = addUniqueBinding(values, count, binding);
        }
        for (ShaderProgramBinding binding : shaderBindingsById.values()) {
            count = addUniqueBinding(values, count, binding);
        }
        for (ShaderValidationRequest request : pendingShaderValidations) {
            count = addUniqueBinding(values, count,
                request == null ? null : request.binding);
        }
        return values;
    }

    private static int addUniqueBinding(ShaderProgramBinding[] values,
                                        int count,
                                        ShaderProgramBinding binding) {
        if (binding == null) return count;
        for (int index = 0; index < count; index++) {
            if (values[index] == binding) return count;
        }
        values[count] = binding;
        return count + 1;
    }

    private boolean shaderDomainsOperational() {
        return OptimizerRegistry.isOperational(
            OptimizationModule.OPTIFINE_SHADER_COMPILE)
            && OptimizerRegistry.isOperational(
                OptimizationModule.OPTIFINE_SHADER_STATE)
            && OptimizerRegistry.isOperational(
                OptimizationModule.OPTIFINE_SHADER_IMAGE)
            && OptimizerRegistry.isOperational(
                OptimizationModule.OPTIFINE_SHADER_ACTIVATION);
    }

    private static void shaderDomainSuccess(OptimizationModule module) {
        ModuleCircuitBreaker breaker = OptimizerRegistry.breaker(module);
        if (breaker != null) breaker.recordSuccess();
    }

    private static void shaderDomainRejected(OptimizationModule module,
                                             String reason) {
        ModuleCircuitBreaker breaker = OptimizerRegistry.breaker(module);
        if (breaker != null) breaker.recordRejected(reason);
    }

    private static void shaderDomainFailure(OptimizationModule module,
                                            Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        ModuleCircuitBreaker breaker = OptimizerRegistry.breaker(module);
        if (breaker != null) breaker.recordFailure(error);
    }

    private static long capturedShaderBytes(String vertex, String geometry,
                                            String fragment,
                                            String properties) {
        if (vertex == null || fragment == null || vertex.indexOf('\0') >= 0
            || fragment.indexOf('\0') >= 0
            || geometry != null && geometry.indexOf('\0') >= 0
            || properties != null && properties.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("captured Shader source");
        }
        long bytes = utf8Bytes(vertex);
        bytes = checkedCapturedAdd(bytes, utf8Bytes(fragment));
        if (geometry != null) bytes = checkedCapturedAdd(bytes,
            utf8Bytes(geometry));
        if (properties != null) bytes = checkedCapturedAdd(bytes,
            utf8Bytes(properties));
        if (bytes <= 0L || bytes > 8L * 1024L * 1024L) {
            throw new IllegalArgumentException("captured Shader byte limit");
        }
        return bytes;
    }

    private static long utf8Bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static long checkedCapturedAdd(long left, long right) {
        if (right > Long.MAX_VALUE - left) {
            throw new IllegalArgumentException("captured Shader size overflow");
        }
        return left + right;
    }

    private static long capturedShaderHeapBytes(String... values) {
        long bytes = 0L;
        for (String value : values) {
            if (value == null) continue;
            bytes = Math.addExact(bytes, shaderStringHeapBytes(value));
        }
        return Math.max(1L, bytes);
    }

    private static long preparedShaderHeapBytes(
        PreparedShaderPermutation prepared) {
        if (prepared == null) throw new IllegalArgumentException(
            "prepared Shader");
        long bytes = 256L;
        dev.rlcraft.ice.optimizer.render.optifine.PreprocessedShader[] stages = {
            prepared.getVertex(), prepared.getGeometry(), prepared.getFragment()
        };
        for (dev.rlcraft.ice.optimizer.render.optifine.PreprocessedShader stage
            : stages) {
            if (stage == null) continue;
            bytes = Math.addExact(bytes,
                shaderStringHeapBytes(stage.getSource()));
            bytes = Math.addExact(bytes,
                dev.rlcraft.ice.optimizer.memory.RetainedHeap.referenceArray(
                    stage.getDependencies().size()));
            for (String dependency : stage.getDependencies()) {
                bytes = Math.addExact(bytes,
                    shaderStringHeapBytes(dependency));
            }
        }
        bytes = Math.addExact(bytes, Math.multiplyExact(64L,
            (long) prepared.getProperties().size()));
        for (Map.Entry<String, String> entry
            : prepared.getProperties().asMap().entrySet()) {
            bytes = Math.addExact(bytes,
                shaderStringHeapBytes(entry.getKey()));
            bytes = Math.addExact(bytes,
                shaderStringHeapBytes(entry.getValue()));
        }
        return bytes;
    }

    private static long shaderStringHeapBytes(String value) {
        if (value == null) return 0L;
        long characters = Math.multiplyExact((long) value.length(), 2L);
        // Charge retained char[] payload/header and a conservative String/
        // reference envelope, independent of Compact Strings.
        return Math.addExact(48L, characters);
    }

    private static final class CapturedShaderSources implements AutoCloseable {
        private final Object programIdentity;
        private final String packId;
        private final String program;
        private final String permutation;
        private final long resourceGeneration;
        private final long shaderGeneration;
        private final String vertexPath;
        private final String vertexSource;
        private final String geometryPath;
        private final String geometrySource;
        private final String fragmentPath;
        private final String fragmentSource;
        private final String propertiesSource;
        private final long bytes;
        private CacheBudget.Reservation heapReservation;

        private CapturedShaderSources(Object programIdentity, String packId,
                                      String program,
                                      String permutation,
                                      long resourceGeneration,
                                      long shaderGeneration,
                                      String vertexPath, String vertexSource,
                                      String geometryPath, String geometrySource,
                                      String fragmentPath, String fragmentSource,
                                      String propertiesSource, long bytes,
                                      CacheBudget.Reservation heapReservation) {
            if (!boundedCapture(packId, 256) || !boundedCapture(program, 256)
                || !boundedCapture(permutation, 1024)
                || !boundedCapture(vertexPath, 1024)
                || !boundedCapture(fragmentPath, 1024)
                || geometrySource != null && !boundedCapture(geometryPath, 1024)
                || resourceGeneration <= 0L || shaderGeneration <= 0L
                || bytes <= 0L || heapReservation == null) {
                throw new IllegalArgumentException("captured Shader metadata");
            }
            this.programIdentity = programIdentity;
            this.packId = packId;
            this.program = program;
            this.permutation = permutation;
            this.resourceGeneration = resourceGeneration;
            this.shaderGeneration = shaderGeneration;
            this.vertexPath = vertexPath;
            this.vertexSource = vertexSource;
            this.geometryPath = geometryPath;
            this.geometrySource = geometrySource;
            this.fragmentPath = fragmentPath;
            this.fragmentSource = fragmentSource;
            this.propertiesSource = propertiesSource == null ? "" : propertiesSource;
            this.bytes = bytes;
            this.heapReservation = heapReservation;
        }

        @Override public void close() {
            CacheBudget.Reservation reservation = heapReservation;
            heapReservation = null;
            if (reservation != null) reservation.close();
        }

        private static boolean boundedCapture(String value, int maximum) {
            return value != null && !value.isEmpty() && value.length() <= maximum
                && value.indexOf('\0') < 0;
        }
    }

    private static final class ShaderValidationRequest {
        private final ShaderProgramBinding binding;
        private final OptifineProgramState state;
        private ShaderValidationRequest(ShaderProgramBinding binding,
                                        OptifineProgramState state) {
            if (binding == null || state == null) {
                throw new IllegalArgumentException("Shader validation request");
            }
            this.binding = binding;
            this.state = state;
        }
    }

    private static final class ShaderProgramBinding {
        private final Object programIdentity;
        private final int legacyProgram;
        private final int candidateProgram;
        private PreparedShaderPermutation prepared;
        private CacheBudget.Reservation preparedReservation;
        private final ShaderPermutationKey key;
        private final long resourceGeneration;
        private final long contextGeneration;
        private final long shaderGeneration;
        private final boolean terrainLayoutCertified;
        private final String terrainLayoutDetail;
        private OptifineProgramState certifiedState;
        private boolean validationQueued;

        private ShaderProgramBinding(Object programIdentity, int legacyProgram,
                                     int candidateProgram,
                                     PreparedShaderPermutation prepared,
                                     long resourceGeneration,
                                     long contextGeneration,
                                     long shaderGeneration,
                                     CacheBudget.Reservation reservation,
                                     boolean terrainLayoutCertified,
                                     String terrainLayoutDetail) {
            if (programIdentity == null || legacyProgram <= 0
                || candidateProgram <= 0 || prepared == null
                || resourceGeneration <= 0L || contextGeneration <= 0L
                || shaderGeneration <= 0L || reservation == null) {
                throw new IllegalArgumentException("Shader Program binding");
            }
            this.programIdentity = programIdentity;
            this.legacyProgram = legacyProgram;
            this.candidateProgram = candidateProgram;
            this.prepared = prepared;
            this.preparedReservation = reservation;
            this.key = prepared.getKey();
            this.resourceGeneration = resourceGeneration;
            this.contextGeneration = contextGeneration;
            this.shaderGeneration = shaderGeneration;
            this.terrainLayoutCertified = terrainLayoutCertified;
            this.terrainLayoutDetail = terrainLayoutDetail == null ? ""
                : terrainLayoutDetail;
        }

        private void closePrepared() {
            prepared = null;
            CacheBudget.Reservation reservation = preparedReservation;
            preparedReservation = null;
            if (reservation != null) reservation.close();
        }
    }

    boolean hasNativeBinding() { return activeNativeShaderBinding != null; }
    boolean nativeTerrainLayoutCertified() {
        return activeNativeShaderBinding != null && activeNativeShaderBinding.terrainLayoutCertified;
    }
    String nativeTerrainLayoutDetail() {
        return activeNativeShaderBinding == null ? "" : activeNativeShaderBinding.terrainLayoutDetail;
    }
    boolean hasPendingWork() {
        return !pendingShaderCandidates.isEmpty() || !pendingShaderValidations.isEmpty();
    }
}
