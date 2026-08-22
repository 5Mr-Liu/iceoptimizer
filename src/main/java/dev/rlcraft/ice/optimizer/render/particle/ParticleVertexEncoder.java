package dev.rlcraft.ice.optimizer.render.particle;

import java.nio.ByteBuffer;

/** Exact 28-byte PARTICLE_POSITION_TEX_COLOR_LMAP encoder. */
final class ParticleVertexEncoder {
    static final int BYTES_PER_VERTEX = 28;
    static final int VERTICES_PER_QUAD = 4;
    static final int BYTES_PER_QUAD = BYTES_PER_VERTEX * VERTICES_PER_QUAD;

    private ParticleVertexEncoder() {
    }

    static boolean putQuad(ByteBuffer output, float x, float y, float z,
                           double[] corners, float minU, float minV,
                           float maxU, float maxV, float red, float green,
                           float blue, float alpha, int lightU, int lightV) {
        if (output == null || corners == null || corners.length < 12
            || output.remaining() < BYTES_PER_QUAD) return false;
        int r = (int) (red * 255.0F);
        int g = (int) (green * 255.0F);
        int b = (int) (blue * 255.0F);
        int a = (int) (alpha * 255.0F);
        put(output, (float) ((double) x + corners[0]),
            (float) ((double) y + corners[1]),
            (float) ((double) z + corners[2]), maxU, maxV,
            r, g, b, a, lightU, lightV);
        put(output, (float) ((double) x + corners[3]),
            (float) ((double) y + corners[4]),
            (float) ((double) z + corners[5]), maxU, minV,
            r, g, b, a, lightU, lightV);
        put(output, (float) ((double) x + corners[6]),
            (float) ((double) y + corners[7]),
            (float) ((double) z + corners[8]), minU, minV,
            r, g, b, a, lightU, lightV);
        put(output, (float) ((double) x + corners[9]),
            (float) ((double) y + corners[10]),
            (float) ((double) z + corners[11]), minU, maxV,
            r, g, b, a, lightU, lightV);
        return true;
    }

    private static void put(ByteBuffer output, float x, float y, float z,
                            float u, float v, int red, int green, int blue,
                            int alpha, int lightU, int lightV) {
        output.putFloat(x).putFloat(y).putFloat(z);
        output.putFloat(u).putFloat(v);
        output.put((byte) red).put((byte) green).put((byte) blue)
            .put((byte) alpha);
        // BufferBuilder.lightmap writes the second short first for the UV
        // SHORT element (see its EnumType.SHORT branch).
        output.putShort((short) lightV).putShort((short) lightU);
    }
}
