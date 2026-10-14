package dev.rlcraft.ice.optimizer.render.terrain;

import static org.junit.Assert.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import org.junit.Test;

public final class TerrainFaceRangesTest {
    @Test public void packedSpansExactlyCoverEveryMaskInOriginalOrder() {
        int[] topology = TerrainFaceRanges.spanPlans();
        for (int mask = 0; mask < 128; mask++) {
            int plan = topology[mask], reconstructed = 0, previous = -1;
            assertTrue((plan >>> 24) <= 4);
            for (int spans = plan >>> 24; spans > 0; spans--, plan >>>= 6) {
                int first = plan & 7, last = (plan >>> 3) & 7;
                assertTrue(first > previous && first <= last && last < 7);
                for (int face = first; face <= last; face++) reconstructed |= 1 << face;
                previous = last;
            }
            assertEquals(mask, reconstructed);
        }
    }

    @Test public void gapPlanMatchesExhaustiveConservativeCostOracle() {
        Random random = new Random(20261012L);
        for (int trial = 0; trial < 1024; trial++) {
            int[] counts = new int[7];
            for (int face = 0; face < 7; face++)
                counts[face] = random.nextInt(4) == 0 ? 0 : random.nextInt(1537) * 4;
            for (int required = 1; required < 128; required += 2) {
                int plan = TerrainFaceRanges.planMask(counts, required);
                assertEquals(required, plan & required);
                long minimum = Long.MAX_VALUE;
                for (int candidate = 1; candidate < 128; candidate += 2)
                    if ((candidate & required) == required)
                        minimum = Math.min(minimum, estimatedCost(counts, candidate));
                assertEquals(minimum, estimatedCost(counts, plan));
            }
        }
    }

    @Test public void mergesCheapInteriorGapWithoutSacrificingFreeSuffixOmission() {
        int[] counts = {4, 4, 4, 4096, 4, 4, 4};
        assertEquals(0b0010111, TerrainFaceRanges.planMask(counts, 0b0010101));
    }

    private static long estimatedCost(int[] counts, int mask) {
        long cost = 0;
        boolean previous = false;
        for (int face = 0; face < 7; face++) if (counts[face] != 0) {
            boolean keep = (mask & (1 << face)) != 0;
            if (keep) cost += counts[face] + (previous ? 0 : 2048);
            previous = keep;
        }
        return cost;
    }

    @Test public void smallInteriorOmissionDoesNotSplitOneDrawIntoTwo() {
        TerrainFaceRanges ranges = planned(false, 1);
        // UNKNOWN, DOWN, UP: only DOWN is back-facing from above.
        assertFalse(ranges.visible(TerrainQuadClassifier.DOWN, 3, 10, 3));
        assertTrue(ranges.visible(TerrainQuadClassifier.UP, 3, 10, 3));
        assertEquals(127, ranges.submissionMask(3, 10, 3));
    }

    @Test public void largeInteriorOmissionPaysForTheExtraSubmission() {
        TerrainFaceRanges ranges = planned(false, 512);
        int mask = ranges.submissionMask(3, 10, 3);
        assertEquals(0, mask & (1 << TerrainQuadClassifier.DOWN));
        assertNotEquals(0, mask & 1); // Unknown geometry never disappears.
        assertNotEquals(0, mask & (1 << TerrainQuadClassifier.UP));
    }

    @Test public void smallPrefixOmissionRemainsUsefulWithoutAnExtraDraw() {
        TerrainFaceRanges ranges = planned(true, 1);
        assertEquals(0, ranges.submissionMask(3, 10, 3) & (1 << TerrainQuadClassifier.DOWN));
        assertEquals(127, ranges.submissionMask(Double.NaN, Double.NaN, Double.NaN));
    }

