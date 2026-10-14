package dev.rlcraft.ice.optimizer.client;

import dev.rlcraft.ice.IceMod;
import dev.rlcraft.ice.optimizer.ClientOptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import dev.rlcraft.ice.optimizer.common.CommonOptimizerBootstrap;
import dev.rlcraft.ice.optimizer.common.OptimizerBootstrapResult;
import dev.rlcraft.ice.optimizer.bridge.ClientRuntimeAccess;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import dev.rlcraft.ice.optimizer.compat.skull.SkullProfileBridge;
import dev.rlcraft.ice.optimizer.compat.save.ChunkSaveCompressionBridge;
import dev.rlcraft.ice.optimizer.compat.chunk.ChunkVboUploadBridge;
import dev.rlcraft.ice.optimizer.compat.chunk.ChunkRenderTelemetry;
import dev.rlcraft.ice.optimizer.compat.chunk.ChunkRenderStatus;
import dev.rlcraft.ice.optimizer.compat.foamfix.FoamFixUploadBridge;
import dev.rlcraft.ice.optimizer.compat.konkrete.KonkreteLocaleBridge;
import dev.rlcraft.ice.optimizer.compat.model.ModelMeshCaptureBridge;
import dev.rlcraft.ice.optimizer.compat.optifine.OptifineShaderSourceBridge;
import dev.rlcraft.ice.optimizer.compat.renderlib.RenderLibTileEntityBridge;
import dev.rlcraft.ice.optimizer.compat.srp.SrpKirinRenderBridge;
import dev.rlcraft.ice.optimizer.compat.xaero.XaeroGpuTimerBridge;
import dev.rlcraft.ice.optimizer.lock.PackLockStatus;
import dev.rlcraft.ice.optimizer.lock.PackLockState;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.render.terrain.NativeTerrainRenderer;
import dev.rlcraft.ice.optimizer.runtime.BoundedRenderQueue;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import dev.rlcraft.ice.optimizer.runtime.ClientWorkerRuntime;
import dev.rlcraft.ice.optimizer.runtime.EpochToken;
import java.io.File;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

public final class ClientOptimizerRuntime implements ClientRuntimeAccess {
    public static final ClientOptimizerRuntime INSTANCE = new ClientOptimizerRuntime();

    private volatile boolean initialized;
    private volatile boolean coreModPresent;
    private ClientOptimizerConfig config;
    private ClientEpochs epochs;
    private volatile BoundedRenderQueue renderQueue;
    private volatile ClientWorkerRuntime workers;
    private CacheBudget cacheBudget;
    private volatile ModernRendererRuntime modernRenderer;
    private NativeTerrainPauseLifecycle nativeTerrainPause;
    private PackLockStatus packLock = new PackLockStatus(PackLockState.DISCOVERY, "尚未初始化", Collections.emptyList(), null);

    private ClientOptimizerRuntime() {
    }

