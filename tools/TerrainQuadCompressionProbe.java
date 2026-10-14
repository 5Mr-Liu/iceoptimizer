package dev.rlcraft.ice.benchmark;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Feasibility of exact rectangle descriptors; no quantization or render claim. */
public final class TerrainQuadCompressionProbe {
    public static void main(String[] args) throws Exception {
        TerrainSceneData scene = TerrainSceneData.read(args[0]);
        long quads = 0, rectangles = 0, uniform = 0, eligible = 0, bytes = 0;
        int[][] bits = new int[5][4];
        int[] counts = new int[5];
        int[] corners = new int[4];
        for (TerrainSceneData.Section section : scene.sections) {
            if (!section.owned) continue;
            bytes += section.data.length;
            ByteBuffer source = ByteBuffer.wrap(section.data).order(ByteOrder.LITTLE_ENDIAN);
            for (int first = 0; first + 4 <= section.vertices; first += 4) {
                quads++;
                boolean sameColorLight = true, finite = true;
                int color = source.getInt(first * 28 + 12), light = source.getInt(first * 28 + 24);
                for (int v = 0; v < 4; v++) {
                    sameColorLight &= source.getInt((first + v) * 28 + 12) == color
                        && source.getInt((first + v) * 28 + 24) == light;
                    for (int d = 0; d < 5; d++) {
                        bits[d][v] = source.getInt((first + v) * 28 + (d < 3 ? d * 4 : 16 + (d - 3) * 4));
                        finite &= Float.isFinite(Float.intBitsToFloat(bits[d][v]));
                    }
                }
                if (!finite) continue;
                for (int d = 0; d < 5; d++) counts[d] = distinct(bits[d]);
                int constant = -1, dimensions = 0;
                boolean rectangle = true;
                for (int d = 0; d < 3; d++) {
                    if (counts[d] == 1) { constant = d; dimensions++; }
                    else rectangle &= counts[d] == 2;
                }
                if (!rectangle || dimensions != 1) continue;
                int a = (constant + 1) % 3, b = (constant + 2) % 3;
                if (!allCorners(bits[a], bits[b], corners)) continue;
                rectangles++;
                if (!sameColorLight) continue;
                uniform++;
                if (counts[3] == 2 && counts[4] == 2 && allCorners(bits[3], bits[4], corners)) eligible++;
            }
        }
        long estimated = bytes - eligible * (112 - 48);
        System.out.println("layer=" + scene.layer + " quads=" + quads + " rectangles=" + rectangles + " uniform_color_light=" + uniform);
        System.out.println("eligible_exact_rectangles=" + eligible + " percent=" + 100.0 * eligible / quads);
        System.out.println("source_bytes=" + bytes + " descriptor48_estimated_bytes=" + estimated + " reduction_percent=" + 100.0 * (bytes - estimated) / bytes);
        System.out.println("scope=format feasibility only; ignores mixed-path dispatch and shader cost; no FPS claim");
    }

    private static int distinct(int[] values) {
        int count = 0;
        for (int i = 0; i < values.length; i++) {
            boolean known = false;
            for (int j = 0; j < i; j++) known |= values[i] == values[j];
            if (!known) count++;
        }
        return count;
    }
    private static boolean allCorners(int[] x, int[] y, int[] corners) {
        int mask = 0;
        for (int i = 0; i < 4; i++) {
            corners[i] = (x[i] == x[0] ? 0 : 1) | (y[i] == y[0] ? 0 : 2);
            mask |= 1 << corners[i];
        }
        // A rectangle must visit opposite corners two vertices apart.
        return mask == 15 && (corners[0] ^ corners[2]) == 3 && (corners[1] ^ corners[3]) == 3;
    }
}
