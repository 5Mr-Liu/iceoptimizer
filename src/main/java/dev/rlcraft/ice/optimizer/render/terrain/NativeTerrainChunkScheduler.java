package dev.rlcraft.ice.optimizer.render.terrain;

/** Normal-frame upload slice; task ownership and priority stay with the dispatcher. */
public final class NativeTerrainChunkScheduler {
    private static final long MAX_UPLOAD_SLICE_NANOS = 2_000_000L;

    private NativeTerrainChunkScheduler() { }

    public static long uploadDeadline(long frameDeadline) {
        // Zero retains the dispatcher's one-item mode. Expired deadlines still
        // permit its first item, so loading progresses instead of starving.
        if (frameDeadline == 0L) return 0L;
        return Math.min(frameDeadline, System.nanoTime() + MAX_UPLOAD_SLICE_NANOS);
    }
}
