package dev.rlcraft.ice.optimizer.render.particle;

import dev.rlcraft.ice.optimizer.FatalErrors;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.BufferUtils;

/**
 * Runtime-safe byte-layout certification for the particle submission path.
 *
 * <p>The previous runtime validator created a synthetic {@code Particle}
 * subclass. In a heavily transformed client that makes output validation link
 * every optional Particle superclass/interface dependency before the first
 * real particle draw; one unavailable transformed dependency therefore
 * disabled an otherwise usable renderer with {@code NoClassDefFoundError}.
 * Full vanilla-emitter equivalence remains a build-time regression test. The
 * runtime check is deliberately limited to the exact BufferBuilder/VBO byte
 * boundary, while the executable instancing capability test still validates
 * the real shader and draw path.</p>
 */
public final class ParticleOutputValidator {
    private static volatile Result cached;

    private ParticleOutputValidator() {
    }

    public static Result validate() {
        Result value = cached;
        if (value != null) return value;
        synchronized (ParticleOutputValidator.class) {
            value = cached;
            if (value == null) cached = value = runValidation();
            return value;
        }
    }

    static void clearForTest() { cached = null; }

    private static Result runValidation() {
        try {
            return packedEncoderMatchesBuilder()
                ? new Result(true, "particle VBO byte layout certified")
                : new Result(false, "particle VBO packing differs");
        } catch (Throwable error) {
            FatalErrors.rethrowIfFatal(error);
            String message = error.getMessage();
            return new Result(false, error.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ": " + message));
        }
    }

    private static boolean packedEncoderMatchesBuilder() {
        float x = 2.75F;
        float y = -4.125F;
        float z = 0.3125F;
        double[] corners = {
            -0.125D, 0.25D, 0.375D, 0.5D, -0.75D, 0.875D,
            1.125D, 1.25D, -1.375D, -1.5D, 1.625D, 1.75D
        };
        float minU = 0.1171875F;
        float minV = 0.203125F;
        float maxU = 0.4921875F;
        float maxV = 0.828125F;
        float red = 0.23F;
        float green = 0.61F;
        float blue = 0.87F;
        float alpha = 0.42F;
        int lightU = 208;
        int lightV = 112;
        BufferBuilder expected = new BufferBuilder(256);
        expected.begin(7, DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP);
        for (int vertex = 0; vertex < 4; vertex++) {
            int offset = vertex * 3;
            float u = vertex < 2 ? maxU : minU;
            float v = vertex == 0 || vertex == 3 ? maxV : minV;
            expected.pos((double) x + corners[offset],
                (double) y + corners[offset + 1],
                (double) z + corners[offset + 2]).tex(u, v)
                .color(red, green, blue, alpha)
                .lightmap(lightU, lightV).endVertex();
        }
        ByteBuffer actual = BufferUtils.createByteBuffer(
            ParticleVertexEncoder.BYTES_PER_QUAD).order(ByteOrder.nativeOrder());
        if (!ParticleVertexEncoder.putQuad(actual, x, y, z, corners,
            minU, minV, maxU, maxV, red, green, blue, alpha,
            lightU, lightV)) return false;
        ByteBuffer legacy = expected.getByteBuffer();
        for (int index = 0; index < ParticleVertexEncoder.BYTES_PER_QUAD; index++) {
            if (legacy.get(index) != actual.get(index)) return false;
        }
        return true;
    }

    public static final class Result {
        private final boolean equivalent;
        private final String detail;

        private Result(boolean equivalent, String detail) {
            this.equivalent = equivalent;
            this.detail = detail;
        }

        public boolean isEquivalent() { return equivalent; }
        public String getDetail() { return detail; }
    }
}
