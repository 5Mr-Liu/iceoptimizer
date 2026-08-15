package dev.rlcraft.ice.optimizer;

/** Physical side whose optimizer runtime owns the shared module registry. */
public enum OptimizerRuntimeSide {
    CLIENT("客户端"),
    DEDICATED_SERVER("专用服务端");

    private final String displayName;

    OptimizerRuntimeSide(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
