package dev.rlcraft.ice.optimizer.render.validation;

public final class ImageValidationResult {
    private final boolean equivalent;
    private final int maximumDelta;
    private final long differingComponents;
    private final String detail;

    ImageValidationResult(boolean equivalent, int maximumDelta,
                          long differingComponents, String detail) {
        this.equivalent = equivalent;
        this.maximumDelta = maximumDelta;
        this.differingComponents = differingComponents;
        this.detail = detail;
    }

    public boolean isEquivalent() { return equivalent; }
    public int getMaximumDelta() { return maximumDelta; }
    public long getDifferingComponents() { return differingComponents; }
    public String getDetail() { return detail; }
}
