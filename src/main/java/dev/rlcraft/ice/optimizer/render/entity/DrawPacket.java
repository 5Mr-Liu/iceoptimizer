package dev.rlcraft.ice.optimizer.render.entity;

import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyMatrixStateTracker;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;

/** Immutable result of one already-executed, certified Java draw site. */
public final class DrawPacket {
    private final long meshHandle;
    private final float[] partMatrices;
    private final float[] materialParameters;
    private final int redBits;
    private final int greenBits;
    private final int blueBits;
    private final int alphaBits;
    private final boolean capturedCurrentColor;
    private final RenderStateKey state;
    private final FrameStamp generation;
    private final long eventScope;
    private final long sequence;
    private final boolean transparent;

    public DrawPacket(long meshHandle, float[] partMatrices,
                      float[] materialParameters, RenderStateKey state,
                      FrameStamp generation, long eventScope, long sequence,
                      boolean transparent) {
        this(meshHandle, partMatrices, materialParameters, state, generation,
            eventScope, sequence, transparent, false, 0.0F, 0.0F, 0.0F,
            0.0F);
    }

    private DrawPacket(long meshHandle, float[] partMatrices,
                       float[] materialParameters, RenderStateKey state,
                       FrameStamp generation, long eventScope, long sequence,
                       boolean transparent, boolean captureOwned,
                       float red, float green, float blue, float alpha) {
        if (meshHandle <= 0L || partMatrices == null || partMatrices.length == 0
            || partMatrices.length > 16 * 512 || (partMatrices.length & 15) != 0
            || !captureOwned && (materialParameters == null
                || materialParameters.length > 256)
            || state == null || generation == null || eventScope < 0L
            || sequence < 0L) throw new IllegalArgumentException("draw packet");
        this.meshHandle = meshHandle;
        this.partMatrices = captureOwned ? partMatrices : partMatrices.clone();
        this.materialParameters = captureOwned ? null
            : materialParameters.clone();
        this.redBits = Float.floatToRawIntBits(red);
        this.greenBits = Float.floatToRawIntBits(green);
        this.blueBits = Float.floatToRawIntBits(blue);
        this.alphaBits = Float.floatToRawIntBits(alpha);
        this.capturedCurrentColor = captureOwned;
        this.state = state;
        this.generation = generation;
        this.eventScope = eventScope;
        this.sequence = sequence;
        this.transparent = transparent;
    }

    /**
     * Captures the tracker-owned model-view snapshot directly into this
     * immutable packet and stores the four current-color values in fields.
     * This is the production path and avoids three redundant per-draw arrays.
     */
    public static DrawPacket captureCurrentModelView(
        long meshHandle, float red, float green, float blue, float alpha,
        RenderStateKey state, FrameStamp generation, long eventScope,
        long sequence, boolean transparent) {
        float[] matrix = EarlyMatrixStateTracker.modelView();
        if (matrix == null) return null;
        return new DrawPacket(meshHandle, matrix, null, state, generation,
            eventScope, sequence, transparent, true, red, green, blue, alpha);
    }

    public long getMeshHandle() { return meshHandle; }
    public float[] getPartMatrices() { return partMatrices.clone(); }
    public float[] getMaterialParameters() {
        return capturedCurrentColor
            ? new float[] { Float.intBitsToFloat(redBits),
                Float.intBitsToFloat(greenBits), Float.intBitsToFloat(blueBits),
                Float.intBitsToFloat(alphaBits) }
            : materialParameters.clone();
    }
    public RenderStateKey getState() { return state; }
    public FrameStamp getGeneration() { return generation; }
    public long getEventScope() { return eventScope; }
    public long getSequence() { return sequence; }
    public boolean isTransparent() { return transparent; }

    float[] rawPartMatrices() { return partMatrices; }
    float[] rawMaterialParameters() { return materialParameters; }

    boolean matchesMaterial(EarlyGlStateTracker.Snapshot state) {
        if (state == null) return false;
        if (capturedCurrentColor) {
            return redBits == Float.floatToRawIntBits(state.getRed())
                && greenBits == Float.floatToRawIntBits(state.getGreen())
                && blueBits == Float.floatToRawIntBits(state.getBlue())
                && alphaBits == Float.floatToRawIntBits(state.getAlpha());
        }
        return materialParameters != null && materialParameters.length == 4
            && Float.floatToRawIntBits(materialParameters[0])
                == Float.floatToRawIntBits(state.getRed())
            && Float.floatToRawIntBits(materialParameters[1])
                == Float.floatToRawIntBits(state.getGreen())
            && Float.floatToRawIntBits(materialParameters[2])
                == Float.floatToRawIntBits(state.getBlue())
            && Float.floatToRawIntBits(materialParameters[3])
                == Float.floatToRawIntBits(state.getAlpha());
    }
}
