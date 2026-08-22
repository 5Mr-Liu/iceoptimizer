package dev.rlcraft.ice.optimizer.render.resource;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

public final class LwjglResourceDestroyer implements ResourceLedger.Destroyer {
    @Override public void destroy(RenderResourceKind kind, int nativeId) {
        switch (kind) {
            case BUFFER: GL15.glDeleteBuffers(nativeId); break;
            case TEXTURE: GL11.glDeleteTextures(nativeId); break;
            case VERTEX_ARRAY: GL30.glDeleteVertexArrays(nativeId); break;
            case PROGRAM: GL20.glDeleteProgram(nativeId); break;
            case QUERY: GL15.glDeleteQueries(nativeId); break;
            case FRAMEBUFFER: GL30.glDeleteFramebuffers(nativeId); break;
            case RENDERBUFFER: GL30.glDeleteRenderbuffers(nativeId); break;
            case SHADER: GL20.glDeleteShader(nativeId); break;
            default: throw new IllegalArgumentException("unknown GL resource " + kind);
        }
    }
}
