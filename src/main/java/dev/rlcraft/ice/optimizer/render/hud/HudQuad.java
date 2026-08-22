package dev.rlcraft.ice.optimizer.render.hud;

public final class HudQuad {
    private final float[] xyuv;
    private final int color;
    private final long sequence;

    public HudQuad(float[] xyuv, int color, long sequence) {
        if (xyuv == null || xyuv.length != 16 || sequence < 0L) {
            throw new IllegalArgumentException("HUD quad");
        }
        for (float value : xyuv) {
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                throw new IllegalArgumentException("non-finite HUD vertex");
            }
        }
        this.xyuv = xyuv.clone();
        this.color = color;
        this.sequence = sequence;
    }

    public float[] getXyuv() { return xyuv.clone(); }
    public int getColor() { return color; }
    public long getSequence() { return sequence; }
}
