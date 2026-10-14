package dev.rlcraft.ice.optimizer.compat.entity;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Item;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import dev.rlcraft.ice.optimizer.compat.nbt.NbtValueComparison;

/** Equipment snapshots for Quality Tools' seven-tick attribute refresh. */
public final class QualityToolsAttributeBridge {
    private static final int MODULE = OptimizationModule.QUALITY_TOOLS_ATTRIBUTES.ordinal();
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
                if (previous.updateChanged(entity)) {
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

    /** QualityTools needs only the armor stack, not a serialized copy of the horse. */
    public static ItemStack horseArmor(EntityHorse horse) {
        if (OptimizerBridge.isEnabled(MODULE)) return ((HorseArmorAccess) horse).ice$horseArmor();
        NBTTagCompound saved = horse.writeToNBT(new NBTTagCompound());
        return saved.hasKey("ArmorItem", 10)
            ? new ItemStack(saved.getCompoundTag("ArmorItem")) : ItemStack.EMPTY;
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
        private final QualityInput[] equipment = new QualityInput[SLOTS.length];
        private int lastVerifiedTick;

        private static Snapshot capture(EntityLivingBase entity) {
            Snapshot value = new Snapshot();
            value.updateChanged(entity);
            value.lastVerifiedTick = entity.ticksExisted;
            return value;
        }

        private boolean updateChanged(EntityLivingBase entity) {
            boolean changed = false;
            for (int index = 0; index < SLOTS.length; index++) {
                ItemStack current = normalized(entity.getItemStackFromSlot(SLOTS[index]));
                QualityInput previous = equipment[index];
                if (previous == null || !previous.matches(current)) {
                    equipment[index] = new QualityInput(current);
                    changed = true;
                }
            }
            return changed;
        }

        private static ItemStack normalized(ItemStack stack) {
            return stack == null ? ItemStack.EMPTY : stack;
        }
    }

    private static final class QualityInput {
        private final boolean empty;
        private final Item item;
        private final int count;
        private final int damage;
        private final NBTBase slots;
        private final NBTBase modifiers;
        private final NBTBase equipmentModifiers;

        private QualityInput(ItemStack stack) {
            empty = stack.isEmpty();
            item = stack.getItem();
            count = stack.getCount();
            damage = stack.getItemDamage();
            NBTTagCompound quality = quality(stack);
            slots = copy(quality == null ? null : quality.getTag("Slots"));
            modifiers = copy(quality == null ? null : quality.getTag("AttributeModifiers"));
            equipmentModifiers = copy(stack.getTagCompound() == null ? null
                : stack.getTagCompound().getTag("AttributeModifiers"));
        }

        private boolean matches(ItemStack stack) {
            if (empty != stack.isEmpty() || item != stack.getItem()
                || count != stack.getCount() || damage != stack.getItemDamage()) return false;
            NBTTagCompound quality = quality(stack);
            return NbtValueComparison.equal(slots, quality == null ? null : quality.getTag("Slots"))
                && NbtValueComparison.equal(modifiers, quality == null ? null : quality.getTag("AttributeModifiers"))
                && NbtValueComparison.equal(equipmentModifiers, stack.getTagCompound() == null ? null
                    : stack.getTagCompound().getTag("AttributeModifiers"));
        }

        private static NBTTagCompound quality(ItemStack stack) {
            NBTTagCompound root = stack.getTagCompound();
            NBTBase value = root == null ? null : root.getTag("Quality");
            return value instanceof NBTTagCompound ? (NBTTagCompound) value : null;
        }

        private static NBTBase copy(NBTBase value) { return value == null ? null : value.copy(); }
    }
}
