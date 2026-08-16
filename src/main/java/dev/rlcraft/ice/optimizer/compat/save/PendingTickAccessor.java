package dev.rlcraft.ice.optimizer.compat.save;

import java.util.List;
import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.chunk.Chunk;

/**
 * Accessor and original-method trampoline injected into the reviewed WorldServer class.
 *
 * <p>This interface is an early CoreMod ABI. Its source lives with the save runtime, but the
 * release build packages the class exclusively in the optimizer core JAR so transformed
 * Minecraft classes can resolve it before Forge loads the regular mod JAR.</p>
 */
public interface PendingTickAccessor {
    Iterable<NextTickListEntry> ice$pendingTickTree();

    List<NextTickListEntry> ice$pendingTicksThisTick();

    long ice$pendingTickVersion();

    List<NextTickListEntry> ice$originalPendingBlockUpdates(Chunk chunk, boolean remove);
}
