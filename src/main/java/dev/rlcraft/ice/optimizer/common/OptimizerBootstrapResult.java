package dev.rlcraft.ice.optimizer.common;

import dev.rlcraft.ice.optimizer.lock.PackLockStatus;

/** Side-neutral result of linking the early CoreMod to the normal Forge runtime. */
public final class OptimizerBootstrapResult {
    private final PackLockStatus packLock;
    private final boolean coreModPresent;

    OptimizerBootstrapResult(PackLockStatus packLock, boolean coreModPresent) {
        this.packLock = packLock;
        this.coreModPresent = coreModPresent;
    }

    public PackLockStatus getPackLock() {
        return packLock;
    }

    public boolean isCoreModPresent() {
        return coreModPresent;
    }
}
