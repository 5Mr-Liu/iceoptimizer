package dev.rlcraft.ice.optimizer.render.optifine;

public final class ShaderStateValidationResult {
    private final boolean equivalent;
    private final String detail;

    ShaderStateValidationResult(boolean equivalent, String detail) {
        this.equivalent = equivalent;
        this.detail = detail == null ? "" : detail;
    }

    public boolean isEquivalent() { return equivalent; }
    public String getDetail() { return detail; }
}
