package dev.rlcraft.ice.optimizer.compat.model;

/** Implemented by reviewed model subclasses with their own render/display-list semantics. */
public interface ModelStateBatchSubclassAccess extends ModelStateBatchAccess {
    boolean ice$stateBatchRenderClassReady();
}
