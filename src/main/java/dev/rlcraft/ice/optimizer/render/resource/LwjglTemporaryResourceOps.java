package dev.rlcraft.ice.optimizer.render.resource;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** Shared native-name operations for bounded executable GL probes. */
public final class LwjglTemporaryResourceOps {
    public static final TemporaryGpuResourceScope.IntAllocator GEN_BUFFER =
        new TemporaryGpuResourceScope.IntAllocator() {
            @Override public int allocate() { return GL15.glGenBuffers(); }
        };
    public static final TemporaryGpuResourceScope.IntAllocator GEN_TEXTURE =
        new TemporaryGpuResourceScope.IntAllocator() {
            @Override public int allocate() { return GL11.glGenTextures(); }
        };
    public static final TemporaryGpuResourceScope.IntAllocator GEN_VERTEX_ARRAY =
        new TemporaryGpuResourceScope.IntAllocator() {
            @Override public int allocate() { return GL30.glGenVertexArrays(); }
        };
    public static final TemporaryGpuResourceScope.IntAllocator GEN_FRAMEBUFFER =
        new TemporaryGpuResourceScope.IntAllocator() {
            @Override public int allocate() { return GL30.glGenFramebuffers(); }
        };
    public static final TemporaryGpuResourceScope.IntAllocator GEN_RENDERBUFFER =
        new TemporaryGpuResourceScope.IntAllocator() {
            @Override public int allocate() { return GL30.glGenRenderbuffers(); }
        };
    public static final TemporaryGpuResourceScope.IntAllocator GEN_QUERY =
        new TemporaryGpuResourceScope.IntAllocator() {
            @Override public int allocate() { return GL15.glGenQueries(); }
        };
    public static final TemporaryGpuResourceScope.IntAllocator CREATE_PROGRAM =
        new TemporaryGpuResourceScope.IntAllocator() {
            @Override public int allocate() { return GL20.glCreateProgram(); }
        };

    public static final TemporaryGpuResourceScope.IntDestroyer DELETE_BUFFER =
        new TemporaryGpuResourceScope.IntDestroyer() {
            @Override public void destroy(int id) { GL15.glDeleteBuffers(id); }
        };
    public static final TemporaryGpuResourceScope.IntDestroyer DELETE_TEXTURE =
        new TemporaryGpuResourceScope.IntDestroyer() {
            @Override public void destroy(int id) { GL11.glDeleteTextures(id); }
        };
    public static final TemporaryGpuResourceScope.IntDestroyer DELETE_VERTEX_ARRAY =
        new TemporaryGpuResourceScope.IntDestroyer() {
            @Override public void destroy(int id) {
                GL30.glDeleteVertexArrays(id);
            }
        };
    public static final TemporaryGpuResourceScope.IntDestroyer DELETE_FRAMEBUFFER =
        new TemporaryGpuResourceScope.IntDestroyer() {
            @Override public void destroy(int id) {
                GL30.glDeleteFramebuffers(id);
            }
        };
    public static final TemporaryGpuResourceScope.IntDestroyer DELETE_RENDERBUFFER =
        new TemporaryGpuResourceScope.IntDestroyer() {
            @Override public void destroy(int id) {
                GL30.glDeleteRenderbuffers(id);
            }
        };
    public static final TemporaryGpuResourceScope.IntDestroyer DELETE_QUERY =
        new TemporaryGpuResourceScope.IntDestroyer() {
            @Override public void destroy(int id) { GL15.glDeleteQueries(id); }
        };
    public static final TemporaryGpuResourceScope.IntDestroyer DELETE_PROGRAM =
        new TemporaryGpuResourceScope.IntDestroyer() {
            @Override public void destroy(int id) { GL20.glDeleteProgram(id); }
        };
    public static final TemporaryGpuResourceScope.IntDestroyer DELETE_SHADER =
        new TemporaryGpuResourceScope.IntDestroyer() {
            @Override public void destroy(int id) { GL20.glDeleteShader(id); }
        };

    private LwjglTemporaryResourceOps() { }
}
