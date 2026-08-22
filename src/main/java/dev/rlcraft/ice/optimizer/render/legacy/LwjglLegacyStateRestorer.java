package dev.rlcraft.ice.optimizer.render.legacy;

import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyMatrixStateTracker;
import java.nio.FloatBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

/**
 * Restores the software-tracked legacy call-site state without any glGet.
 * Unknown tracking state is rejected so callers can keep the whole segment on
 * the untouched legacy path instead of guessing a binding.
 */
public final class LwjglLegacyStateRestorer
    implements LegacyGlIsland.LegacyStateRestorer {
    private final FloatBuffer matrix = BufferUtils.createFloatBuffer(16);

    @Override
    public void restoreLegacyCallSiteState() {
        EarlyGlStateTracker.CompatibilitySnapshot state =
            EarlyGlStateTracker.compatibilitySnapshot();
        EarlyMatrixStateTracker.Snapshot matrices =
            EarlyMatrixStateTracker.snapshot();
        if (state == null || matrices == null) {
            throw new IllegalStateException("legacy call-site state is unknown");
        }

        if (state.getReadFramebuffer() == state.getDrawFramebuffer()) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,
                state.getDrawFramebuffer());
        } else {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,
                state.getReadFramebuffer());
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,
                state.getDrawFramebuffer());
        }
        GL20.glUseProgram(state.getProgram());
        if (state.isVertexArraySupported()) {
            GL30.glBindVertexArray(state.getVertexArray());
        }
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, state.getArrayBuffer());
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,
            state.getElementBuffer());
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,
            state.getPixelPackBuffer());
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,
            state.getPixelUnpackBuffer());
        GL15.glBindBuffer(36671, state.getDrawIndirectBuffer());

        for (int unit = 0; unit < state.getTextureUnitCount(); unit++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            enable(GL11.GL_TEXTURE_2D, state.isTexture2dEnabled(unit));
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, state.getTexture2d(unit));
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + state.getActiveTexture());
        GL13.glClientActiveTexture(GL13.GL_TEXTURE0
            + state.getClientActiveTexture());

        enable(GL11.GL_BLEND, state.isBlend());
        GL14.glBlendFuncSeparate(state.getBlendSourceRgb(),
            state.getBlendDestinationRgb(), state.getBlendSourceAlpha(),
            state.getBlendDestinationAlpha());
        GL20.glBlendEquationSeparate(state.getBlendEquationRgb(),
            state.getBlendEquationAlpha());
        enable(GL11.GL_DEPTH_TEST, state.isDepthTest());
        GL11.glDepthFunc(state.getDepthFunction());
        GL11.glDepthMask(state.isDepthMask());
        enable(GL11.GL_CULL_FACE, state.isCull());
        GL11.glCullFace(state.getCullFace());
        enable(GL11.GL_SCISSOR_TEST, state.isScissor());
        enable(GL11.GL_STENCIL_TEST, state.isStencil());
        GL11.glViewport(state.getViewportX(), state.getViewportY(),
            state.getViewportWidth(), state.getViewportHeight());
        GL11.glScissor(state.getScissorX(), state.getScissorY(),
            state.getScissorWidth(), state.getScissorHeight());
        int mask = state.getColorMask();
        GL11.glColorMask((mask & 1) != 0, (mask & 2) != 0,
            (mask & 4) != 0, (mask & 8) != 0);
        GL11.glColor4f(state.getRed(), state.getGreen(), state.getBlue(),
            state.getAlpha());

        loadMatrix(GL11.GL_MODELVIEW, matrices.getModelView());
        loadMatrix(GL11.GL_PROJECTION, matrices.getProjection());
        loadMatrix(GL11.GL_TEXTURE, matrices.getTexture());
        GL11.glMatrixMode(matrices.getMode());
    }

    private void loadMatrix(int mode, float[] values) {
        matrix.clear();
        matrix.put(values);
        matrix.flip();
        GL11.glMatrixMode(mode);
        GL11.glLoadMatrix(matrix);
    }

    private static void enable(int capability, boolean enabled) {
        if (enabled) GL11.glEnable(capability);
        else GL11.glDisable(capability);
    }
}
