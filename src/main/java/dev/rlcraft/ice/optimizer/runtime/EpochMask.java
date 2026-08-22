package dev.rlcraft.ice.optimizer.runtime;

public final class EpochMask {
    public static final int NONE = 0;
    public static final int FRAME = 1;
    public static final int CLIENT_TICK = 1 << 1;
    public static final int WORLD = 1 << 2;
    public static final int RESOURCE = 1 << 3;
    public static final int GL_CONTEXT = 1 << 4;
    public static final int SHADER_PACK = 1 << 5;
    public static final int SHADER_PERMUTATION = 1 << 6;
    public static final int VERTEX_FORMAT = 1 << 7;
    public static final int VIEW_FRUSTUM = 1 << 8;
    public static final int WORLD_RESOURCES = WORLD | RESOURCE;
    public static final int GPU_RESOURCE = RESOURCE | GL_CONTEXT;
    public static final int RENDER_PIPELINE = GPU_RESOURCE | SHADER_PACK
        | SHADER_PERMUTATION | VERTEX_FORMAT | VIEW_FRUSTUM;

    private EpochMask() {
    }
}
