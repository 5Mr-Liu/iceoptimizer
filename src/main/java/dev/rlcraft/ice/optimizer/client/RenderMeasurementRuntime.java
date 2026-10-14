package dev.rlcraft.ice.optimizer.client;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.render.backend.AdaptiveBackendController;
import dev.rlcraft.ice.optimizer.render.backend.BackendLifecycleState;
import dev.rlcraft.ice.optimizer.render.backend.MeasurementArm;
import dev.rlcraft.ice.optimizer.render.backend.RenderBackendId;
import dev.rlcraft.ice.optimizer.render.backend.SceneFingerprint;
import dev.rlcraft.ice.optimizer.render.frame.RenderPass;
import dev.rlcraft.ice.optimizer.render.telemetry.CorrelatedRenderProfiler;
import dev.rlcraft.ice.optimizer.render.telemetry.RenderProfileKey;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;

import dev.rlcraft.ice.optimizer.client.ModernRendererRuntime.RenderBackendSample;

/** Owns CPU/GPU sample correlation and backend validation progress. */
final class RenderMeasurementRuntime {
    private final ModernRendererRuntime runtime;

    RenderMeasurementRuntime(ModernRendererRuntime runtime) {
        this.runtime = runtime;
    }

    CorrelatedRenderProfiler.GpuScope frameGpuScope;

    SceneFingerprint pendingTerrainMeasurementScene;

    long pendingTerrainMeasurementStarted;

    boolean pendingTerrainMeasurementStable;

    final LinkedHashMap<RenderProfileKey, HzbGpuSample> hzbGpuSamples =
        new LinkedHashMap<RenderProfileKey, HzbGpuSample>();

    final LinkedHashMap<RenderProfileKey, BackendGpuSample> backendGpuSamples =
        new LinkedHashMap<RenderProfileKey, BackendGpuSample>();

    final EnumMap<OptimizationModule, Long> lastValidationFrames =
        new EnumMap<OptimizationModule, Long>(OptimizationModule.class);

    final CorrelatedRenderProfiler.GpuCompletion hzbGpuCompletion =
        new CorrelatedRenderProfiler.GpuCompletion() {
            @Override public void completed(RenderProfileKey key, long elapsedNanos) {
                completeHzbGpuSample(key, elapsedNanos);
            }
        };

    final CorrelatedRenderProfiler.GpuCompletion backendGpuCompletion =
        new CorrelatedRenderProfiler.GpuCompletion() {
            @Override public void completed(RenderProfileKey key, long elapsedNanos) {
                completeBackendGpuSample(key, elapsedNanos);
            }
        };

    void recordValidation(OptimizationModule module, boolean equivalent,
                                 String detail) {
        AdaptiveBackendController backend = runtime.components.backends.get(module);
        if (backend != null && backend.lifecycleState()
            == BackendLifecycleState.OUTPUT_VALIDATE) {
            backend.validationResult(equivalent, detail);
        }
    }

    MeasurementArm expectedMeasurementArm(OptimizationModule module) {
        AdaptiveBackendController backend = runtime.components.backends.get(module);
        return backend == null ? MeasurementArm.LEGACY : backend.expectedMeasurementArm();
    }

    void recordMeasurement(OptimizationModule module, SceneFingerprint scene,
                                  MeasurementArm arm, long nanos, boolean stable) {
        AdaptiveBackendController backend = runtime.components.backends.get(module);
        if (backend != null && backend.lifecycleState()
            == BackendLifecycleState.PAIRED_MEASURE) {
            backend.recordMeasurement(scene, arm, nanos, stable);
        }
    }

