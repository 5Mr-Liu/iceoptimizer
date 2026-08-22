package dev.rlcraft.ice.optimizer.render.particle;

/**
 * Exact Fancy Block Particles geometry packet. Sub-draw boundaries retain the
 * original per-particle Tessellator flush order.
 */
public final class FbpParticlePacket {
    private final float[] interleavedVertices;
    private final int strideFloats;
    private final int[] subDrawFirst;
    private final int[] subDrawCount;
    private final ParticleState state;
    private final long sequence;

    public FbpParticlePacket(float[] interleavedVertices, int strideFloats,
                             int[] subDrawFirst, int[] subDrawCount,
                             ParticleState state, long sequence) {
        if (interleavedVertices == null || strideFloats <= 0
            || interleavedVertices.length % strideFloats != 0
            || subDrawFirst == null || subDrawCount == null
            || subDrawFirst.length != subDrawCount.length || state == null
            || sequence < 0L) throw new IllegalArgumentException("FBP packet");
        int vertices = interleavedVertices.length / strideFloats;
        int previousEnd = 0;
        for (int i = 0; i < subDrawFirst.length; i++) {
            long end = (long) subDrawFirst[i] + subDrawCount[i];
            if (subDrawFirst[i] < previousEnd || subDrawCount[i] <= 0 || end > vertices) {
                throw new IllegalArgumentException("FBP sub-draw range");
            }
            previousEnd = (int) end;
        }
        this.interleavedVertices = interleavedVertices.clone();
        this.strideFloats = strideFloats;
        this.subDrawFirst = subDrawFirst.clone();
        this.subDrawCount = subDrawCount.clone();
        this.state = state;
        this.sequence = sequence;
    }

    public float[] getInterleavedVertices() { return interleavedVertices.clone(); }
    public int getStrideFloats() { return strideFloats; }
    public int[] getSubDrawFirst() { return subDrawFirst.clone(); }
    public int[] getSubDrawCount() { return subDrawCount.clone(); }
    public ParticleState getState() { return state; }
    public long getSequence() { return sequence; }
}