    private static TerrainFaceRanges planned(boolean noUnknown, int hiddenQuads) {
        int quads = hiddenQuads + (noUnknown ? 1 : 2);
        ByteBuffer source = buffer(quads * 112);
        if (!noUnknown) {
            ByteBuffer unknown = quad(TerrainQuadClassifier.SOUTH, 0, 1);
            unknown.putFloat(2 * 28 + 8, 1F); source.put(unknown);
        }
        for (int i = 0; i < hiddenQuads; i++) source.put(quad(TerrainQuadClassifier.DOWN, 0, i));
        source.put(quad(TerrainQuadClassifier.UP, 1, 2)); source.flip();
        return TerrainFaceRanges.group(source, buffer(TerrainFaceRanges.storageBytes(source.remaining())), source.remaining());
    }
    @Test public void classifiesAllSixWindingsAndRejectsMalformedQuads() {
        for (int face = 1; face <= 6; face++) {
            assertEquals(face, TerrainQuadClassifier.classify(quad(face, 2F, 7), 0, 28));
        }
        ByteBuffer twisted = quad(TerrainQuadClassifier.SOUTH, 2F, 7);
        twisted.putFloat(2 * 28 + 8, 2.01F);
        assertEquals(0, TerrainQuadClassifier.classify(twisted, 0, 28));
        ByteBuffer nan = quad(TerrainQuadClassifier.SOUTH, 2F, 7);
        nan.putFloat(0, Float.NaN);
        assertEquals(0, TerrainQuadClassifier.classify(nan, 0, 28));
        ByteBuffer bowtie = quad(TerrainQuadClassifier.SOUTH, 2F, 7);
        bowtie.putFloat(2 * 28, 0F).putFloat(3 * 28, 1F);
        assertEquals(0, TerrainQuadClassifier.classify(bowtie, 0, 28));
    }

    @Test public void groupingPreservesEveryByteAndStableOrderWithinFaces() {
        ByteBuffer unknown = quad(TerrainQuadClassifier.SOUTH, 2F, 1);
        unknown.putFloat(2 * 28 + 8, 3F);
        ByteBuffer first = quad(TerrainQuadClassifier.UP, 3F, 2);
        ByteBuffer second = quad(TerrainQuadClassifier.UP, 8F, 3);
        ByteBuffer north = quad(TerrainQuadClassifier.NORTH, 5F, 4);
        ByteBuffer source = buffer(448).put(first).put(unknown).put(second).put(north);
        source.flip();
        byte[] original = source.array().clone();
        ByteBuffer target = buffer(TerrainFaceRanges.storageBytes(448));
        TerrainFaceRanges ranges = TerrainFaceRanges.group(source, target, 448);
        assertArrayEquals(original, source.array());
        assertEquals(0, source.position());
        assertEquals(448, target.limit());
        assertEquals(4, ranges.count(0));
        assertEquals(8, ranges.count(TerrainQuadClassifier.UP));
        assertEquals(4, ranges.first(TerrainQuadClassifier.UP));
        assertEquals(1, target.getInt(12));
        assertEquals(2, target.getInt(112 + 12));
        assertEquals(3, target.getInt(224 + 12));
        assertEquals(4, target.getInt(336 + 12));
        for (int vertex = 0; vertex < 16; vertex++) {
            assertEquals(0x44556677, target.getInt(vertex * 28 + 16));
            assertEquals(0x77889900, target.getInt(vertex * 28 + 20));
            assertEquals(0x11223344, target.getInt(vertex * 28 + 24));
        }
        assertTrue(ranges.visible(0, 0, -1000, 0));
        assertTrue(ranges.visible(TerrainQuadClassifier.UP, 0, 4, 0));
        assertFalse(ranges.visible(TerrainQuadClassifier.UP, 0, 2.9, 0));
        assertTrue(ranges.visible(TerrainQuadClassifier.UP, 0, 3, 0));
        assertTrue(ranges.visible(TerrainQuadClassifier.UP, 0, 2.9995, 0));
        assertTrue(ranges.visible(TerrainQuadClassifier.UP, 0, Double.NaN, 0));
    }