    void recordAuxiliaryBackendSample(OptimizationModule module,
                                             BackendLifecycleState sampledState,
                                             MeasurementArm arm,
                                             SceneFingerprint scene,
                                             long nanos, boolean stable) {
        AdaptiveBackendController backend = runtime.components.backends.get(module);
        if (backend == null || sampledState == null || arm == null) return;
        BackendLifecycleState current = backend.lifecycleState();
        try {
            if (sampledState == BackendLifecycleState.PAIRED_MEASURE
                && current == BackendLifecycleState.PAIRED_MEASURE) {
                backend.recordMeasurement(scene, arm, nanos, stable);
            } else if (sampledState == BackendLifecycleState.REGRESSION_MONITOR
                && current == BackendLifecycleState.REGRESSION_MONITOR
                && arm == MeasurementArm.MODERN) {
                backend.recordRegressionSample(scene, nanos, stable);
            }
        } catch (Throwable error) {
            backend.runtimeFailure(error);
        }
    }

    RenderBackendSample beginRenderBackendSample(OptimizationModule module,
                                                        RenderPass pass) {
        return beginRenderBackendSample(module, pass, 0);
    }

    RenderBackendSample beginRenderBackendSample(
        OptimizationModule module, RenderPass pass, int workloadDiscriminator) {
        if (!runtime.initialized || runtime.currentStamp == null || module == null || pass == null
            || runtime.insideLegacyIsland()
            || runtime.components.threadGuard == null || !runtime.components.threadGuard.isRenderThread()) return null;
        if ((module == OptimizationModule.MODERN_PARTICLE_BACKEND
            || module == OptimizationModule.FBP_PARTICLE_ADAPTER)
            && !runtime.shaderRuntime.isShaderPackSafeForNativeVertexFormats()) return null;
        AdaptiveBackendController backend = runtime.components.backends.get(module);
        if (backend == null) return null;
        boolean profiledModelWorkload = !backend.isDirectActivation()
            && (module == OptimizationModule.MODERN_ENTITY_BACKEND
                || module == OptimizationModule.MODERN_TESR_BACKEND);
        if (!profiledModelWorkload && !backend.shouldInspectCandidate()) {
            return null;
        }
        SceneFingerprint scene = profiledModelWorkload
            || backend.shouldMeasureFrame(runtime.currentStamp.getFrameId())
                ? sceneFingerprint(workloadDiscriminator) : null;
        if (scene != null && profiledModelWorkload) {
            int bucket = module == OptimizationModule.MODERN_ENTITY_BACKEND
                ? scene.entityLoadBucket() : scene.tileEntityLoadBucket();
            if (!backend.prepareProfiledWorkload(bucket,
                runtime.currentStamp.getFrameId())) return null;
        }
        if (!backend.shouldInspectCandidate()) return null;
        BackendLifecycleState state = backend.lifecycleState();
        MeasurementArm arm = state == BackendLifecycleState.PAIRED_MEASURE
            ? backend.expectedMeasurementArm()
            : (state == BackendLifecycleState.MODERN
                || state == BackendLifecycleState.REGRESSION_MONITOR
                    ? MeasurementArm.MODERN : MeasurementArm.LEGACY);
        RenderProfileKey key = null;
        CorrelatedRenderProfiler.GpuScope gpu = null;
        if (backend.shouldMeasureFrame(runtime.currentStamp.getFrameId()) && scene != null) {
            RenderBackendId id = arm == MeasurementArm.MODERN
                ? RenderBackendId.ICE_NATIVE : RenderBackendId.LEGACY;
            key = new RenderProfileKey(runtime.currentStamp, pass, id);
            if (!backendGpuSamples.containsKey(key)) {
                backendGpuSamples.put(key, new BackendGpuSample(module, scene, arm));
                while (backendGpuSamples.size() > 64) {
                    Iterator<RenderProfileKey> iterator = backendGpuSamples.keySet().iterator();
                    iterator.next();
                    iterator.remove();
                }
                gpu = runtime.components.profiler.beginGpu(runtime.currentStamp, pass, id, backendGpuCompletion);
                if (gpu == null) backendGpuSamples.remove(key);
            } else {
                key = null;
            }
        }
        return new RenderBackendSample(module, state, arm, scene, key, gpu,
            key == null ? 0L : System.nanoTime(), runtime.currentStamp.getFrameId());
    }

