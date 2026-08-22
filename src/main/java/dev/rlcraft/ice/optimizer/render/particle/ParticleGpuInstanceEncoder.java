package dev.rlcraft.ice.optimizer.render.particle;

import java.nio.ByteBuffer;

/**
 * Exact final-corner particle instance.  Positions are rounded with the same
 * double-add/float-cast sequence as BufferBuilder before entering the stream;
 * the vertex shader only selects one of the four already-final positions.
 */
final class ParticleGpuInstanceEncoder {
    static final int POSITION_0 = 0;
    static final int POSITION_1 = 12;
    static final int POSITION_2 = 24;
    static final int POSITION_3 = 36;
    static final int UV_BOUNDS = 48;
    static final int COLOR = 64;
    static final int LIGHT = 68;
    static final int BYTES_PER_INSTANCE = 72;

    private ParticleGpuInstanceEncoder() { }

    static boolean put(ByteBuffer output, float x, float y, float z,
                       double[] corners, float minU, float minV,
                       float maxU, float maxV, float red, float green,
                       float blue, float alpha, int lightU, int lightV) {
        if (output == null || corners == null || corners.length < 12
            || output.remaining() < BYTES_PER_INSTANCE) return false;
        for (int offset = 0; offset < 12; offset += 3) {
            output.putFloat((float) ((double) x + corners[offset]));
            output.putFloat((float) ((double) y + corners[offset + 1]));
            output.putFloat((float) ((double) z + corners[offset + 2]));
        }
        output.putFloat(minU).putFloat(minV).putFloat(maxU).putFloat(maxV);
        output.put((byte) ((int) (red * 255.0F)));
        output.put((byte) ((int) (green * 255.0F)));
        output.put((byte) ((int) (blue * 255.0F)));
        output.put((byte) ((int) (alpha * 255.0F)));
        output.putShort((short) lightV).putShort((short) lightU);
        return true;
    }

    static void appendLegacyQuad(ByteBuffer instances, int instanceIndex,
                                 ByteBuffer output) {
        int base = Math.multiplyExact(instanceIndex, BYTES_PER_INSTANCE);
        float minU = instances.getFloat(base + UV_BOUNDS);
        float minV = instances.getFloat(base + UV_BOUNDS + 4);
        float maxU = instances.getFloat(base + UV_BOUNDS + 8);
        float maxV = instances.getFloat(base + UV_BOUNDS + 12);
        appendVertex(instances, base + POSITION_0, maxU, maxV, base, output);
        appendVertex(instances, base + POSITION_1, maxU, minV, base, output);
        appendVertex(instances, base + POSITION_2, minU, minV, base, output);
        appendVertex(instances, base + POSITION_3, minU, maxV, base, output);
    }

    private static void appendVertex(ByteBuffer instances, int position,
                                     float u, float v, int base,
                                     ByteBuffer output) {
        output.putFloat(instances.getFloat(position));
        output.putFloat(instances.getFloat(position + 4));
        output.putFloat(instances.getFloat(position + 8));
        output.putFloat(u).putFloat(v);
        output.put(instances.get(base + COLOR));
        output.put(instances.get(base + COLOR + 1));
        output.put(instances.get(base + COLOR + 2));
        output.put(instances.get(base + COLOR + 3));
        output.putShort(instances.getShort(base + LIGHT));
        output.putShort(instances.getShort(base + LIGHT + 2));
    }
}
