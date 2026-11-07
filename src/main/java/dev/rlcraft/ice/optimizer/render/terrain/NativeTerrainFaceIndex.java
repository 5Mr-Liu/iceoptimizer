package dev.rlcraft.ice.optimizer.render.terrain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Upload-time face metadata; index filtering preserves the original quad order. */
final class NativeTerrainFaceIndex {
    private final byte[] faces;
    private final byte[] blockFaces;
    private final int commonBlockFaces;
    private final double down, up, north, south, west, east;
    private int present = 1;

    NativeTerrainFaceIndex(ByteBuffer vertices) {
        this(vertices, 1, 0);
    }

    NativeTerrainFaceIndex(ByteBuffer vertices, float scale, float translation) {
        faces = new byte[vertices.remaining() / 112];
        blockFaces = new byte[(faces.length + 63) / 64];
        float down = Float.NEGATIVE_INFINITY, up = Float.POSITIVE_INFINITY;
        float north = Float.NEGATIVE_INFINITY, south = Float.POSITIVE_INFINITY;
        float west = Float.NEGATIVE_INFINITY, east = Float.POSITIVE_INFINITY;
        int base = vertices.position();
        for (int quad = 0; quad < faces.length; quad++) {
            int offset = base + quad * 112;
            int face = TerrainQuadClassifier.classifyKnownRange(vertices, offset, 28);
            faces[quad] = (byte) face; present |= 1 << face;
            blockFaces[quad / 64] |= 1 << face;
            if (face != 0) {
                int axis = face <= 2 ? 4 : face <= 4 ? 8 : 0;
                float plane = vertices.getFloat(offset + axis);
                switch (face) {
                    case 1: down = Math.max(down, plane); break;
                    case 2: up = Math.min(up, plane); break;
                    case 3: north = Math.max(north, plane); break;
                    case 4: south = Math.min(south, plane); break;
                    case 5: west = Math.max(west, plane); break;
                    case 6: east = Math.min(east, plane); break;
                    default: break;
                }
            }
        }
        int common = 127;
        for (int block = 0; block < faces.length / 64; block++) common &= blockFaces[block];
        commonBlockFaces = common;
        // Fold the fixed positive model scale/translation into the six planes.
        // The next representable bound keeps rounding at a boundary conservative.
        this.down = Math.nextUp((down + .001) * scale + translation);
        this.up = Math.nextDown((up - .001) * scale + translation);
        this.north = Math.nextUp((north + .001) * scale + translation);
        this.south = Math.nextDown((south - .001) * scale + translation);
        this.west = Math.nextUp((west + .001) * scale + translation);
        this.east = Math.nextDown((east - .001) * scale + translation);
    }

    int visibleMask(double x, double y, double z) {
        // The margin only retains additional geometry at a face plane.
        int mask = 1;
        if (!(y > down)) mask |= 2;
        if (!(y < up)) mask |= 4;
        if (!(z > north)) mask |= 8;
        if (!(z < south)) mask |= 16;
        if (!(x > west)) mask |= 32;
        if (!(x < east)) mask |= 64;
        return mask & present;
    }

    int allFaces() { return present; }

    int writeShortIndices(ByteBuffer target, int mask, int firstVertex, int vertexCount) {
        return writeShortIndices(target, mask, firstVertex, vertexCount, firstVertex);
    }

    int writeShortIndices(ByteBuffer target, int mask, int firstVertex, int vertexCount, int baseVertex) {
        int before = target.position();
        if ((mask & present) == present) {
            NativeTerrainSequentialIndices.append(target, firstVertex - baseVertex, vertexCount);
            return vertexCount;
        }
        int end = (firstVertex + vertexCount) / 4;
        // If every full block contains both accepted and rejected faces, a
        // block walk cannot skip/copy any of them. Keep the direct scalar loop.
        if ((commonBlockFaces & mask) != 0 && (commonBlockFaces & ~mask) != 0) {
            appendMixed(target, mask, firstVertex / 4, end, baseVertex);
            return (target.position() - before) / 2;
        }
        for (int face = firstVertex / 4; face < end;) {
            int blockEnd = Math.min(end, (face / 64 + 1) * 64);
            int included = blockFaces[face / 64] & mask;
            if (included == (blockFaces[face / 64] & 255)) {
                NativeTerrainSequentialIndices.append(target, face * 4 - baseVertex, (blockEnd - face) * 4);
            } else if (included != 0) {
                appendMixed(target, mask, face, blockEnd, baseVertex);
            }
            face = blockEnd;
        }
        return (target.position() - before) / 2;
    }

    private void appendMixed(ByteBuffer target, int mask, int start, int end, int baseVertex) {
        long quad = quadPattern(target);
        for (int face = start; face < end; face++) if ((mask & 1 << faces[face]) != 0) {
            int first = face * 4 - baseVertex;
            target.putLong(quad + first * 0x0001000100010001L);
        }
    }

    static void writeSequentialShortIndices(ByteBuffer target, int count) {
        NativeTerrainSequentialIndices.append(target, 0, count);
    }

    private static long quadPattern(ByteBuffer target) {
        // Four consecutive unsigned shorts in their original order, with one
        // buffer bounds/position update. Each renderer part is <= 65532 vertices.
        return target.order() == ByteOrder.LITTLE_ENDIAN
            ? 0x0003000200010000L : 0x0000000100020003L;
    }
}