    public synchronized void initialize(File gameDirectory) {
        if (initialized) return;
        config = ClientOptimizerConfig.capture();
        OptimizerBootstrapResult bootstrap = CommonOptimizerBootstrap.initialize(gameDirectory, config);
        coreModPresent = bootstrap.isCoreModPresent();
        packLock = bootstrap.getPackLock();
        if (!config.isEnabled()) {
            ModelMeshCaptureBridge.reset();
            initialized = true;
            IceMod.LOGGER.info("ICE 客户端优化运行时已由配置关闭");
            return;
        }
        epochs = new ClientEpochs();
        renderQueue = new BoundedRenderQueue(epochs, config.getRenderQueueCapacity());
        workers = new ClientWorkerRuntime(epochs, renderQueue, config.getWorkerThreads(), config.getWorkerQueueCapacity());
        cacheBudget = new CacheBudget(config.getHeapCacheBudgetBytes(), config.getDirectCacheBudgetBytes(), config.getGpuCacheBudgetBytes());
        if (!config.usesExternalTerrainRenderer() && !config.usesNativeTerrainRenderer()) {
            modernRenderer = new ModernRendererRuntime(epochs, cacheBudget);
        }
        if (config.usesNativeTerrainRenderer()) nativeTerrainPause = new NativeTerrainPauseLifecycle();
        SkullProfileBridge.configure(config.isIncompletePlayerSkullProfileLookupAllowed(),
            config.enabled(OptimizationModule.SKULL_PROFILE_ASYNC), config.getSkullProfileCacheEntries(),
            config.getSkullProfilePositiveTtlMinutes(), config.getSkullProfileNegativeTtlSeconds(),
            config.getSkullProfileQueueCapacity());
        OptimizerBridge.attachClientRuntime(this);
        OptimizerRegistry.breaker(OptimizationModule.CORE_RUNTIME).activate("专用线程、代际取消和预算系统已启动");
        OptimizerRegistry.breaker(OptimizationModule.RENDER_SUBMISSION).activate("有界 MPSC 渲染队列已启动");
        initialized = true;
        IceMod.LOGGER.info("ICE RLCraft 客户端优化运行时启动：{} 个工作线程，CPU 队列 {}，渲染队列 {}，兼容策略 {}",
            config.getWorkerThreads(), config.getWorkerQueueCapacity(), config.getRenderQueueCapacity(), packLock.getState());
        if (!coreModPresent) {
            IceMod.LOGGER.error("ICE Optimizer 启动插件或转换器未注册；客户端将继续运行，但字节码优化不会生效");
        }
        if (!packLock.permitsPatches()) IceMod.LOGGER.warn("ICE 外部优化补丁保持关闭：{}", packLock.getDetail());
    }

    public long beginFrame() {
        if (!initialized || epochs == null) return 0L;
        long frameId = epochs.nextFrame();
        ModernRendererRuntime renderer = modernRenderer;
        if (renderer != null) renderer.beginFrame(frameId, epochs.snapshot());
        return frameId;
    }

    public void endFrame() {
        ModernRendererRuntime renderer = modernRenderer;
        if (renderer != null) renderer.endFrame();
    }

    public long beginClientTick() {
        if (!initialized || epochs == null) return 0L;
        return epochs.nextClientTick();
    }

    /** Called on the client thread between rendered frames. Does not rewrite module configuration. */
    public synchronized boolean setRuntimePaused(boolean paused) {
        if (!initialized || !config.isEnabled()) return false;
        boolean previous = OptimizerRegistry.isRuntimePaused();
        if (previous == paused) return true;
        IceMod.LOGGER.info("ICE 优化总开关切换请求：{}", paused ? "关闭" : "开启");
        if (nativeTerrainPause != null) nativeTerrainPause.beforeChange();
        if (paused) OptimizerRegistry.setRuntimePaused(true);
        ModernRendererRuntime renderer = modernRenderer;
        if (renderer != null && !renderer.setRuntimePaused(paused)) {
            OptimizerRegistry.setRuntimePaused(previous);
            return false;
        }
        if (paused) {
            ClientWorkerRuntime oldWorkers = workers;
            workers = null;
            renderQueue = null;
            // A stopped queue also rejects completions from a worker finishing after resume.
            if (oldWorkers != null) oldWorkers.shutdown();
            ChunkSaveCompressionBridge.pause();
        } else {
            BoundedRenderQueue nextQueue = new BoundedRenderQueue(epochs, config.getRenderQueueCapacity());
            renderQueue = nextQueue;
            workers = new ClientWorkerRuntime(epochs, nextQueue,
                config.getWorkerThreads(), config.getWorkerQueueCapacity());
        }
        if (config.isIncompletePlayerSkullProfileLookupAllowed()
            && config.enabled(OptimizationModule.SKULL_PROFILE_ASYNC)) {
            SkullProfileBridge.configure(true, !paused,
                config.getSkullProfileCacheEntries(), config.getSkullProfilePositiveTtlMinutes(),
                config.getSkullProfileNegativeTtlSeconds(), config.getSkullProfileQueueCapacity());
        }
        if (!paused) OptimizerRegistry.setRuntimePaused(false);
        if (nativeTerrainPause != null) nativeTerrainPause.afterChange();
        IceMod.LOGGER.info("ICE 优化总开关：{}{}", paused ? "关闭" : "开启",
            nativeTerrainPause == null ? "" : "；地形资源已按当前模式重建");
        return true;
    }

    public boolean isRuntimePaused() { return OptimizerRegistry.isRuntimePaused(); }