    void endRenderBackendSample(RenderBackendSample sample,
                                       boolean stable, boolean modernWork) {
        if (sample == null) return;
        boolean emptyModernArm = sample.state
            == BackendLifecycleState.PAIRED_MEASURE
            && sample.arm == MeasurementArm.MODERN && stable && !modernWork;
        if (sample.state == BackendLifecycleState.PAIRED_MEASURE
            && sample.arm == MeasurementArm.MODERN) {
            AdaptiveBackendController backend = runtime.components.backends.get(sample.module);
            if (backend != null) {
                if (modernWork) backend.recordModernWork();
                else if (emptyModernArm) backend.recordEmptyModernArm(sample.arm, sample.frameId);
            }
        }
        if (sample.gpuScope != null) sample.gpuScope.close();
        if (sample.key == null) return;
        BackendGpuSample pending = backendGpuSamples.get(sample.key);
        if (pending == null) return;
        pending.cpuNanos = Math.max(1L, System.nanoTime() - sample.startedNanos);
        pending.cpuReady = true;
        pending.stable = stable && (sample.arm == MeasurementArm.LEGACY || modernWork);
        pending.emptyModernArm = emptyModernArm;
        finishBackendGpuSample(sample.key, pending);
    }

    void recordValidationOnce(OptimizationModule module, boolean equivalent,
                                      String failure) {
        if (!equivalent) {
            recordValidation(module, false, failure);
            return;
        }
        long frame = runtime.currentStamp == null ? -1L : runtime.currentStamp.getFrameId();
        Long previous = lastValidationFrames.get(module);
        if (previous != null && previous.longValue() == frame) return;
        lastValidationFrames.put(module, Long.valueOf(frame));
        recordValidation(module, true, null);
    }

    void finishReusedHzbGpuSample(MeasurementArm arm, long started) {
        RenderProfileKey key = findCurrentHzbKey(arm);
        if (key == null) return;
        try {
            closeHzbGpuScope(key);
            addHzbCpuNanos(key, Math.max(1L, System.nanoTime() - started));
        } catch (Throwable error) {
            invalidateHzbGpuSample(key);
            runtime.reportInstrumentationFailure(null, error);
        }
    }

    RenderProfileKey beginHzbGpuSample(SceneFingerprint scene,
                                               MeasurementArm arm) {
        RenderBackendId backend = arm == MeasurementArm.MODERN
            ? RenderBackendId.ICE_NATIVE : RenderBackendId.LEGACY;
        RenderProfileKey key = new RenderProfileKey(runtime.currentStamp,
            RenderPass.MAIN_SOLID, backend);
        hzbGpuSamples.put(key, new HzbGpuSample(scene, arm));
        while (hzbGpuSamples.size() > 64) {
            Iterator<RenderProfileKey> iterator = hzbGpuSamples.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
        return key;
    }

    RenderProfileKey findCurrentHzbKey(MeasurementArm arm) {
        if (runtime.currentStamp == null) return null;
        RenderProfileKey key = new RenderProfileKey(runtime.currentStamp,
            RenderPass.MAIN_SOLID, arm == MeasurementArm.MODERN
                ? RenderBackendId.ICE_NATIVE : RenderBackendId.LEGACY);
        return hzbGpuSamples.containsKey(key) ? key : null;
    }

    void invalidateCurrentHzbGpuSample(MeasurementArm arm) {
        invalidateHzbGpuSample(findCurrentHzbKey(arm));
    }

    void invalidateHzbGpuSample(RenderProfileKey key) {
        if (key == null) return;
        HzbGpuSample sample = hzbGpuSamples.remove(key);
        if (sample != null) sample.closeGpuScope();
    }

    void attachHzbGpuScope(RenderProfileKey key,
                                   CorrelatedRenderProfiler.GpuScope scope) {
        HzbGpuSample sample = key == null ? null : hzbGpuSamples.get(key);
        if (sample == null) {
            if (scope != null) scope.close();
            return;
        }
        sample.gpuScope = scope;
    }

    void closeHzbGpuScope(RenderProfileKey key) {
        HzbGpuSample sample = key == null ? null : hzbGpuSamples.get(key);
        if (sample != null) sample.closeGpuScope();
    }

    void discardOpenHzbGpuSamples(long frameId) {
        if (hzbGpuSamples.isEmpty()) return;
        Iterator<Map.Entry<RenderProfileKey, HzbGpuSample>> iterator =
            hzbGpuSamples.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<RenderProfileKey, HzbGpuSample> entry = iterator.next();
            if (entry.getKey().getStamp().getFrameId() != frameId
                || entry.getValue().gpuScope == null) continue;
            HzbGpuSample sample = entry.getValue();
            iterator.remove();
            sample.closeGpuScope();
        }
    }

