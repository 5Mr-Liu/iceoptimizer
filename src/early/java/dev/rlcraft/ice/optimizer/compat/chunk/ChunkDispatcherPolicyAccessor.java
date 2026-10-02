package dev.rlcraft.ice.optimizer.compat.chunk;

/** Early-load ABI for clamping the final builder count after other CoreMods. */
public interface ChunkDispatcherPolicyAccessor {
    int ice$builderCount();
    void ice$setBuilderCount(int value);
}
