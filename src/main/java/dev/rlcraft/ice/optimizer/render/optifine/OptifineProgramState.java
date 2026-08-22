package dev.rlcraft.ice.optimizer.render.optifine;

import java.util.Arrays;

/** Exact non-owning description of one OptiFine Program activation. */
public final class OptifineProgramState {
    private final String name;
    private final String stage;
    private final int programId;
    private final int framebuffer;
    private final ShaderFramebufferState framebufferState;
    private final int[] drawBuffers;
    private final int compositeMipmap;
    private final int instanceCount;
    private final AlphaState alpha;
    private final BlendState blend;
    private final RenderScaleState renderScale;

    public OptifineProgramState(String name, String stage, int programId,
                                int framebuffer, int[] drawBuffers,
                                int compositeMipmap, int instanceCount,
                                AlphaState alpha, BlendState blend,
                                RenderScaleState renderScale) {
        this(name, stage, programId, framebuffer, null, drawBuffers,
            compositeMipmap, instanceCount, alpha, blend, renderScale);
    }

    public OptifineProgramState(String name, String stage, int programId,
                                int framebuffer,
                                ShaderFramebufferState framebufferState,
                                int[] drawBuffers, int compositeMipmap,
                                int instanceCount, AlphaState alpha,
                                BlendState blend, RenderScaleState renderScale) {
        if (name == null || name.isEmpty() || name.length() > 256
            || stage == null || stage.length() > 64 || programId < 0
            || framebuffer < -1 || drawBuffers == null
            || drawBuffers.length > 16 || instanceCount < 0) {
            throw new IllegalArgumentException("OptiFine program state");
        }
        this.name = name;
        this.stage = stage;
        this.programId = programId;
        this.framebuffer = framebuffer;
        this.framebufferState = framebufferState;
        this.drawBuffers = drawBuffers.clone();
        this.compositeMipmap = compositeMipmap;
        this.instanceCount = instanceCount;
        this.alpha = alpha;
        this.blend = blend;
        this.renderScale = renderScale;
    }

    public String getName() { return name; }
    public String getStage() { return stage; }
    public int getProgramId() { return programId; }
    public int getFramebuffer() { return framebuffer; }
    public ShaderFramebufferState getFramebufferState() { return framebufferState; }
    public int[] getDrawBuffers() { return drawBuffers.clone(); }
    public int getCompositeMipmap() { return compositeMipmap; }
    public int getInstanceCount() { return instanceCount; }
    public AlphaState getAlpha() { return alpha; }
    public BlendState getBlend() { return blend; }
    public RenderScaleState getRenderScale() { return renderScale; }

    /**
     * Exact logical activation identity used when a retained GL program is
     * re-entered.  The two GL program names and the concrete FBO name are
     * deliberately excluded: a retained program has a different name and
     * OptiFine may recreate an otherwise identical FBO.  Every observable
     * pass/output property remains part of the comparison.
     */
    public boolean isLogicalActivationEquivalent(OptifineProgramState other) {
        return other != null && name.equals(other.name) && stage.equals(other.stage)
            && framebufferState != null && other.framebufferState != null
            && framebufferState.equals(other.framebufferState)
            && Arrays.equals(drawBuffers, other.drawBuffers)
            && compositeMipmap == other.compositeMipmap
            && instanceCount == other.instanceCount
            && equal(alpha, other.alpha) && equal(blend, other.blend)
            && equal(renderScale, other.renderScale);
    }

    /** Returns the same logical activation with a different non-owning GL name. */
    public OptifineProgramState withProgramId(int replacementProgramId) {
        return new OptifineProgramState(name, stage, replacementProgramId,
            framebuffer, framebufferState, drawBuffers, compositeMipmap,
            instanceCount, alpha, blend, renderScale);
    }

