package dev.rlcraft.ice.optimizer.render.particle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.BufferUtils;
import org.junit.Test;

public final class ParticleOutputValidatorTest {
    @Test
    public void runtimeValidatorCertifiesTheExactVboByteBoundary() {
        ParticleOutputValidator.clearForTest();
        ParticleOutputValidator.Result result = ParticleOutputValidator.validate();
        assertTrue(result.getDetail(), result.isEquivalent());
    }

    @Test
    public void hardBarrierSplitsOtherwiseIdenticalStateRunsWithoutAllocatingOnRecord() {
        ParticleState state = new ParticleState(0, 1, 0, 770, 771,
            false, false, 2L);
        ParticleInstanceStream stream = new ParticleInstanceStream(64);
        assertTrue(stream.record(state, 1, 2, 3, 1, 0, -1, 0,
            0, 0, 1, 1, 0L));
        stream.barrier();
        assertTrue(stream.record(state, 4, 5, 6, 1, 0, -1, 0,
            0, 0, 1, 1, 1L));
        assertEquals(2, stream.runCount());
        assertEquals(2, stream.flush().size());
    }

    @Test
    public void packedVboQuadMatchesBufferBuilderByteForByte() {
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

        BufferBuilder legacy = new BufferBuilder(256);
        legacy.begin(7, DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP);
        vertex(legacy, x, y, z, corners, 0, maxU, maxV,
            red, green, blue, alpha, lightU, lightV);
        vertex(legacy, x, y, z, corners, 3, maxU, minV,
            red, green, blue, alpha, lightU, lightV);
        vertex(legacy, x, y, z, corners, 6, minU, minV,
            red, green, blue, alpha, lightU, lightV);
        vertex(legacy, x, y, z, corners, 9, minU, maxV,
            red, green, blue, alpha, lightU, lightV);

        ByteBuffer packed = BufferUtils.createByteBuffer(
            ParticleVertexEncoder.BYTES_PER_QUAD).order(ByteOrder.nativeOrder());
        assertTrue(ParticleVertexEncoder.putQuad(packed, x, y, z, corners,
            minU, minV, maxU, maxV, red, green, blue, alpha, lightU, lightV));
        ByteBuffer expected = legacy.getByteBuffer();
        for (int index = 0; index < ParticleVertexEncoder.BYTES_PER_QUAD; index++) {
            assertEquals("byte " + index, expected.get(index), packed.get(index));
        }
    }

    private static void vertex(BufferBuilder output, float x, float y, float z,
                               double[] corners, int offset, float u, float v,
                               float red, float green, float blue, float alpha,
                               int lightU, int lightV) {
        output.pos((double) x + corners[offset],
            (double) y + corners[offset + 1],
            (double) z + corners[offset + 2]).tex(u, v)
            .color(red, green, blue, alpha).lightmap(lightU, lightV).endVertex();
    }
}
