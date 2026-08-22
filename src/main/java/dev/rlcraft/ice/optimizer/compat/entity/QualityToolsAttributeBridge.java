package dev.rlcraft.ice.optimizer.compat.entity;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;

/** Equipment snapshots for Quality Tools' seven-tick attribute refresh. */
public final class QualityToolsAttributeBridge {
    private static final String MODULE = "qualitytools-attributes";
    private static final int PERIODIC_VERIFY_TICKS = 140;
    private static final EntityEquipmentSlot[] SLOTS = {
        EntityEquipmentSlot.MAINHAND, EntityEquipmentSlot.OFFHAND,
        EntityEquipmentSlot.HEAD, EntityEquipmentSlot.CHEST,
        EntityEquipmentSlot.LEGS, EntityEquipmentSlot.FEET
    };
    private static final Map<EntityLivingBase, Snapshot> SNAPSHOTS =
        new WeakHashMap<EntityLivingBase, Snapshot>();
    private static volatile boolean activated;

    private QualityToolsAttributeBridge() {
    }

    public static boolean shouldRefresh(EntityLivingBase entity) {
        if (entity == null) return true;
        try {
            if (!OptimizerBridge.isEnabled(MODULE)) return true;
            // Players also scan the full inventory and Baubles; horses serialize their armor NBT.
            // Keep those uncommon paths exact and optimize the large population of ordinary mobs.
            if (entity instanceof EntityPlayer || entity instanceof EntityHorse) return true;
            synchronized (SNAPSHOTS) {
                Snapshot previous = SNAPSHOTS.get(entity);
                if (previous == null) {
                    SNAPSHOTS.put(entity, Snapshot.capture(entity));
                    activateOnce();
                    return true;
                }
                if (!previous.matches(entity)) {
                    previous.update(entity);
                    previous.lastVerifiedTick = entity.ticksExisted;
                    activateOnce();
                    return true;
                }
                if (entity.ticksExisted - previous.lastVerifiedTick >= PERIODIC_VERIFY_TICKS
                    || entity.ticksExisted < previous.lastVerifiedTick) {
                    previous.lastVerifiedTick = entity.ticksExisted;
                    return true;
                }
                activateOnce();
                return false;
            }
        } catch (Throwable error) {
            fail(error);
            return true;
        }
    }

    static void clearForTests() {
        synchronized (SNAPSHOTS) {
            SNAPSHOTS.clear();
        }
    }

    private static void activateOnce() {
        if (activated) return;
        synchronized (QualityToolsAttributeBridge.class) {
            if (activated) return;
            OptimizerBridge.activate(MODULE,
                "Quality Tools 对未换装备的普通生物复用属性状态，并每 140 Tick 强制复核");
            activated = true;
        }
    }

    private static void fail(Throwable error) {
        try {
            OptimizerBridge.failure(MODULE, error);
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
        }
    }

    private static final class Snapshot {
        private final ItemStack[] equipment = new ItemStack[SLOTS.length];
        private int lastVerifiedTick;

        private static Snapshot capture(EntityLivingBase entity) {
            Snapshot value = new Snapshot();
            value.update(entity);
            value.lastVerifiedTick = entity.ticksExisted;
            return value;
        }

        private boolean matches(EntityLivingBase entity) {
            for (int index = 0; index < SLOTS.length; index++) {
                ItemStack current = normalized(entity.getItemStackFromSlot(SLOTS[index]));
                if (!ItemStack.areItemStacksEqual(equipment[index], current)) return false;
            }
            return true;
        }

        private void update(EntityLivingBase entity) {
            for (int index = 0; index < SLOTS.length; index++) {
                ItemStack current = normalized(entity.getItemStackFromSlot(SLOTS[index]));
                equipment[index] = current.isEmpty() ? ItemStack.EMPTY : current.copy();
            }
        }

        private static ItemStack normalized(ItemStack stack) {
            return stack == null ? ItemStack.EMPTY : stack;
        }
    }
}
