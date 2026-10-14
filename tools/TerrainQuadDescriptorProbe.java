package dev.rlcraft.ice.benchmark;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Bit-exact CPU feasibility for 72-byte axis-aligned quad descriptors. */
public final class TerrainQuadDescriptorProbe {
    public static void main(String[] args) throws Exception {
        TerrainSceneData scene = TerrainSceneData.read(args[0]);
        int[] input = new int[28], packed = new int[18], restored = new int[28];
        long quads = 0, accepted = 0, bytes = 0, uniform = 0;
        for (TerrainSceneData.Section section : scene.sections) {
            if (!section.owned) continue;
            bytes += section.data.length;
            ByteBuffer source = ByteBuffer.wrap(section.data).order(ByteOrder.LITTLE_ENDIAN);
            for (int first = 0; first + 4 <= section.vertices; first += 4) {
                quads++;
                for (int i = 0; i < 28; i++) input[i] = source.getInt(first * 28 + i * 4);
                if (!encode(input, packed)) continue;
                decode(packed, restored);
                if (!java.util.Arrays.equals(input, restored)) throw new AssertionError("Vertex bits changed");
                accepted++;
                boolean constant = true;
                for (int v = 1; v < 4; v++) constant &= input[v * 7 + 3] == input[3] && input[v * 7 + 6] == input[6];
                if (constant) uniform++;
            }
        }
        System.out.println("layer=" + scene.layer + " quads=" + quads + " descriptor72_quads=" + accepted + " uniform48_quads=" + uniform);
        System.out.println("source_bytes=" + bytes + " descriptor72_bytes=" + (bytes - accepted * 40) + " mixed48_72_bytes=" + (bytes - accepted * 40 - uniform * 24));
        System.out.println("cpu_vertex_bits_exact=true eligible_percent=" + 100.0 * accepted / quads);
        System.out.println("scope=CPU reconstruction only; GPU pixel equivalence, mixed dispatch and performance untested");
    }

    static boolean encode(int[] input, int[] output) {
        int axis = -1;
        for (int d = 0; d < 3; d++) {
            boolean same = true;
            for (int v = 1; v < 4; v++) same &= input[v * 7 + d] == input[d];
            if (same) { if (axis >= 0) return false; axis = d; }
        }
        if (axis < 0) return false;
        int a = (axis + 1) % 3, b = (axis + 2) % 3;
        output[0] = input[axis];
        if (!Float.isFinite(Float.intBitsToFloat(output[0]))) return false;
        if (!pair(input, a, output, 1) || !pair(input, b, output, 3)
            || !pair(input, 4, output, 5) || !pair(input, 5, output, 7)) return false;
        int descriptor = axis, geometryCorners = 0, textureCorners = 0;
        for (int v = 0; v < 4; v++) {
            int mask = (input[v * 7 + a] == output[1] ? 0 : 1)
                | (input[v * 7 + b] == output[3] ? 0 : 2)
                | (input[v * 7 + 4] == output[5] ? 0 : 4)
                | (input[v * 7 + 5] == output[7] ? 0 : 8);
            descriptor |= mask << (2 + v * 4);
            geometryCorners |= 1 << (mask & 3);
            textureCorners |= 1 << (mask >>> 2);
            output[10 + v] = input[v * 7 + 3];
            output[14 + v] = input[v * 7 + 6];
        }
        if (geometryCorners != 15 || textureCorners != 15) return false;
        output[9] = descriptor;
        return true;
    }

    private static boolean pair(int[] input, int component, int[] output, int at) {
        int first = input[component], second = first;
        for (int v = 1; v < 4; v++) if (input[v * 7 + component] != first) second = input[v * 7 + component];
        if (first == second || !Float.isFinite(Float.intBitsToFloat(first)) || !Float.isFinite(Float.intBitsToFloat(second))) return false;
        for (int v = 0; v < 4; v++) if (input[v * 7 + component] != first && input[v * 7 + component] != second) return false;
        output[at] = first; output[at + 1] = second;
        return true;
    }

    static void decode(int[] input, int[] output) {
        int axis = input[9] & 3, a = (axis + 1) % 3, b = (axis + 2) % 3;
        for (int v = 0; v < 4; v++) {
            int mask = (input[9] >>> (2 + v * 4)) & 15;
            output[v * 7 + axis] = input[0];
            output[v * 7 + a] = input[1 + (mask & 1)];
            output[v * 7 + b] = input[3 + ((mask >>> 1) & 1)];
            output[v * 7 + 3] = input[10 + v];
            output[v * 7 + 4] = input[5 + ((mask >>> 2) & 1)];
            output[v * 7 + 5] = input[7 + ((mask >>> 3) & 1)];
            output[v * 7 + 6] = input[14 + v];
        }
    }
}
