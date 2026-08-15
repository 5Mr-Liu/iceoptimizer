package dev.rlcraft.ice.optimizer.runtime;

public final class EpochMask {
    public static final int NONE = 0;
    public static final int FRAME = 1;
    public static final int CLIENT_TICK = 1 << 1;
    public static final int WORLD = 1 << 2;
    public static final int RESOURCE = 1 << 3;
    public static final int GL_CONTEXT = 1 << 4;
    public static final int WORLD_RESOURCES = WORLD | RESOURCE;
    public static final int GPU_RESOURCE = RESOURCE | GL_CONTEXT;

    private EpochMask() {
    }
}
