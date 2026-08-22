package dev.rlcraft.ice.optimizer.render.legacy;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** Complete bind-only restore used after an unknown Legacy Island. */
public final class LwjglGlStateDriver implements GlStateDriver {
    @Override
    public void apply(GlStateSnapshot value) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, value.framebuffer);
        GL20.glUseProgram(value.program);
        GL30.glBindVertexArray(value.vertexArray);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, value.arrayBuffer);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, value.elementBuffer);
        for (int unit = 0; unit < value.textures2d.length; unit++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, value.textures2d[unit]);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + value.activeTextureUnit);
        enable(GL11.GL_BLEND, value.blend);
        GL14.glBlendFuncSeparate(value.blendSrcRgb, value.blendDstRgb,
            value.blendSrcAlpha, value.blendDstAlpha);
        GL20.glBlendEquationSeparate(value.blendEquationRgb,
            value.blendEquationAlpha);
        enable(GL11.GL_DEPTH_TEST, value.depthTest);
        GL11.glDepthFunc(value.depthFunction);
        GL11.glDepthMask(value.depthMask);
        enable(GL11.GL_CULL_FACE, value.cull);
        GL11.glCullFace(value.cullFace);
        enable(GL11.GL_SCISSOR_TEST, value.scissor);
        enable(GL11.GL_STENCIL_TEST, value.stencil);
        GL11.glViewport(value.viewport[0], value.viewport[1],
            value.viewport[2], value.viewport[3]);
        GL11.glScissor(value.scissorBox[0], value.scissorBox[1],
            value.scissorBox[2], value.scissorBox[3]);
        GL11.glColorMask((value.colorMaskBits & 1) != 0,
            (value.colorMaskBits & 2) != 0, (value.colorMaskBits & 4) != 0,
            (value.colorMaskBits & 8) != 0);
    }

    private static void enable(int capability, boolean enabled) {
        if (enabled) GL11.glEnable(capability);
        else GL11.glDisable(capability);
    }
}
