package dev.rlcraft.ice.optimizer.render.terrain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;
import static org.junit.Assert.*;

public final class TerrainBindlessCommandTest {
    @Test public void secondVboPromotesOverlappingHeadersWithoutChangingOrder() throws Exception {
        LwjglTerrainBatchRenderer renderer = new LwjglTerrainBatchRenderer(null, null);
        ByteBuffer bytes = ByteBuffer.allocate(2048).order(ByteOrder.nativeOrder());
        set(renderer, "bytes", bytes);
        set(renderer, "commandBase", 32);
        set(renderer, "crossPage", true);
        LwjglTerrainBatchRenderer.Page first = renderer.newPage(10), second = renderer.newPage(20);
        long a = 0x123400000000L, b = 0x234500000000L;
        set(first, "gpuAddress", a); set(first, "storageBytes", 4096L);
        set(second, "gpuAddress", b); set(second, "storageBytes", 8192L);
        for (int i = 0; i < 10; i++) renderer.command(first, 4 * (i + 1), i * 12, i * 3);
        assertEquals(16, renderer.commandStrideBytes());
        assertEquals(10, renderer.commandCount());
        renderer.command(second, 52, 144, 36);
        assertEquals(112, renderer.commandStrideBytes());
        assertEquals(11, renderer.commandCount());
        for (int i = 0; i < 10; i++) {
            int base = 32 + i * 112;
            assertEquals(4 * (i + 1), bytes.getInt(base));
            assertEquals(1, bytes.getInt(base + 4));
            assertEquals(i * 12, bytes.getInt(base + 8));
            assertEquals(i * 3, bytes.getInt(base + 12));
            assertEquals(a, bytes.getLong(base + 24));
            assertEquals(a + 24, bytes.getLong(base + 16 + 3 * 24 + 8));
        }
        int last = 32 + 10 * 112;
        assertEquals(52, bytes.getInt(last));
        assertEquals(36, bytes.getInt(last + 12));
        assertEquals(b, bytes.getLong(last + 24));
        assertEquals(8192L, bytes.getLong(last + 32));
        renderer.command(first, 64, 180, 45);
        assertEquals(a, bytes.getLong(32 + 11 * 112 + 24));
    }

    private static void set(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true); field.set(target, value);
    }

    @Test public void nvCommandUses64BitAddressesAnd24ByteAttributeRecords() {
        ByteBuffer bytes = ByteBuffer.allocate(256).order(ByteOrder.nativeOrder());
        long address = 0x123456789ABC0000L;
        int base = 32;
        LwjglTerrainBatchRenderer.putCommand(bytes, base, 96, 24, 17);
        LwjglTerrainBatchRenderer.putBindlessPointers(bytes, base + 16, address, 8192);
        assertEquals(112, LwjglTerrainBatchRenderer.BINDLESS_COMMAND_BYTES);
        assertEquals(96, bytes.getInt(base));
        assertEquals(1, bytes.getInt(base + 4));
        assertEquals(24, bytes.getInt(base + 8));
        assertEquals(17, bytes.getInt(base + 12));
        int[] offsets = {0, 12, 16, 24};
        for (int i = 0; i < 4; i++) {
            int pointer = base + 16 + 24 * i;
            assertEquals(8 + i, bytes.getInt(pointer));
            assertEquals(0, bytes.getInt(pointer + 4));
            assertEquals(address + offsets[i], bytes.getLong(pointer + 8));
            assertEquals(8192L - offsets[i], bytes.getLong(pointer + 16));
        }
        // GPU visibility rewrites instanceCount only; addresses and draw order
        // remain the same, including a high unsigned GPU address bit pattern.
        bytes.putInt(base + 4, 0);
        assertEquals(address + 24, bytes.getLong(base + 16 + 3 * 24 + 8));
        assertEquals(17, bytes.getInt(base + 12));
    }
}
