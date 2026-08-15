package dev.rlcraft.ice.optimizer.compat.entity;

import static org.junit.Assert.assertEquals;

import dev.rlcraft.ice.optimizer.ClientOptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Bootstrap;
import net.minecraft.world.World;
import org.junit.Test;

public class QuarkItemSyncBridgeTest {
    @Test
    public void preservesQuarksPersistentAgeAndLifespanChangeSemantics() {
        boolean oldGlobal = OptimizerConfig.settings.enabled;
        boolean oldModule = OptimizerConfig.settings.quarkItemSync;
        try {
            Bootstrap.register();
            configure(true);
            QuarkItemSyncBridge.clearForTests();
            EntityItem ageItem = new EntityItem((World) null);
            assertEquals(QuarkItemSyncBridge.NO_SYNC,
                QuarkItemSyncBridge.decision(ageItem, 0, 6000));
            assertEquals(QuarkItemSyncBridge.NO_SYNC,
                QuarkItemSyncBridge.decision(ageItem, 1, 6000));
            assertEquals(QuarkItemSyncBridge.SYNC,
                QuarkItemSyncBridge.decision(ageItem, 20, 6000));
            assertEquals("Quark leaves the old age in its map after a discontinuity",
                QuarkItemSyncBridge.SYNC,
                QuarkItemSyncBridge.decision(ageItem, 21, 6000));

            EntityItem lifespanItem = new EntityItem((World) null);
            assertEquals(QuarkItemSyncBridge.NO_SYNC,
                QuarkItemSyncBridge.decision(lifespanItem, 0, 6000));
            assertEquals(QuarkItemSyncBridge.SYNC,
                QuarkItemSyncBridge.decision(lifespanItem, 1, 5000));
            assertEquals("Quark never updates LIFESPAN_MAP after a change",
                QuarkItemSyncBridge.SYNC,
                QuarkItemSyncBridge.decision(lifespanItem, 2, 5000));

            configure(false);
            assertEquals(QuarkItemSyncBridge.USE_ORIGINAL,
                QuarkItemSyncBridge.decision(ageItem, 22, 6000));
        } finally {
            OptimizerConfig.settings.enabled = oldGlobal;
            OptimizerConfig.settings.quarkItemSync = oldModule;
            OptimizerRegistry.configure(ClientOptimizerConfig.capture());
            QuarkItemSyncBridge.clearForTests();
        }
    }

    private static void configure(boolean enabled) {
        OptimizerConfig.settings.enabled = true;
        OptimizerConfig.settings.quarkItemSync = enabled;
        OptimizerRegistry.configure(ClientOptimizerConfig.capture());
        if (enabled) {
            OptimizerRegistry.targetObserved("quark-item-sync", "test.Quark", repeat('b', 64), true);
            OptimizerRegistry.patchInstalled("quark-item-sync", "test.Quark", repeat('b', 64));
        }
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