    public boolean isCoreModPresent() { return coreModPresent; }

    public void worldChanged() {
        if (epochs == null) return;
        NativeTerrainRenderer.reset(true);
        epochs.invalidateWorld();
        epochs.invalidateViewFrustum();
        ModernRendererRuntime renderer = modernRenderer;
        if (renderer != null) renderer.invalidateWorld();
        ChunkSaveCompressionBridge.reset();
        RenderLibTileEntityBridge.reset();
        discardStaleWorkerTasks();
    }

    public void resourcesReloaded() {
        if (epochs == null) return;
        NativeTerrainRenderer.reset(true);
        epochs.invalidateResources();
        resetResourceCaptureBridges();
        ModernRendererRuntime renderer = modernRenderer;
        if (renderer != null) renderer.invalidateResources();
        discardStaleWorkerTasks();
    }

    public void atlasChanged() {
        if (epochs == null) return;
        NativeTerrainRenderer.reset(true);
        epochs.invalidateResources();
        epochs.invalidateAtlas();
        epochs.invalidateShaderPermutation();
        resetResourceCaptureBridges();
        ModernRendererRuntime renderer = modernRenderer;
        if (renderer != null) renderer.invalidateResources();
        discardStaleWorkerTasks();
    }

    public void modelsChanged() {
        if (epochs == null) return;
        NativeTerrainRenderer.reset(true);
        epochs.invalidateResources();
        resetResourceCaptureBridges();
        ModernRendererRuntime renderer = modernRenderer;
        if (renderer != null) renderer.invalidateResources();
        discardStaleWorkerTasks();
    }

    public void glContextReset() {
        if (epochs == null) return;
        NativeTerrainRenderer.reset(false);
        long oldGeneration = epochs.currentGlContextGeneration();
        epochs.invalidateGlContext();
        FoamFixUploadBridge.contextLost(oldGeneration);
        ChunkVboUploadBridge.contextLost();
        XaeroGpuTimerBridge.contextLost(oldGeneration);
        SrpKirinRenderBridge.contextLost(oldGeneration);
        ModelMeshCaptureBridge.reset();
        ModernRendererRuntime renderer = modernRenderer;
        if (renderer != null) renderer.invalidateContext(oldGeneration);
        discardStaleWorkerTasks();
    }

    public void shaderPackChanged() {
        if (epochs == null) return;
        NativeTerrainRenderer.reset(true);
        epochs.invalidateShaderPack();
        epochs.invalidateShaderPermutation();
        OptifineShaderSourceBridge.reset();
        ModernRendererRuntime renderer = modernRenderer;
        if (renderer != null) renderer.invalidateResources();
        discardStaleWorkerTasks();
    }

    public void shaderPackStateObserved(boolean known, boolean active) {
        ModernRendererRuntime renderer = modernRenderer;
        if (renderer != null) renderer.setShaderPackState(known, active);
    }

    public void shaderPermutationChanged() {
        if (epochs == null) return;
        epochs.invalidateShaderPermutation();
        OptifineShaderSourceBridge.reset();
        ModernRendererRuntime renderer = modernRenderer;
        if (renderer != null) renderer.invalidateResources();
        discardStaleWorkerTasks();
    }

    public void vertexFormatChanged() {
        if (epochs == null) return;
        NativeTerrainRenderer.reset(true);
        epochs.invalidateVertexFormat();
        ModelMeshCaptureBridge.reset();
        ModernRendererRuntime renderer = modernRenderer;
        if (renderer != null) renderer.invalidateResources();
        discardStaleWorkerTasks();
    }

    public void viewFrustumChanged() {
        if (epochs == null) return;
        epochs.invalidateViewFrustum();
        ModernRendererRuntime renderer = modernRenderer;
        if (renderer != null) renderer.invalidateWorld();
        discardStaleWorkerTasks();
    }

    public int drainRenderQueue() {
        BoundedRenderQueue queue = renderQueue;
        if (queue == null || config == null) return 0;
        return queue.drain(config.getRenderDrainBudgetNanos(), 4096);
    }

    private void discardStaleWorkerTasks() {
        ClientWorkerRuntime currentWorkers = workers;
        if (currentWorkers != null) currentWorkers.discardStaleQueuedTasks();
    }

