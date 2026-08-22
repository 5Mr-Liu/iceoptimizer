package dev.rlcraft.ice.optimizer.render.validation;

/** Exact attachment comparator; structural differences cannot be hidden by SSIM. */
public final class ShaderImageValidator {
    public ImageValidationResult compare(byte[] legacy, byte[] modern,
                                         int allowedComponentDelta) {
        if (legacy == null || modern == null || legacy.length != modern.length) {
            return new ImageValidationResult(false, 255, -1L,
                "attachment dimensions/length differ");
        }
        int allowed = Math.max(0, Math.min(255, allowedComponentDelta));
        int maximum = 0;
        long differing = 0L;
        for (int i = 0; i < legacy.length; i++) {
            int delta = Math.abs((legacy[i] & 255) - (modern[i] & 255));
            maximum = Math.max(maximum, delta);
            if (delta > allowed) differing++;
        }
        return new ImageValidationResult(differing == 0L, maximum, differing,
            differing == 0L ? "equivalent" : "component delta exceeded at "
                + differing + " positions");
    }
}
