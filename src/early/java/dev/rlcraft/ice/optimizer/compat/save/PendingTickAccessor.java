package dev.rlcraft.ice.optimizer.compat.save;

import java.util.List;
import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.chunk.Chunk;

/**
 * Accessor and original-method trampoline injected into the reviewed WorldServer class.
 *
 * <p>This interface is compiled in the early source set and included in the single optimizer
 * JAR, which Forge exposes to LaunchWrapper before transformed Minecraft classes are defined.</p>
 */
public interface PendingTickAccessor {
    Iterable<NextTickListEntry> ice$pendingTickTree();

    List<NextTickListEntry> ice$pendingTicksThisTick();

    List<NextTickListEntry> ice$originalPendingBlockUpdates(Chunk chunk, boolean remove);
}
