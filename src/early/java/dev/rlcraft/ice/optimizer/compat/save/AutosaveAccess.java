package dev.rlcraft.ice.optimizer.compat.save;

import java.util.Collection;
import net.minecraft.world.chunk.Chunk;

/** Server-thread-only callbacks to the original chunk writer. */
public interface AutosaveAccess {
    Object ice$autosaveCursor();
    void ice$autosaveCursor(Object cursor);
    Collection<Chunk> ice$loadedChunks();
    boolean ice$isLoaded(Chunk chunk);
    boolean ice$canSave();
    boolean ice$originalSaveChunks(boolean all);
    void ice$saveChunkData(Chunk chunk);
    void ice$saveChunkExtraData(Chunk chunk);
}
