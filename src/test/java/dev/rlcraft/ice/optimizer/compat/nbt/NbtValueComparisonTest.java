package dev.rlcraft.ice.optimizer.compat.nbt;

import static org.junit.Assert.*;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import net.minecraft.nbt.*;
import org.junit.Test;

public class NbtValueComparisonTest {
    @Test public void copiedArrayMapUsesLinearValueComparisonsAndNoKeyProbes() {
        for (int size : new int[] { 1, 4, 16, 64, 256 }) {
            CountingMap left = new CountingMap();
            CountingMap right = new CountingMap();
            for (int i = 0; i < size; i++) {
                left.put("key" + i, new CountingInt(i));
                right.put("key" + i, new CountingInt(i));
            }
            CountingInt.comparisons = 0;
            assertTrue(NbtValueComparison.mapsEqual(left, right));
            assertEquals(size, CountingInt.comparisons);
            assertEquals(0, left.probes + right.probes);
        }
    }

    @Test public void differentInsertionOrdersAndMissingKeysRemainOrderIndependent() {
        Map<String, NBTBase> a = new Object2ObjectArrayMap<String, NBTBase>();
        Map<String, NBTBase> b = new LinkedHashMap<String, NBTBase>();
        for (int i = 0; i < 24; i++) a.put("key" + i, new NBTTagInt(i));
        for (int i = 23; i >= 0; i--) b.put("key" + i, new NBTTagInt(i));
        assertTrue(NbtValueComparison.mapsEqual(a, b));
        b.remove("key3"); b.put("different", new NBTTagInt(3));
        assertFalse(NbtValueComparison.mapsEqual(a, b));
    }

    @Test public void randomNestedValuesTypesAndInPlaceChangesMatchOriginalEquality() {
        Random random = new Random(0x1ce23L);
        for (int pass = 0; pass < 200; pass++) {
            NBTTagCompound compound = new NBTTagCompound();
            compound.setLong("long", random.nextLong());
            compound.setByteArray("bytes", new byte[] { (byte) random.nextInt(), 1, 2 });
            NBTTagList list = new NBTTagList();
            NBTTagCompound child = new NBTTagCompound();
            child.setString("name", "nested" + pass);
            list.appendTag(child);
            compound.setTag("list", list);
            NBTTagCompound copied = compound.copy();
            assertEquals(compound.equals(copied), NbtValueComparison.equal(compound, copied));
            child.setInteger("changed", 9);
            assertEquals(compound.equals(copied), NbtValueComparison.equal(compound, copied));
            assertFalse(NbtValueComparison.equal(compound, copied));
        }
        assertFalse(NbtValueComparison.equal(new NBTTagInt(1), new NBTTagByte((byte) 1)));
        assertEquals(new NBTTagFloat(Float.NaN).equals(new NBTTagFloat(Float.NaN)),
            NbtValueComparison.equal(new NBTTagFloat(Float.NaN), new NBTTagFloat(Float.NaN)));
        assertEquals(new NBTTagDouble(-0D).equals(new NBTTagDouble(0D)),
            NbtValueComparison.equal(new NBTTagDouble(-0D), new NBTTagDouble(0D)));
        NBTTagList a = new NBTTagList(); a.appendTag(new NBTTagInt(1)); a.appendTag(new NBTTagInt(2));
        NBTTagList b = new NBTTagList(); b.appendTag(new NBTTagInt(2)); b.appendTag(new NBTTagInt(1));
        assertFalse(NbtValueComparison.equal(a, b));
        NBTTagFloat nan = new NBTTagFloat(Float.NaN);
        NBTTagList nanA = new NBTTagList(); nanA.appendTag(nan);
        NBTTagList nanB = new NBTTagList(); nanB.appendTag(nan);
        assertEquals(nanA.equals(nanB), NbtValueComparison.equal(nanA, nanB));
    }

    private static final class CountingMap extends Object2ObjectArrayMap<String, NBTBase> {
        int probes;
        @Override public NBTBase get(Object key) { probes++; return super.get(key); }
        @Override public boolean containsKey(Object key) { probes++; return super.containsKey(key); }
    }
    private static final class CountingInt extends NBTTagInt {
        static int comparisons;
        CountingInt(int value) { super(value); }
        @Override public boolean equals(Object other) { comparisons++; return super.equals(other); }
    }
}
