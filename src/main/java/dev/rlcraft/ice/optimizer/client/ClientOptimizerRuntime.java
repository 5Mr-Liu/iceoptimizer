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
import dev.rlcraft.ice.optimizer.compat.chunk.ChunkRenderTelemetry;
import dev.rlcraft.ice.optimizer.compat.chunk.ChunkRenderStatus;
import dev.rlcraft.ice.optimizer.lock.PackLockStatus;
import dev.rlcraft.ice.optimizer.lock.PackLockState;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
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
    private BoundedRenderQueue renderQueue;
    private ClientWorkerRuntime workers;
    private CacheBudget cacheBudget;
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
            initialized = true;
            IceMod.LOGGER.info("ICE 客户端优化运行时已由配置关闭");
            return;
        }
        epochs = new ClientEpochs();
        renderQueue = new BoundedRenderQueue(epochs, config.getRenderQueueCapacity());
        workers = new ClientWorkerRuntime(epochs, renderQueue, config.getWorkerThreads(), config.getWorkerQueueCapacity());
        cacheBudget = new CacheBudget(config.getHeapCacheBudgetBytes(), config.getDirectCacheBudgetBytes(), config.getGpuCacheBudgetBytes());
        SkullProfileBridge.configure(config.getSkullProfileCacheEntries(),
            config.getSkullProfilePositiveTtlMinutes(), config.getSkullProfileNegativeTtlSeconds(),
            config.getSkullProfileQueueCapacity());
        OptimizerBridge.attachClientRuntime(this);
        OptimizerRegistry.breaker(OptimizationModule.CORE_RUNTIME).activate("专用线程、代际取消和预算系统已启动");
        OptimizerRegistry.breaker(OptimizationModule.RENDER_SUBMISSION).activate("有界 MPSC 渲染队列已启动");
        initialized = true;
        IceMod.LOGGER.info("ICE RLCraft 客户端优化运行时启动：{} 个工作线程，CPU 队列 {}，渲染队列 {}，兼容策略 {}",
            config.getWorkerThreads(), config.getWorkerQueueCapacity(), config.getRenderQueueCapacity(), packLock.getState());
        if (!coreModPresent) {
            IceMod.LOGGER.error("ICE Optimizer Core JAR 未加载；客户端将安全运行，但字节码优化不会生效");
        }
        if (!packLock.permitsPatches()) IceMod.LOGGER.warn("ICE 外部优化补丁保持关闭：{}", packLock.getDetail());
    }

    public long beginFrame() {
        if (!initialized || epochs == null) return 0L;
        return epochs.nextFrame();
    }

    public long beginClientTick() {
        if (!initialized || epochs == null) return 0L;
        return epochs.nextClientTick();
    }

    public void worldChanged() {
        if (epochs == null) return;
        epochs.invalidateWorld();
        ChunkSaveCompressionBridge.reset();
        if (workers != null) workers.discardStaleQueuedTasks();
    }

    public void resourcesReloaded() {
        if (epochs == null) return;
        epochs.invalidateResources();
        if (workers != null) workers.discardStaleQueuedTasks();
    }

    public void glContextReset() {
        if (epochs == null) return;
        epochs.invalidateGlContext();
        if (workers != null) workers.discardStaleQueuedTasks();
    }

    public int drainRenderQueue() {
        if (renderQueue == null || config == null) return 0;
        return renderQueue.drain(config.getRenderDrainBudgetNanos(), 4096);
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
        return workers != null && workers.submit(module, token, epochMask, computation, renderCompletion);
    }

    public CacheBudget.Reservation tryReserve(BudgetKind kind, long bytes) {
        CacheBudget budget = cacheBudget;
        return budget == null ? null : budget.tryReserve(kind, bytes);
    }

    public synchronized void shutdown() {
        OptimizerBridge.detachClientRuntime(this);
        if (workers != null) workers.shutdown();
        SkullProfileBridge.shutdown();
        ChunkSaveCompressionBridge.shutdown();
        workers = null;
        renderQueue = null;
        cacheBudget = null;
        epochs = null;
        OptimizerRegistry.shutdown("客户端优化运行时已停止");
        initialized = false;
        coreModPresent = false;
    }

    public ClientOptimizerStatus status() {
        return new ClientOptimizerStatus(initialized, coreModPresent, packLock,
            workers == null ? null : workers.snapshot(),
            renderQueue == null ? null : renderQueue.snapshot(),
            cacheBudget == null ? null : cacheBudget.snapshot(),
            epochs == null ? null : epochs.snapshot(), safeChunkRenderStatus(),
            OptimizerRegistry.snapshot());
    }

    private ChunkRenderStatus safeChunkRenderStatus() {
        if (!coreModPresent) return new ChunkRenderStatus(0, 0, 0, 0L, 0L, 0L, "CORE-MISSING");
        try {
            return ChunkRenderTelemetry.snapshot();
        } catch (LinkageError incompatibleCore) {
            return new ChunkRenderStatus(0, 0, 0, 0L, 0L, 0L, "CORE-ABI");
        }
    }

}
