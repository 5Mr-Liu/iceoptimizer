package dev.rlcraft.ice.optimizer.compat.lycanites;

import java.util.List;
import net.minecraft.util.ResourceLocation;

/** Tiny accessor ABI injected into the reviewed Lycanites BlockSpawnLocation class. */
public interface LycanitesSpawnScanAccessor {
    boolean ice$spawnSurface();

    boolean ice$spawnUnderground();

    List<ResourceLocation> ice$spawnBlockIds();
}
