package dev.rlcraft.ice.optimizer.compat.nbt;

/** Access to the runtime state of the two reviewed, pure payload formats. */
public interface CapabilityStateProviderAccess {
    /** Zero means that the receiver is not the reviewed concrete provider. */
    int ice$stateKind();
    Object ice$statePayload();
    Object ice$stateOwner();
}
