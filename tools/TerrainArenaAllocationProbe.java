package dev.rlcraft.ice.benchmark;

import dev.rlcraft.ice.optimizer.render.arena.ArenaRange;
import dev.rlcraft.ice.optimizer.render.arena.GpuArenaAllocator;
import java.util.Arrays;

/** CPU-only adverse fragmentation probe; reports allocator cost, never live FPS. */
public final class TerrainArenaAllocationProbe {
    private static volatile long sink;

    public static void main(String[] args) {
        boolean failedOnly = args.length > 0 && args[0].equals("failed");
        boolean successOnly = args.length > 0 && args[0].equals("success");
        long[] failed = new long[11], mixed = new long[11];
        for (int i = -5; i < failed.length; i++) {
            long a = successOnly ? 0 : failedRequests();
            long b = failedOnly ? 0 : successfulReuse();
            if (i >= 0) { failed[i] = a; mixed[i] = b; }
        }
        Arrays.sort(failed); Arrays.sort(mixed);
        System.out.println("fragmented_failed_allocation_median_ns=" + failed[failed.length / 2] / 20000.0);
        System.out.println("mixed_free_reuse_median_ns=" + mixed[mixed.length / 2] / 20000.0);
        System.out.println("offset_digest=" + sink);
        System.out.println("scope=CPU synthetic allocator trace; not a live FPS estimate");
    }

    private static long failedRequests() {
        int count = 2048;
        long page = count * 64L;
        GpuArenaAllocator arena = new GpuArenaAllocator(page, page, 4L, 1L, null, page);
        ArenaRange[] ranges = new ArenaRange[count];
        for (int i = 0; i < count; i++) ranges[i] = arena.allocate(64L);
        for (int i = 0; i < count; i += 2) arena.free(ranges[i]);
        long[] capacities = { page };
        long start = System.nanoTime();
        for (int i = 0; i < 20000; i++) {
            if (arena.allocatePaged(128L + (i & 7) * 4L, capacities, null) != null)
                throw new AssertionError("fragment unexpectedly fit");
        }
        long elapsed = System.nanoTime() - start;
        arena.close();
        return elapsed;
    }

    private static long successfulReuse() {
        int count = 1024;
        long page = count * 64L;
        GpuArenaAllocator arena = new GpuArenaAllocator(page, page, 4L, 1L, null, page);
        ArenaRange[] ranges = new ArenaRange[count];
        for (int i = 0; i < count; i++) ranges[i] = arena.allocate(64L);
        long[] capacities = { page };
        long digest = 0;
        long start = System.nanoTime();
        for (int i = 0; i < 20000; i++) {
            int slot = (i * 137) & (count - 1);
            arena.free(ranges[slot]);
            ranges[slot] = arena.allocatePaged(64L, capacities, null);
            if (ranges[slot] == null) throw new AssertionError("reusable slot lost");
            digest += ranges[slot].getOffset();
        }
        long elapsed = System.nanoTime() - start;
        sink = digest;
        arena.close();
        return elapsed;
    }
}
