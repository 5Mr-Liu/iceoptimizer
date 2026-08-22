package dev.rlcraft.ice.optimizer.render.legacy;

import java.util.Arrays;

/**
 * Software state mirror used by modern code. Unknown legacy calls invalidate
 * it; the next modern batch performs one complete required-state bind.
 */
public final class GlStateMirror {
    private final int[] textures2d;
    private final int[] viewport = new int[4];
    private final int[] scissorBox = new int[4];
    private int framebuffer;
    private int program;
    private int vertexArray;
    private int arrayBuffer;
    private int elementBuffer;
    private int activeTextureUnit;
    private boolean blend;
    private int blendSrcRgb = 1;
    private int blendDstRgb;
    private int blendSrcAlpha = 1;
    private int blendDstAlpha;
    private int blendEquationRgb = 32774;
    private int blendEquationAlpha = 32774;
    private boolean depthTest;
    private int depthFunction = 513;
    private boolean depthMask = true;
    private boolean cull;
    private int cullFace = 1029;
    private boolean scissor;
    private boolean stencil;
    private int colorMaskBits = 15;
    private boolean known;
    private long invalidations;
    private long fullRebinds;

    public GlStateMirror(int textureUnits) {
        textures2d = new int[Math.max(1, Math.min(64, textureUnits))];
    }

    public void bindFramebuffer(int value) { framebuffer = nonNegative(value); known = true; }
    public void useProgram(int value) { program = nonNegative(value); known = true; }
    public void bindVertexArray(int value) { vertexArray = nonNegative(value); known = true; }
    public void bindArrayBuffer(int value) { arrayBuffer = nonNegative(value); known = true; }
    public void bindElementBuffer(int value) { elementBuffer = nonNegative(value); known = true; }

    public void bindTexture2d(int unit, int value) {
        if (unit < 0 || unit >= textures2d.length) throw new IllegalArgumentException("texture unit");
        activeTextureUnit = unit;
        textures2d[unit] = nonNegative(value);
        known = true;
    }

    public void setBlend(boolean enabled, int srcRgb, int dstRgb, int srcAlpha,
                         int dstAlpha, int equationRgb, int equationAlpha) {
        blend = enabled;
        blendSrcRgb = srcRgb;
        blendDstRgb = dstRgb;
        blendSrcAlpha = srcAlpha;
        blendDstAlpha = dstAlpha;
        blendEquationRgb = equationRgb;
        blendEquationAlpha = equationAlpha;
        known = true;
    }

    public void setDepth(boolean enabled, int function, boolean writeMask) {
        depthTest = enabled;
        depthFunction = function;
        depthMask = writeMask;
        known = true;
    }

    public void setCull(boolean enabled, int face) {
        cull = enabled;
        cullFace = face;
        known = true;
    }

    public void setViewport(int x, int y, int width, int height) {
        setBox(viewport, x, y, width, height);
        known = true;
    }

    public void setScissor(boolean enabled, int x, int y, int width, int height) {
        scissor = enabled;
        setBox(scissorBox, x, y, width, height);
        known = true;
    }

    public void setStencil(boolean enabled) { stencil = enabled; known = true; }

    public void setColorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        colorMaskBits = (red ? 1 : 0) | (green ? 2 : 0)
            | (blue ? 4 : 0) | (alpha ? 8 : 0);
        known = true;
    }

    public GlStateSnapshot snapshot() {
        return new GlStateSnapshot(framebuffer, program, vertexArray, arrayBuffer,
            elementBuffer, activeTextureUnit, textures2d, blend, blendSrcRgb,
            blendDstRgb, blendSrcAlpha, blendDstAlpha, blendEquationRgb,
            blendEquationAlpha, depthTest, depthFunction, depthMask, cull,
            cullFace, scissor, stencil, viewport, scissorBox, colorMaskBits);
    }

    public void restoreRequired(GlStateSnapshot required, GlStateDriver driver) {
        if (required == null || driver == null) throw new IllegalArgumentException("required state");
        if (!known || !required.equals(snapshot())) {
            driver.apply(required);
            copyFrom(required);
            known = true;
            fullRebinds++;
        }
    }

    public void invalidateAll() {
        known = false;
        invalidations++;
    }

    public boolean isKnown() { return known; }
    public long getInvalidations() { return invalidations; }
    public long getFullRebinds() { return fullRebinds; }

    private void copyFrom(GlStateSnapshot value) {
        framebuffer = value.framebuffer;
        program = value.program;
        vertexArray = value.vertexArray;
        arrayBuffer = value.arrayBuffer;
        elementBuffer = value.elementBuffer;
        activeTextureUnit = value.activeTextureUnit;
        System.arraycopy(value.textures2d, 0, textures2d, 0,
            Math.min(textures2d.length, value.textures2d.length));
        if (value.textures2d.length < textures2d.length) {
            Arrays.fill(textures2d, value.textures2d.length, textures2d.length, 0);
        }
        blend = value.blend;
        blendSrcRgb = value.blendSrcRgb;
        blendDstRgb = value.blendDstRgb;
        blendSrcAlpha = value.blendSrcAlpha;
        blendDstAlpha = value.blendDstAlpha;
        blendEquationRgb = value.blendEquationRgb;
        blendEquationAlpha = value.blendEquationAlpha;
        depthTest = value.depthTest;
        depthFunction = value.depthFunction;
        depthMask = value.depthMask;
        cull = value.cull;
        cullFace = value.cullFace;
        scissor = value.scissor;
        stencil = value.stencil;
        System.arraycopy(value.viewport, 0, viewport, 0, 4);
        System.arraycopy(value.scissorBox, 0, scissorBox, 0, 4);
        colorMaskBits = value.colorMaskBits;
    }

    private static int nonNegative(int value) {
        if (value < 0) throw new IllegalArgumentException("negative GL name");
        return value;
    }

    private static void setBox(int[] target, int x, int y, int width, int height) {
        if (width < 0 || height < 0) throw new IllegalArgumentException("negative extent");
        target[0] = x;
        target[1] = y;
        target[2] = width;
        target[3] = height;
    }
}
