package dev.rlcraft.ice.optimizer.compat.nbt;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Equality for one comparison only, retaining every nested mutation and tag type. */
public final class NbtValueComparison {
    private NbtValueComparison() { }

    public static boolean equal(NBTBase left, NBTBase right) {
        if (left == null) return right == null;
        if (right == null) return false;
        if (left.getClass() == NBTTagCompound.class && right.getClass() == NBTTagCompound.class
            && left instanceof NbtCompoundAccess && right instanceof NbtCompoundAccess) {
            return mapsEqual(((NbtCompoundAccess) left).ice$tagMap(),
                ((NbtCompoundAccess) right).ice$tagMap());
        }
        if (left.getClass() == NBTTagList.class && right.getClass() == NBTTagList.class) {
            if (left == right) return true;
            NBTTagList a = (NBTTagList) left;
            NBTTagList b = (NBTTagList) right;
            int size = a.tagCount();
            if (size != b.tagCount() || a.getTagType() != b.getTagType()) return false;
            for (int i = 0; i < size; i++) if (!equal(a.get(i), b.get(i))) return false;
            return true;
        }
        // A mod subclass may supply its own equals implementation.
        return left.equals(right);
    }

    static boolean mapsEqual(Map<String, NBTBase> left, Map<String, NBTBase> right) {
        if (left == right) return true;
        if (left.size() != right.size()) return false;
        Iterator<Map.Entry<String, NBTBase>> a = entries(left);
        Iterator<Map.Entry<String, NBTBase>> b = entries(right);
        while (a.hasNext()) {
            Map.Entry<String, NBTBase> x = a.next();
            Map.Entry<String, NBTBase> y = b.next();
            if (!java.util.Objects.equals(x.getKey(), y.getKey())) {
                // Different insertion order is still an equal compound. The usual
                // ItemStack.copy case never needs these per-key lookups.
                return unorderedEqual(left, right);
            }
            if (x.getValue() != y.getValue() && !equal(x.getValue(), y.getValue())) return false;
        }
        return true;
    }

    private static boolean unorderedEqual(Map<String, NBTBase> left, Map<String, NBTBase> right) {
        Iterator<Map.Entry<String, NBTBase>> iterator = entries(left);
        while (iterator.hasNext()) {
            Map.Entry<String, NBTBase> entry = iterator.next();
            NBTBase value = right.get(entry.getKey());
            if (entry.getValue() != value && !equal(entry.getValue(), value)
                || value == null && !right.containsKey(entry.getKey())) return false;
        }
        return true;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    static Iterator<Map.Entry<String, NBTBase>> entries(Map<String, NBTBase> map) {
        Set<Map.Entry<String, NBTBase>> values = map.entrySet();
        // NormalASM forwards the backing map's actual entry set. Its fast iterator
        // reuses the entry wrapper instead of allocating one per tag.
        return values instanceof Object2ObjectMap.FastEntrySet
            ? ((Object2ObjectMap.FastEntrySet) values).fastIterator() : values.iterator();
    }
}