    @Test public void bucketCanOnlyDisappearBehindEveryPlane() {
        for (int face = 1; face <= 6; face++) {
            ByteBuffer source = buffer(224).put(quad(face, 2F, 1)).put(quad(face, 6F, 2));
            source.flip();
            TerrainFaceRanges ranges = TerrainFaceRanges.group(source,
                buffer(TerrainFaceRanges.storageBytes(224)), 224);
            assertTrue(ranges.visible(face, 4, 4, 4));
            boolean positive = face == TerrainQuadClassifier.UP || face == TerrainQuadClassifier.SOUTH
                || face == TerrainQuadClassifier.EAST;
            double behind = positive ? 1 : 7;
            assertFalse(ranges.visible(face, behind, behind, behind));
            double front = positive ? 7 : 1;
            assertTrue(ranges.visible(face, front, front, front));
        }
    }

    @Test public void cachedFacesSurviveReusingScratchForDifferentSizedMeshes() {
        ByteBuffer target = buffer(TerrainFaceRanges.storageBytes(257 * 112));
        for (int quads : new int[] {128, 3, 257}) {
            ByteBuffer source = buffer(quads * 112);
            int[] faces = new int[quads];
            for (int q = 0; q < quads; q++) {
                int face = (q * 13 + 3) % 7;
                faces[q] = face;
                ByteBuffer value = quad(face == 0 ? TerrainQuadClassifier.SOUTH : face, q, q);
                if (face == 0) value.putFloat(2 * 28 + 8, q + .5F);
                source.put(value);
            }
            source.flip();
            TerrainFaceRanges ranges = TerrainFaceRanges.group(source, target, source.limit());
            assertEquals(source.limit(), target.limit());
            assertEquals(0, target.position());
            int offset = 0;
            for (int face = 0; face < 7; face++) {
                assertEquals(offset / 28, ranges.first(face));
                int count = 0;
                for (int q = 0; q < quads; q++) if (faces[q] == face) {
                    for (int b = 0; b < 112; b++)
                        assertEquals(source.get(q * 112 + b), target.get(offset + b));
                    offset += 112;
                    count += 4;
                }
                assertEquals(count, ranges.count(face));
            }
            assertEquals(source.limit(), offset);
        }
    }

    @Test public void cameraUsesInverseAffineViewAndRejectsReflections() {
        float[] matrix = {0, 2, 0, 0, -3, 0, 0, 0, 0, 0, 4, 0, 12, -6, -20, 1};
        double[] eye = new double[3];
        assertTrue(TerrainCamera.eye(matrix, eye));
        assertArrayEquals(new double[] {3, 4, 5}, eye, 1.0E-12);
        matrix[10] = -4;
        assertFalse(TerrainCamera.eye(matrix, eye));
        matrix[10] = 0;
        assertFalse(TerrainCamera.eye(matrix, eye));
    }

    @Test public void indirectCommandsPreserveNonContiguousBaseInstancesAndSliceOffset() {
        ByteBuffer command = buffer(64);
        LwjglTerrainBatchRenderer.putCommand(command, 16, 12, 72, 9);
        assertEquals(0, command.getInt(0));
        assertEquals(12, command.getInt(16));
        assertEquals(1, command.getInt(20));
        assertEquals(72, command.getInt(24));
        assertEquals(9, command.getInt(28));
        assertEquals(0, command.getInt(32));
    }

    private static ByteBuffer buffer(int bytes) { return ByteBuffer.allocate(bytes).order(ByteOrder.nativeOrder()); }
    private static ByteBuffer quad(int face, float plane, int tag) {
        ByteBuffer result = buffer(112);
        boolean positive = face == TerrainQuadClassifier.UP || face == TerrainQuadClassifier.SOUTH
            || face == TerrainQuadClassifier.EAST;
        int[][] corners = positive ? new int[][] {{0,0},{1,0},{1,1},{0,1}}
            : new int[][] {{0,0},{0,1},{1,1},{1,0}};
        for (int[] corner : corners) {
            float x, y, z;
            if (face <= 2) { x = corner[1]; y = plane; z = corner[0]; }
            else if (face <= 4) { x = corner[0]; y = corner[1]; z = plane; }
            else { x = plane; y = corner[0]; z = corner[1]; }
            result.putFloat(x).putFloat(y).putFloat(z).putInt(tag)
                .putInt(0x44556677).putInt(0x77889900).putInt(0x11223344);
        }
        result.flip();
        return result;
    }
}