    void clearHzbGpuSamples() {
        if (hzbGpuSamples.isEmpty()) return;
        for (HzbGpuSample sample : hzbGpuSamples.values()) {
            sample.closeGpuScope();
        }
        hzbGpuSamples.clear();
    }

    void addHzbCpuNanos(RenderProfileKey key, long nanos) {
        HzbGpuSample sample = hzbGpuSamples.get(key);
        if (sample == null) return;
        sample.cpuNanos = safeAdd(sample.cpuNanos, nanos);
        sample.cpuReady = true;
        finishHzbGpuSample(key, sample);
    }

    void completeHzbGpuSample(RenderProfileKey key, long elapsedNanos) {
        HzbGpuSample sample = hzbGpuSamples.get(key);
        if (sample == null) return;
        sample.gpuNanos = safeAdd(sample.gpuNanos, Math.max(0L, elapsedNanos));
        sample.completedGpuScopes++;
        finishHzbGpuSample(key, sample);
    }

    /** Cold publication boundary: old GPU completions cannot train a resumed run. */
    void discardBackendSamples(OptimizationModule module) {
        Iterator<BackendGpuSample> iterator = backendGpuSamples.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().module == module) iterator.remove();
        }
    }

    void completeBackendGpuSample(RenderProfileKey key, long elapsedNanos) {
        BackendGpuSample sample = backendGpuSamples.get(key);
        if (sample == null) return;
        sample.gpuNanos = Math.max(0L, elapsedNanos);
        sample.gpuReady = true;
        finishBackendGpuSample(key, sample);
    }

    void finishBackendGpuSample(RenderProfileKey key, BackendGpuSample sample) {
        if (!sample.cpuReady || !sample.gpuReady) return;
        backendGpuSamples.remove(key);
        AdaptiveBackendController backend = runtime.components.backends.get(sample.module);
        if (backend == null) return;
        long pipeline = Math.max(1L, Math.max(sample.cpuNanos, sample.gpuNanos));
        if (backend.lifecycleState() == BackendLifecycleState.PAIRED_MEASURE
            && !sample.emptyModernArm) {
            backend.recordMeasurement(sample.scene, sample.arm, pipeline, sample.stable);
        } else if (backend.lifecycleState() == BackendLifecycleState.REGRESSION_MONITOR
            && sample.arm == MeasurementArm.MODERN) {
            backend.recordRegressionSample(sample.scene, pipeline, sample.stable);
        }
    }

    void finishHzbGpuSample(RenderProfileKey key, HzbGpuSample sample) {
        if (!sample.cpuReady || sample.completedGpuScopes < 1) return;
        hzbGpuSamples.remove(key);
        long pipelineNanos = Math.max(1L, Math.max(sample.cpuNanos, sample.gpuNanos));
        AdaptiveBackendController visibility = runtime.components.backends.get(
            OptimizationModule.MODERN_VISIBILITY_HZB);
        if (visibility == null) return;
        if (visibility.lifecycleState() == BackendLifecycleState.PAIRED_MEASURE) {
            visibility.recordMeasurement(sample.scene, sample.arm, pipelineNanos, true);
        } else if (visibility.lifecycleState()
            == BackendLifecycleState.REGRESSION_MONITOR
            && sample.arm == MeasurementArm.MODERN) {
            visibility.recordRegressionSample(sample.scene, pipelineNanos, true);
        }
    }

    boolean requiresWholeFrameGpuSample(long frameId) {
        if (frameId <= 0L || frameId % WHOLE_FRAME_GPU_SAMPLE_INTERVAL != 0L) {
            return false;
        }
        for (AdaptiveBackendController backend : runtime.components.backends.values()) {
            BackendLifecycleState state = backend.lifecycleState();
            if (state == BackendLifecycleState.PAIRED_MEASURE
                || state == BackendLifecycleState.REGRESSION_MONITOR) return true;
        }
        return false;
    }

    SceneFingerprint sceneFingerprint(int visibleSections) {
        return sceneFingerprint(visibleSections, -1, -1);
    }

    SceneFingerprint sceneFingerprint(int visibleSections,
                                              int terrainArenaOwned,
                                              int terrainRegionRuns) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || minecraft.world == null
            || minecraft.getRenderViewEntity() == null || runtime.currentStamp == null) return null;
        Entity camera = minecraft.getRenderViewEntity();
        int weather = (minecraft.world.isRaining() ? 1 : 0)
            | (minecraft.world.isThundering() ? 2 : 0);
        return new SceneFingerprint(minecraft.world.provider.getDimension(),
            floorRegion(camera.posX), floorRegion(camera.posY), floorRegion(camera.posZ),
            angleBucket(camera.rotationYaw), angleBucket(camera.rotationPitch),
            visibleSections, minecraft.world.loadedEntityList.size(),
            minecraft.world.loadedTileEntityList.size(),
            minecraft.gameSettings.renderDistanceChunks, minecraft.displayWidth,
            minecraft.displayHeight, weather, runtime.currentStamp.getResourceGeneration(),
            runtime.currentStamp.getShaderPackGeneration(), terrainArenaOwned,
            terrainRegionRuns);
    }

    static int floorRegion(double coordinate) {
        if (!Double.isFinite(coordinate)) return 0;
        double value = Math.floor(coordinate / 16.0D);
        if (value <= Integer.MIN_VALUE) return Integer.MIN_VALUE;
        if (value >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) value;
    }

    static int angleBucket(float angle) {
        if (Float.isNaN(angle) || Float.isInfinite(angle)) return 0;
        return (int) Math.floor(angle / 5.0F);
    }

    private static final class HzbGpuSample {
        private final SceneFingerprint scene;
        private final MeasurementArm arm;
        private int completedGpuScopes;
        private long cpuNanos;
        private long gpuNanos;
        private boolean cpuReady;
        private CorrelatedRenderProfiler.GpuScope gpuScope;

        private HzbGpuSample(SceneFingerprint scene, MeasurementArm arm) {
            this.scene = scene;
            this.arm = arm;
        }

        private void closeGpuScope() {
            CorrelatedRenderProfiler.GpuScope closing = gpuScope;
            gpuScope = null;
            if (closing != null) closing.close();
        }
    }

    private static final class BackendGpuSample {
        private final OptimizationModule module;
        private final SceneFingerprint scene;
        private final MeasurementArm arm;
        private long cpuNanos;
        private long gpuNanos;
        private boolean cpuReady;
        private boolean gpuReady;
        private boolean stable;
        private boolean emptyModernArm;

        private BackendGpuSample(OptimizationModule module,
                                 SceneFingerprint scene, MeasurementArm arm) {
            this.module = module;
            this.scene = scene;
            this.arm = arm;
        }
    }

    private static final long WHOLE_FRAME_GPU_SAMPLE_INTERVAL = 120L;
    private static long safeAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }
}
