package dev.rlcraft.ice.optimizer.compat.model;

/** Injected only into a ModelRenderer whose render graph was reviewed at transformation. */
public interface ModelStateBatchAccess {
    int ice$stateBatchDisplayList();
    boolean ice$stateBatchNodeReady();
}
