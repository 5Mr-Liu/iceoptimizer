package dev.rlcraft.ice.optimizer.compat.entity;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.ClientOptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.util.Arrays;
import java.util.EnumMap;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.EnumHandSide;
import net.minecraft.world.World;
import org.junit.Test;

public class QualityToolsAttributeBridgeTest {
    @Test
    public void refreshesOnFirstUseEquipmentChangesAndPeriodicVerification() {
        boolean oldGlobal = OptimizerConfig.settings.enabled;
        boolean oldModule = OptimizerConfig.settings.qualityToolsEntityAttributes;
        try {
            Bootstrap.register();
            configure(true);
            QualityToolsAttributeBridge.clearForTests();
            TestLiving entity = new TestLiving();
            entity.ticksExisted = 7;
            assertTrue(QualityToolsAttributeBridge.shouldRefresh(entity));
            entity.ticksExisted = 14;
            assertFalse(QualityToolsAttributeBridge.shouldRefresh(entity));

            ItemStack held = new ItemStack(new Item());
            entity.setItemStackToSlot(EntityEquipmentSlot.MAINHAND, held);
            entity.ticksExisted = 21;
            assertTrue(QualityToolsAttributeBridge.shouldRefresh(entity));
            entity.ticksExisted = 28;
            assertFalse(QualityToolsAttributeBridge.shouldRefresh(entity));

            held.setCount(2);
            entity.ticksExisted = 35;
            assertTrue("the snapshot is a full ItemStack copy, not an identity check",
                QualityToolsAttributeBridge.shouldRefresh(entity));
            entity.ticksExisted = 42;
            assertFalse(QualityToolsAttributeBridge.shouldRefresh(entity));
            entity.ticksExisted = 168;
            assertFalse(QualityToolsAttributeBridge.shouldRefresh(entity));
            entity.ticksExisted = 175;
            assertTrue(QualityToolsAttributeBridge.shouldRefresh(entity));

            configure(false);
            assertTrue(QualityToolsAttributeBridge.shouldRefresh(entity));
        } finally {
            OptimizerConfig.settings.enabled = oldGlobal;
            OptimizerConfig.settings.qualityToolsEntityAttributes = oldModule;
            OptimizerRegistry.configure(ClientOptimizerConfig.capture());
            QualityToolsAttributeBridge.clearForTests();
        }
    }

    private static void configure(boolean enabled) {
        OptimizerConfig.settings.enabled = true;
        OptimizerConfig.settings.qualityToolsEntityAttributes = enabled;
        OptimizerRegistry.configure(ClientOptimizerConfig.capture());
        if (enabled) {
            OptimizerRegistry.targetObserved("qualitytools-attributes", "test.Quality", repeat('c', 64), true);
            OptimizerRegistry.patchInstalled("qualitytools-attributes", "test.Quality", repeat('c', 64));
        }
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    private static final class TestLiving extends EntityLivingBase {
        private final EnumMap<EntityEquipmentSlot, ItemStack> equipment =
            new EnumMap<EntityEquipmentSlot, ItemStack>(EntityEquipmentSlot.class);

        private TestLiving() {
            super((World) null);
            for (EntityEquipmentSlot slot : EntityEquipmentSlot.values()) {
                equipment.put(slot, ItemStack.EMPTY);
            }
        }

        @Override public Iterable<ItemStack> getArmorInventoryList() {
            return Arrays.asList(
                getItemStackFromSlot(EntityEquipmentSlot.HEAD),
                getItemStackFromSlot(EntityEquipmentSlot.CHEST),
                getItemStackFromSlot(EntityEquipmentSlot.LEGS),
                getItemStackFromSlot(EntityEquipmentSlot.FEET));
        }

        @Override public ItemStack getItemStackFromSlot(EntityEquipmentSlot slot) {
            ItemStack value = equipment.get(slot);
            return value == null ? ItemStack.EMPTY : value;
        }

        @Override public void setItemStackToSlot(EntityEquipmentSlot slot, ItemStack stack) {
            equipment.put(slot, stack == null ? ItemStack.EMPTY : stack);
        }

        @Override public EnumHandSide getPrimaryHand() {
            return EnumHandSide.RIGHT;
        }
    }
}
