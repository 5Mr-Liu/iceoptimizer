package dev.rlcraft.ice.optimizer.compat.entity;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.util.Collection;
import java.util.Iterator;
import java.util.UUID;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

/** Retains an already applied quality modifier without constructing a discarded NBT result. */
public final class QualityToolsModifierBridge {
    private static final int MODULE = OptimizationModule.QUALITY_TOOLS_ATTRIBUTES.ordinal();
    private static final int MAX_CANDIDATES = 4;
    private static volatile boolean activated;

    private QualityToolsModifierBridge() { }

    /**
     * A hit is exactly the original contains/remove branch. Misses and unusual data
     * continue through QualityTools' original parser and attribute reconciliation.
     * All fields are inspected live; no NBT, ItemStack or mutable modifier is cached.
     */
    public static boolean retainExistingModifier(NBTTagCompound tag,
            Multimap<String, AttributeModifier> unused, NBTTagList source) {
        // The original helper reads the list again for AttributeName. Custom lists
        // may return different values or have side effects on that second read.
        return source != null && source.getClass() == NBTTagList.class
            && retainExistingModifier(tag, unused);
    }

    static boolean retainExistingModifier(NBTTagCompound tag,
            Multimap<String, AttributeModifier> unused) {
        if (!OptimizerBridge.isEnabled(MODULE) || tag == null
            || tag.getClass() != NBTTagCompound.class || unused == null
            || unused.getClass() != HashMultimap.class) return false;
        // Check the total size before creating Guava's wrapped per-key collection.
        // This bounds every scan and leaves large refreshes on the original hash path.
        if (unused.size() > MAX_CANDIDATES) return false;

        NBTBase attribute = tag.getTag("AttributeName");
        if (attribute == null || attribute.getClass() != NBTTagString.class) return false;
        String attributeName = ((NBTTagString) attribute).getString();
        if (!unused.containsKey(attributeName)) return false;
        Collection<AttributeModifier> candidates = unused.get(attributeName);

        NBTBase most = tag.getTag("UUIDMost");
        NBTBase least = tag.getTag("UUIDLeast");
        NBTBase name = tag.getTag("Name");
        NBTBase operation = tag.getTag("Operation");
        NBTBase amount = tag.getTag("Amount");
        // Only the canonical, valid serialization can bypass the original parser.
        // Invalid names/operations must still take its exception/logging path.
        if (most == null || most.getClass() != NBTTagLong.class
            || least == null || least.getClass() != NBTTagLong.class
            || name == null || name.getClass() != NBTTagString.class
            || operation == null || operation.getClass() != NBTTagInt.class
            || amount == null || amount.getClass() != NBTTagDouble.class
            || ((NBTTagString) name).getString().isEmpty()) return false;
        int op = ((NBTTagInt) operation).getInt();
        if (op < 0 || op > 2) return false;

        long mostBits = ((NBTTagLong) most).getLong();
        long leastBits = ((NBTTagLong) least).getLong();
        Iterator<AttributeModifier> iterator = candidates.iterator();
        while (iterator.hasNext()) {
            AttributeModifier existing = iterator.next();
            // AttributeModifier.equals requires exactly the same class, not instanceof.
            if (existing == null || existing.getClass() != AttributeModifier.class) continue;
            UUID id = existing.getID();
            if (id != null && id.getMostSignificantBits() == mostBits
                && id.getLeastSignificantBits() == leastBits) {
                iterator.remove();
                activateOnce();
                return true;
            }
        }
        return false;
    }

    private static void activateOnce() {
        if (activated) return;
        synchronized (QualityToolsModifierBridge.class) {
            if (activated) return;
            OptimizerBridge.activate(MODULE, "Quality Tools 直接保留已应用的品质属性，减少重复 NBT 解析和临时对象");
            activated = true;
        }
    }
}
