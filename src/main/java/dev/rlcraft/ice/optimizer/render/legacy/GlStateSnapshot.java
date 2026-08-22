package dev.rlcraft.ice.optimizer.render.legacy;

import java.util.Arrays;

/** Immutable software-mirrored GL state required at a compatibility boundary. */
public final class GlStateSnapshot {
    final int framebuffer;
    final int program;
    final int vertexArray;
    final int arrayBuffer;
    final int elementBuffer;
    final int activeTextureUnit;
    final int[] textures2d;
    final boolean blend;
    final int blendSrcRgb;
    final int blendDstRgb;
    final int blendSrcAlpha;
    final int blendDstAlpha;
    final int blendEquationRgb;
    final int blendEquationAlpha;
    final boolean depthTest;
    final int depthFunction;
    final boolean depthMask;
    final boolean cull;
    final int cullFace;
    final boolean scissor;
    final boolean stencil;
    final int[] viewport;
    final int[] scissorBox;
    final int colorMaskBits;

    GlStateSnapshot(int framebuffer, int program, int vertexArray, int arrayBuffer,
                    int elementBuffer, int activeTextureUnit, int[] textures2d,
                    boolean blend, int blendSrcRgb, int blendDstRgb,
                    int blendSrcAlpha, int blendDstAlpha, int blendEquationRgb,
                    int blendEquationAlpha, boolean depthTest, int depthFunction,
                    boolean depthMask, boolean cull, int cullFace, boolean scissor,
                    boolean stencil, int[] viewport, int[] scissorBox,
                    int colorMaskBits) {
        this.framebuffer = framebuffer;
        this.program = program;
        this.vertexArray = vertexArray;
        this.arrayBuffer = arrayBuffer;
        this.elementBuffer = elementBuffer;
        this.activeTextureUnit = activeTextureUnit;
        this.textures2d = textures2d.clone();
        this.blend = blend;
        this.blendSrcRgb = blendSrcRgb;
        this.blendDstRgb = blendDstRgb;
        this.blendSrcAlpha = blendSrcAlpha;
        this.blendDstAlpha = blendDstAlpha;
        this.blendEquationRgb = blendEquationRgb;
        this.blendEquationAlpha = blendEquationAlpha;
        this.depthTest = depthTest;
        this.depthFunction = depthFunction;
        this.depthMask = depthMask;
        this.cull = cull;
        this.cullFace = cullFace;
        this.scissor = scissor;
        this.stencil = stencil;
        this.viewport = viewport.clone();
        this.scissorBox = scissorBox.clone();
        this.colorMaskBits = colorMaskBits;
    }

    public int getFramebuffer() { return framebuffer; }
    public int getProgram() { return program; }
    public int getVertexArray() { return vertexArray; }
    public int getArrayBuffer() { return arrayBuffer; }
    public int getElementBuffer() { return elementBuffer; }
    public int getActiveTextureUnit() { return activeTextureUnit; }
    public int[] getTextures2d() { return textures2d.clone(); }
    public boolean isBlend() { return blend; }
    public boolean isDepthTest() { return depthTest; }
    public boolean isDepthMask() { return depthMask; }
    public boolean isCull() { return cull; }
    public boolean isScissor() { return scissor; }
    public boolean isStencil() { return stencil; }
    public int[] getViewport() { return viewport.clone(); }
    public int[] getScissorBox() { return scissorBox.clone(); }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof GlStateSnapshot)) return false;
        GlStateSnapshot other = (GlStateSnapshot) value;
        return framebuffer == other.framebuffer && program == other.program
            && vertexArray == other.vertexArray && arrayBuffer == other.arrayBuffer
            && elementBuffer == other.elementBuffer
            && activeTextureUnit == other.activeTextureUnit
            && Arrays.equals(textures2d, other.textures2d)
            && blend == other.blend && blendSrcRgb == other.blendSrcRgb
            && blendDstRgb == other.blendDstRgb
            && blendSrcAlpha == other.blendSrcAlpha
            && blendDstAlpha == other.blendDstAlpha
            && blendEquationRgb == other.blendEquationRgb
            && blendEquationAlpha == other.blendEquationAlpha
            && depthTest == other.depthTest && depthFunction == other.depthFunction
            && depthMask == other.depthMask && cull == other.cull
            && cullFace == other.cullFace && scissor == other.scissor
            && stencil == other.stencil && Arrays.equals(viewport, other.viewport)
            && Arrays.equals(scissorBox, other.scissorBox)
            && colorMaskBits == other.colorMaskBits;
    }

    @Override
    public int hashCode() {
        int result = framebuffer;
        result = 31 * result + program;
        result = 31 * result + Arrays.hashCode(textures2d);
        result = 31 * result + Arrays.hashCode(viewport);
        result = 31 * result + colorMaskBits;
        return result;
    }
}
