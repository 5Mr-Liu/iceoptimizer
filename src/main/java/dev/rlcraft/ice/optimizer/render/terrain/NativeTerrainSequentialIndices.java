package dev.rlcraft.ice.optimizer.render.terrain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Shared immutable index bytes, with thread-local cursors for bulk copies. */
final class NativeTerrainSequentialIndices {
    private static final byte[] LITTLE = table(ByteOrder.LITTLE_ENDIAN);
    private static final byte[] BIG = table(ByteOrder.BIG_ENDIAN);
    private static final ThreadLocal<ByteBuffer[]> SOURCES = new ThreadLocal<ByteBuffer[]>() {
        @Override protected ByteBuffer[] initialValue() {
            return new ByteBuffer[] { ByteBuffer.wrap(LITTLE), ByteBuffer.wrap(BIG) };
        }
    };

    private NativeTerrainSequentialIndices() { }

    static void append(ByteBuffer target, int first, int count) {
        ByteBuffer source = SOURCES.get()[target.order() == ByteOrder.LITTLE_ENDIAN ? 0 : 1];
        source.clear();
        source.position(first * 2);
        source.limit((first + count) * 2);
        target.put(source);
    }

    private static byte[] table(ByteOrder order) {
        ByteBuffer data = ByteBuffer.allocate(65536 * 2).order(order);
        for (int i = 0; i < 65536; i++) data.putShort((short) i);
        return data.array();
    }
}
