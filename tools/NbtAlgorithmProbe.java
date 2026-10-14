package dev.rlcraft.ice.optimizer.compat.nbt;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.*;
import mirror.normalasm.api.datastructures.NormalTagMap;
import net.minecraft.nbt.*;

/** Real NormalASM maps and instrumented game compounds; never loaded by the mod. */
public final class NbtAlgorithmProbe {
    private static volatile int sink;
    private static Field mapField;
    public static void main(String[] arguments) throws Exception {
        for (Field field : NBTTagCompound.class.getDeclaredFields()) if (field.getType() == Map.class) mapField = field;
        mapField.setAccessible(true);
        if (!(new NBTTagCompound() instanceof NbtCompoundAccess)) throw new AssertionError("production accessor not installed");
        Random random = new Random(23);
        for (int test = 0; test < 500; test++) {
            NBTTagCompound a = fixture(4 + random.nextInt(24), false);
            NBTTagCompound b = fixture(a.getSize(), (test & 1) != 0);
            assertEquivalent(a, b);
            ((NBTTagCompound) ((NBTTagList) b.getTag("key0")).get(0)).setDouble("amount", test + 1);
            assertEquivalent(a, b);
            byte[] before = encode(a, false);
            byte[] after = encode(a, true);
            if (!Arrays.equals(before, after)) throw new AssertionError("NBT encoding changed");
        }
        System.out.println("instrumented nested equality/mutation/order/encoding: 1500 checks passed");
        for (int size : new int[] { 4, 16, 32 }) benchmark(size);
    }

    private static NBTTagCompound fixture(int size, boolean reverse) throws Exception {
        NBTTagCompound result = new NBTTagCompound();
        mapField.set(result, new NormalTagMap<String, NBTBase>(64, false, true));
        for (int step = 0; step < size; step++) {
            int i = reverse ? size - step - 1 : step;
            NBTTagCompound child = new NBTTagCompound();
            mapField.set(child, new NormalTagMap<String, NBTBase>(64, false, true));
            child.setDouble("amount", i);
            child.setString("name", "attribute" + i);
            child.setInteger("operation", i % 3);
            child.setLong("UUIDMost", i);
            child.setLong("UUIDLeast", i * 17L);
            NBTTagList list = new NBTTagList(); list.appendTag(child);
            result.setTag("key" + i, list);
        }
        return result;
    }

    private static void assertEquivalent(NBTTagCompound a, NBTTagCompound b) {
        if (a.equals(b) != NbtValueComparison.equal(a, b)) throw new AssertionError("equality changed");
    }

    private static byte[] encode(NBTTagCompound value, boolean optimized) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        if (optimized) NbtEncodingBridge.writeEntries((NbtCompoundAccess) value, output);
        else ((NbtCompoundAccess) value).ice$originalNbtWrite(output);
        return bytes.toByteArray();
    }

    private static void benchmark(int size) throws Exception {
        NBTTagCompound a = fixture(size, false), b = fixture(size, false);
        run(a, b, false, 10000); run(a, b, true, 10000);
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        long[] originalNanos = new long[7], fastNanos = new long[7];
        long[] originalBytes = new long[7], fastBytes = new long[7];
        int repetitions = 30000;
        for (int sample = 0; sample < 7; sample++) {
            for (int turn = 0; turn < 2; turn++) {
                boolean fast = ((sample + turn) & 1) != 0;
                long bytes = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
                long start = System.nanoTime();
                run(a, b, fast, repetitions);
                long elapsed = System.nanoTime() - start;
                long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - bytes;
                (fast ? fastNanos : originalNanos)[sample] = elapsed / repetitions;
                (fast ? fastBytes : originalBytes)[sample] = allocated / repetitions;
            }
        }
        Arrays.sort(originalNanos); Arrays.sort(fastNanos); Arrays.sort(originalBytes); Arrays.sort(fastBytes);
        System.out.println("nested keys=" + size + " original_ns=" + originalNanos[3]
            + " optimized_ns=" + fastNanos[3] + " original_bytes=" + originalBytes[3]
            + " optimized_bytes=" + fastBytes[3]);
    }

    private static void run(NBTTagCompound a, NBTTagCompound b, boolean fast, int count) {
        int equal = 0;
        for (int i = 0; i < count; i++) if (fast ? NbtValueComparison.equal(a, b) : a.equals(b)) equal++;
        sink = equal;
    }
}
