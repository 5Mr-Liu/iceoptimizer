package dev.rlcraft.ice.optimizer.compat.nbt;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.INBTSerializable;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.items.ItemStackHandler;

/** Compares reviewed serialized projections without constructing their NBT trees. */
public final class CapabilityStateBridge {
    public static final int UNKNOWN = -1;
    public static final int ENERGY = 1;
    public static final int QUIVER = 2;
    private static final int MODULE = OptimizationModule.CAPABILITY_STATE_NBT.ordinal();
    private static final NBTTagInt[] INVENTORY_INTEGERS = inventoryIntegers();

    private CapabilityStateBridge() { }

    public static boolean useSnapshots() {
        return OptimizerBridge.isEnabled(MODULE);
    }

    public static void writeEnergy(NBTTagCompound output, String key, int value, CapabilityStateScalarAccess owner) {
        if (!useSnapshots()) {
            output.setInteger(key, value);
            return;
        }
        NBTTagInt scalar = owner.ice$stateScalar();
        if (scalar == null || scalar.getInt() != value) {
            scalar = new NBTTagInt(value);
            owner.ice$stateScalar(scalar);
        }
        output.setTag(key, scalar);
    }

    /** Only Slot/Size leaves are shared; the original writer still owns its containers. */
    public static void writeInventoryInteger(NBTTagCompound output, String key, int value) {
        if (value >= 0 && value < INVENTORY_INTEGERS.length) output.setTag(key, INVENTORY_INTEGERS[value]);
        else output.setInteger(key, value);
    }

    private static NBTTagInt[] inventoryIntegers() {
        NBTTagInt[] values = new NBTTagInt[64];
        for (int i = 0; i < values.length; i++) values[i] = new NBTTagInt(i);
        return values;
    }

    /** Returns UNKNOWN before doing any provider work when either side is mixed. */
    public static int compare(Object left, Object right) {
        if (!OptimizerBridge.isEnabled(MODULE)
            || !(left instanceof CapabilityStateDispatcherAccess)
            || !(right instanceof CapabilityStateDispatcherAccess)) return UNKNOWN;
        CapabilityStateDispatcherAccess a = (CapabilityStateDispatcherAccess) left;
        CapabilityStateDispatcherAccess b = (CapabilityStateDispatcherAccess) right;
        if (!a.ice$plainStateDispatcher() || !b.ice$plainStateDispatcher()) return UNKNOWN;
        INBTSerializable<?>[] leftWriters = a.ice$stateWriters();
        INBTSerializable<?>[] rightWriters = b.ice$stateWriters();
        String[] leftNames = a.ice$stateNames();
        String[] rightNames = b.ice$stateNames();
        if (!supported(leftWriters, leftNames) || !supported(rightWriters, rightNames)) return UNKNOWN;

        // Quiver serialization also initializes its owning stack's missing tag.
        // Retain that effect for every writer, in Forge's left-then-right order.
        prepare(leftWriters);
        prepare(rightWriters);
        for (int i = 0; i < leftWriters.length; i++) {
            if (last(leftNames, leftNames[i]) != i) continue;
            int j = last(rightNames, leftNames[i]);
            if (j < 0 || !equalState((CapabilityStateProviderAccess) leftWriters[i],
                (CapabilityStateProviderAccess) rightWriters[j])) return 0;
        }
        for (String name : rightNames) if (last(leftNames, name) < 0) return 0;
        return 1;
    }

    private static boolean supported(INBTSerializable<?>[] writers, String[] names) {
        if (writers == null || names == null || writers.length != names.length) return false;
        for (int i = 0; i < writers.length; i++) {
            if (names[i] == null || !(writers[i] instanceof CapabilityStateProviderAccess)) return false;
            CapabilityStateProviderAccess state = (CapabilityStateProviderAccess) writers[i];
            int kind = state.ice$stateKind();
            if (kind == ENERGY) continue;
            if (kind != QUIVER) return false;
            ItemStackHandler inventory = (ItemStackHandler) state.ice$statePayload();
            for (int slot = 0; slot < inventory.getSlots(); slot++) {
                ItemStack stack = inventory.getStackInSlot(slot);
                if (stack.isEmpty()) continue;
                if (!((Object) stack instanceof CapabilityStateStackAccess)) return false;
                CapabilityStateStackAccess stackState = (CapabilityStateStackAccess) (Object) stack;
                Object caps = stackState.ice$stateCapabilities();
                if (caps == null) {
                    // NormalASM writes this deferred payload even before its
                    // dispatcher exists. Let that original path handle it.
                    if (stackState.ice$statePendingNbt() != null) return false;
                    continue;
                }
                if (!(caps instanceof CapabilityStateDispatcherAccess)) return false;
                CapabilityStateDispatcherAccess access = (CapabilityStateDispatcherAccess) caps;
                if (!access.ice$plainStateDispatcher() || access.ice$stateWriters().length != 0) return false;
            }
        }
        return true;
    }

    private static void prepare(INBTSerializable<?>[] writers) {
        for (INBTSerializable<?> writer : writers) {
            CapabilityStateProviderAccess state = (CapabilityStateProviderAccess) writer;
            if (state.ice$stateKind() == QUIVER) {
                ItemStack owner = (ItemStack) state.ice$stateOwner();
                if (!owner.hasTagCompound()) owner.setTagCompound(new NBTTagCompound());
            }
        }
    }

    private static boolean equalState(CapabilityStateProviderAccess left, CapabilityStateProviderAccess right) {
        int kind = left.ice$stateKind();
        if (kind != right.ice$stateKind()) return false;
        if (kind == ENERGY) {
            return ((IEnergyStorage) left.ice$statePayload()).getEnergyStored()
                == ((IEnergyStorage) right.ice$statePayload()).getEnergyStored();
        }
        ItemStackHandler a = (ItemStackHandler) left.ice$statePayload();
        ItemStackHandler b = (ItemStackHandler) right.ice$statePayload();
        if (a.getSlots() != b.getSlots()) return false;
        for (int slot = 0; slot < a.getSlots(); slot++) {
            ItemStack x = a.getStackInSlot(slot);
            ItemStack y = b.getStackInSlot(slot);
            if (x.isEmpty() || y.isEmpty()) {
                if (x.isEmpty() != y.isEmpty()) return false;
                continue;
            }
            CapabilityStateStackAccess xa = (CapabilityStateStackAccess) (Object) x;
            CapabilityStateStackAccess ya = (CapabilityStateStackAccess) (Object) y;
            // Match the on-disk projection, including its byte/short casts.
            if ((byte) xa.ice$stateCount() != (byte) ya.ice$stateCount()
                || (short) xa.ice$stateDamage() != (short) ya.ice$stateDamage()) return false;
            Item xi = xa.ice$stateItem();
            Item yi = ya.ice$stateItem();
            if (xi != yi && !itemName(xi).equals(itemName(yi))) return false;
            NBTTagCompound xt = x.getTagCompound();
            NBTTagCompound yt = y.getTagCompound();
            if (xt == null ? yt != null : !xt.equals(yt)) return false;
        }
        return true;
    }

    private static String itemName(Item item) {
        ResourceLocation name = Item.REGISTRY.getNameForObject(item);
        return name == null ? "minecraft:air" : name.toString();
    }

    private static int last(String[] names, String name) {
        for (int i = names.length - 1; i >= 0; i--) if (name.equals(names[i])) return i;
        return -1;
    }
}
