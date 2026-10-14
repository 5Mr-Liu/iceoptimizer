import dev.rlcraft.ice.optimizer.render.arena.*;
import java.util.*;

/** Local CPU evidence only: shuffled live/freed ranges, alternating order. */
public final class ArenaOwnershipProbe {
    private static volatile long sink;
    public static void main(String[] args) {
        GpuArenaAllocator arena = new GpuArenaAllocator(1L << 20, 1L << 20, 4, 1);
        List<ArenaRange> values = new ArrayList<>();
        for (int i = 0; i < 8192; i++) values.add(arena.allocate(32));
        for (int i = 0; i < values.size(); i += 4) arena.free(values.get(i));
        Collections.shuffle(values, new Random(129));
        ArenaRange[] ranges = values.toArray(new ArenaRange[0]);
        for (ArenaRange range : ranges)
            if (arena.isLive(range) != arena.ownsLiveRange(range)) throw new AssertionError("ownership mismatch");
        for (int i = 0; i < 8; i++) { measure(arena, ranges, false); measure(arena, ranges, true); }
        double[] old = new double[9], newer = new double[9];
        for (int i = 0; i < 9; i++) {
            if ((i & 1) == 0) { old[i] = measure(arena, ranges, false); newer[i] = measure(arena, ranges, true); }
            else { newer[i] = measure(arena, ranges, true); old[i] = measure(arena, ranges, false); }
        }
        Arrays.sort(old); Arrays.sort(newer);
        System.out.println("map_ns_per_check=" + old[4] + " witness_ns_per_check=" + newer[4] + " checksum=" + sink);
        arena.close();
        for (ArenaRange range : ranges) if (arena.ownsLiveRange(range)) throw new AssertionError("stale range after close");
        System.out.println("ownership_semantics_match=true; local CPU measurement, not game FPS");
    }
    private static double measure(GpuArenaAllocator arena, ArenaRange[] ranges, boolean witness) {
        long sum = 0, start = System.nanoTime();
        for (int repeat = 0; repeat < 256; repeat++) for (ArenaRange range : ranges)
            if (witness ? arena.ownsLiveRange(range) : arena.isLive(range)) sum += range.getOffset();
        long elapsed = System.nanoTime() - start;
        sink = sum;
        return elapsed / (256.0 * ranges.length);
    }
}
