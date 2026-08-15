package dev.rlcraft.ice.optimizer.client;

import dev.rlcraft.ice.optimizer.ModuleStatus;
import dev.rlcraft.ice.optimizer.lock.PackLockStatus;
import dev.rlcraft.ice.optimizer.memory.CacheBudgetStatus;
import dev.rlcraft.ice.optimizer.runtime.EpochToken;
import dev.rlcraft.ice.optimizer.runtime.RenderQueueStatus;
import dev.rlcraft.ice.optimizer.runtime.WorkerStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ClientOptimizerStatus {
    private final boolean initialized;
    private final PackLockStatus packLock;
    private final WorkerStatus workers;
    private final RenderQueueStatus renderQueue;
    private final CacheBudgetStatus cacheBudget;
    private final EpochToken epochs;
    private final List<ModuleStatus> modules;

    public ClientOptimizerStatus(boolean initialized, PackLockStatus packLock, WorkerStatus workers,
                                 RenderQueueStatus renderQueue, CacheBudgetStatus cacheBudget,
                                 EpochToken epochs, List<ModuleStatus> modules) {
        this.initialized = initialized;
        this.packLock = packLock;
        this.workers = workers;
        this.renderQueue = renderQueue;
        this.cacheBudget = cacheBudget;
        this.epochs = epochs;
        this.modules = Collections.unmodifiableList(new ArrayList<ModuleStatus>(modules));
    }

    public boolean isInitialized() { return initialized; }
    public PackLockStatus getPackLock() { return packLock; }
    public WorkerStatus getWorkers() { return workers; }
    public RenderQueueStatus getRenderQueue() { return renderQueue; }
    public CacheBudgetStatus getCacheBudget() { return cacheBudget; }
    public EpochToken getEpochs() { return epochs; }
    public List<ModuleStatus> getModules() { return modules; }
}