    public EpochToken captureEpochs() {
        return epochs == null ? null : epochs.snapshot();
    }

    @Override public long currentFrameId() {
        ClientEpochs value = epochs;
        return value == null ? 0L : value.currentFrameId();
    }

    @Override public long currentClientTickId() {
        ClientEpochs value = epochs;
        return value == null ? 0L : value.currentClientTickId();
    }

    @Override public long currentWorldGeneration() {
        ClientEpochs value = epochs;
        return value == null ? 0L : value.currentWorldGeneration();
    }

    @Override public long currentResourceGeneration() {
        ClientEpochs value = epochs;
        return value == null ? 0L : value.currentResourceGeneration();
    }

    @Override public long currentGlContextGeneration() {
        ClientEpochs value = epochs;
        return value == null ? 0L : value.currentGlContextGeneration();
    }

    public <T> boolean submit(OptimizationModule module, EpochToken token, int epochMask,
                              Callable<T> computation, Consumer<T> renderCompletion) {
        ClientWorkerRuntime currentWorkers = workers;
        return currentWorkers != null && currentWorkers.submit(module, token, epochMask, computation, renderCompletion);
    }

    public CacheBudget.Reservation tryReserve(BudgetKind kind, long bytes) {
        CacheBudget budget = cacheBudget;
        return budget == null ? null : budget.tryReserve(kind, bytes);
    }

    public CacheBudget cacheBudget() { return cacheBudget; }

    public synchronized void shutdown() {
        NativeTerrainRenderer.reset(true);
        // Stop every producer before disposing render-thread state. A worker
        // which outlives its bounded join is still unable to enqueue a late
        // completion because ClientWorkerRuntime closes the render queue first.
        if (workers != null) workers.shutdown();
        FoamFixUploadBridge.shutdown();
        ChunkVboUploadBridge.shutdown();
        XaeroGpuTimerBridge.shutdown();
        SrpKirinRenderBridge.reset();
        if (modernRenderer != null) modernRenderer.shutdown();
        OptimizerBridge.detachClientRuntime(this);
        SkullProfileBridge.shutdown();
        RenderLibTileEntityBridge.reset();
        ModelMeshCaptureBridge.reset();
        OptifineShaderSourceBridge.reset();
        KonkreteLocaleBridge.reset();
        ChunkSaveCompressionBridge.shutdown();
        workers = null;
        renderQueue = null;
        modernRenderer = null;
        nativeTerrainPause = null;
        cacheBudget = null;
        epochs = null;
        OptimizerRegistry.shutdown("客户端优化运行时已停止");
        initialized = false;
        coreModPresent = false;
    }

    public ClientOptimizerStatus status() {
        ClientWorkerRuntime currentWorkers = workers;
        BoundedRenderQueue currentQueue = renderQueue;
        return new ClientOptimizerStatus(initialized, coreModPresent, isRuntimePaused(), packLock,
            currentWorkers == null ? null : currentWorkers.snapshot(),
            currentQueue == null ? null : currentQueue.snapshot(),
            cacheBudget == null ? null : cacheBudget.snapshot(),
            epochs == null ? null : epochs.snapshot(), safeChunkRenderStatus(),
            modernRenderer == null ? null : modernRenderer.status(),
            OptimizerRegistry.snapshot());
    }

    public ModernRendererRuntime modernRenderer() {
        return modernRenderer;
    }

    private ChunkRenderStatus safeChunkRenderStatus() {
        if (!coreModPresent) return new ChunkRenderStatus(0, 0, 0, 0L, 0L, 0L, "CORE-MISSING");
        try {
            return ChunkRenderTelemetry.snapshot();
        } catch (LinkageError incompatibleCore) {
            dev.rlcraft.ice.optimizer.FatalErrors.rethrowIfFatal(incompatibleCore);
            return new ChunkRenderStatus(0, 0, 0, 0L, 0L, 0L, "CORE-ABI");
        }
    }

    private static void resetResourceCaptureBridges() {
        ModelMeshCaptureBridge.reset();
        SrpKirinRenderBridge.reset();
        OptifineShaderSourceBridge.reset();
        KonkreteLocaleBridge.reset();
    }

}