    @Override public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof OptifineProgramState)) return false;
        OptifineProgramState other = (OptifineProgramState) value;
        return programId == other.programId && framebuffer == other.framebuffer
            && compositeMipmap == other.compositeMipmap
            && instanceCount == other.instanceCount && name.equals(other.name)
            && stage.equals(other.stage) && Arrays.equals(drawBuffers, other.drawBuffers)
            && equal(framebufferState, other.framebufferState)
            && equal(alpha, other.alpha) && equal(blend, other.blend)
            && equal(renderScale, other.renderScale);
    }

    @Override public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + stage.hashCode();
        result = 31 * result + programId;
        result = 31 * result + framebuffer;
        result = 31 * result + (framebufferState == null ? 0
            : framebufferState.hashCode());
        result = 31 * result + Arrays.hashCode(drawBuffers);
        result = 31 * result + compositeMipmap;
        result = 31 * result + instanceCount;
        result = 31 * result + (alpha == null ? 0 : alpha.hashCode());
        result = 31 * result + (blend == null ? 0 : blend.hashCode());
        return 31 * result + (renderScale == null ? 0 : renderScale.hashCode());
    }

    private static boolean equal(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    public static final class AlphaState {
        private final boolean enabled;
        private final int function;
        private final int referenceBits;

        public AlphaState(boolean enabled, int function, float reference) {
            if (!finite(reference)) throw new IllegalArgumentException("alpha reference");
            this.enabled = enabled;
            this.function = function;
            this.referenceBits = Float.floatToRawIntBits(reference);
        }
        public boolean isEnabled() { return enabled; }
        public int getFunction() { return function; }
        public float getReference() { return Float.intBitsToFloat(referenceBits); }
        @Override public boolean equals(Object value) {
            if (!(value instanceof AlphaState)) return false;
            AlphaState other = (AlphaState) value;
            return enabled == other.enabled && function == other.function
                && referenceBits == other.referenceBits;
        }
        @Override public int hashCode() {
            return 31 * (31 * (enabled ? 1 : 0) + function) + referenceBits;
        }
    }

    public static final class BlendState {
        private final boolean enabled;
        private final int sourceRgb;
        private final int destinationRgb;
        private final int sourceAlpha;
        private final int destinationAlpha;

        public BlendState(boolean enabled, int sourceRgb, int destinationRgb,
                          int sourceAlpha, int destinationAlpha) {
            this.enabled = enabled;
            this.sourceRgb = sourceRgb;
            this.destinationRgb = destinationRgb;
            this.sourceAlpha = sourceAlpha;
            this.destinationAlpha = destinationAlpha;
        }
        public boolean isEnabled() { return enabled; }
        public int getSourceRgb() { return sourceRgb; }
        public int getDestinationRgb() { return destinationRgb; }
        public int getSourceAlpha() { return sourceAlpha; }
        public int getDestinationAlpha() { return destinationAlpha; }
        @Override public boolean equals(Object value) {
            if (!(value instanceof BlendState)) return false;
            BlendState other = (BlendState) value;
            return enabled == other.enabled && sourceRgb == other.sourceRgb
                && destinationRgb == other.destinationRgb
                && sourceAlpha == other.sourceAlpha
                && destinationAlpha == other.destinationAlpha;
        }
        @Override public int hashCode() {
            int result = enabled ? 1 : 0;
            result = 31 * result + sourceRgb;
            result = 31 * result + destinationRgb;
            result = 31 * result + sourceAlpha;
            return 31 * result + destinationAlpha;
        }
    }

    public static final class RenderScaleState {
        private final int scaleBits;
        private final int offsetXBits;
        private final int offsetYBits;

        public RenderScaleState(float scale, float offsetX, float offsetY) {
            if (!finite(scale) || !finite(offsetX) || !finite(offsetY) || scale <= 0.0F) {
                throw new IllegalArgumentException("render scale");
            }
            scaleBits = Float.floatToRawIntBits(scale);
            offsetXBits = Float.floatToRawIntBits(offsetX);
            offsetYBits = Float.floatToRawIntBits(offsetY);
        }
        public float getScale() { return Float.intBitsToFloat(scaleBits); }
        public float getOffsetX() { return Float.intBitsToFloat(offsetXBits); }
        public float getOffsetY() { return Float.intBitsToFloat(offsetYBits); }
        @Override public boolean equals(Object value) {
            if (!(value instanceof RenderScaleState)) return false;
            RenderScaleState other = (RenderScaleState) value;
            return scaleBits == other.scaleBits && offsetXBits == other.offsetXBits
                && offsetYBits == other.offsetYBits;
        }
        @Override public int hashCode() {
            return 31 * (31 * scaleBits + offsetXBits) + offsetYBits;
        }
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
