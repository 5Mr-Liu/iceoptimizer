package dev.rlcraft.ice.optimizer.compat.fishing;

import static org.junit.Assert.*;
import java.util.Map;
import net.minecraftforge.common.BiomeDictionary;
import org.junit.Test;

public class FishingBiomeBridgeTest {
    @Test public void sharedNamesKeepEachExistingChunkSnapshotAndIncludeLaterRegistrationsForNewChunks() {
        Map<String, BiomeDictionary.Type> before = FishingBiomeBridge.sharedNames();
        assertSame(before, FishingBiomeBridge.sharedNames());
        String name = "ICE_TEST_LATE_FISH_BIOME_" + System.nanoTime();
        BiomeDictionary.Type added = BiomeDictionary.Type.getType(name);
        Map<String, BiomeDictionary.Type> after = FishingBiomeBridge.sharedNames();
        assertNotSame(before, after);
        assertFalse(before.containsKey(name));
        assertSame(added, after.get(name));
        assertSame(after, FishingBiomeBridge.sharedNames());
    }
}
