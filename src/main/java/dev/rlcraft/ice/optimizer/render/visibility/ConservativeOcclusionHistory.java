package dev.rlcraft.ice.optimizer.render.visibility;

/** Generation-scoped delayed HZB history; key mismatch always draws. */
public final class ConservativeOcclusionHistory {
    private HzbHistoryKey key;
    private ConservativeHzb hzb;
    private long invalidations;

    public void publish(HzbHistoryKey nextKey, ConservativeHzb nextHzb) {
        if (nextKey == null || nextHzb == null || !nextKey.isStandardDepth()) {
            if (nextHzb != null && nextHzb != hzb) nextHzb.close();
            invalidate();
            return;
        }
        ConservativeHzb previous = hzb;
        key = nextKey;
        hzb = nextHzb;
        if (previous != null && previous != nextHzb) previous.close();
    }

    public OcclusionResult test(HzbHistoryKey current, boolean opaquePass,
                                int minX, int minY, int maxX, int maxY,
                                float nearestDepth, float epsilon) {
        if (!opaquePass || current == null || key == null || !key.equals(current)
            || hzb == null) return OcclusionResult.UNKNOWN;
        return hzb.test(minX, minY, maxX, maxY, nearestDepth, epsilon);
    }

    public void invalidate() {
        if (key != null || hzb != null) invalidations++;
        ConservativeHzb previous = hzb;
        key = null;
        hzb = null;
        if (previous != null) previous.close();
    }

    public long getInvalidations() { return invalidations; }
}
