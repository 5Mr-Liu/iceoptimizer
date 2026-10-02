package dev.rlcraft.ice.optimizer.compat.save;

import static org.junit.Assert.*;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Random;
import net.minecraft.block.state.IBlockState;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.BitArray;
import net.minecraft.world.chunk.IBlockStatePalette;
import net.minecraft.world.chunk.NibbleArray;
import org.junit.Test;

public final class ChunkSectionNbtBridgeTest {
    @Test public void allLocalPaletteWidthsMatchLegacyIncludingCrossWordAndHighIds() {
        Random random = new Random(713L);
        for (int bits = 4; bits <= 8; bits++) {
            BitArray storage = new BitArray(bits, 4096);
            int[] ids = new int[1 << bits];
            for (int i = 0; i < ids.length; i++) ids[i] = random.nextInt(65536);
            ids[0] = -1;
            for (int i = 0; i < 4096; i++) storage.setAt(i, random.nextInt(ids.length));
            assertPackedEquivalent(storage, bits, ids);
        }
    }

    @Test public void lowIdsOmitAddArrayAndOverwritePreviouslyFilledMetadata() {
        BitArray storage = new BitArray(4, 4096);
        int[] ids = new int[16];
        for (int i = 0; i < 16; i++) ids[i] = i * 173;
        for (int i = 0; i < 4096; i++) storage.setAt(i, i & 15);
        assertPackedEquivalent(storage, 4, ids);
        assertNull(ChunkSectionNbtBridge.pack(storage.getBackingLongArray(), 4, ids,
            new byte[4096], new byte[2048]));
    }

    @Test public void paletteIdsAreResolvedOncePerSlotAndRefreshedOnEverySave() {
        CountingPalette palette = new CountingPalette();
        IdentityHashMap<IBlockState, Integer> direct = new IdentityHashMap<IBlockState, Integer>();
        for (int i = 0; i < 16; i++) direct.put(palette.states[i], i + 42);
        int[] ids = new int[256];
        assertTrue(ChunkSectionNbtBridge.resolvePalette(palette, 4, direct, ids, palette.states[0]));
        assertEquals(16, palette.reads);
        assertEquals(42, ids[0]);
        direct.put(palette.states[0], 8100);
        assertTrue(ChunkSectionNbtBridge.resolvePalette(palette, 4, direct, ids, palette.states[0]));
        assertEquals(32, palette.reads);
        assertEquals(8100, ids[0]);
    }

    @Test public void missingDirectStateDeclinesMemoizationBeforeAnySerialization() {
        CountingPalette palette = new CountingPalette();
        IdentityHashMap<IBlockState, Integer> direct = new IdentityHashMap<IBlockState, Integer>();
        for (int i = 0; i < 15; i++) direct.put(palette.states[i], i);
        assertFalse(ChunkSectionNbtBridge.resolvePalette(palette, 4, direct, new int[256], palette.states[0]));
    }

    @Test public void emptyPaletteSlotsUseTheOriginalSectionAirState() {
        CountingPalette palette = new CountingPalette();
        IBlockState air = palette.states[0];
        palette.states[15] = null;
        IdentityHashMap<IBlockState, Integer> direct = new IdentityHashMap<IBlockState, Integer>();
        for (int i = 0; i < 15; i++) direct.put(palette.states[i], i + 100);
        int[] ids = new int[256];
        assertTrue(ChunkSectionNbtBridge.resolvePalette(palette, 4, direct, ids, air));
        assertEquals(100, ids[15]);
    }

    private static void assertPackedEquivalent(BitArray storage, int bits, int[] ids) {
        byte[] expectedBlocks = new byte[4128];
        Arrays.fill(expectedBlocks, (byte) 91);
        NibbleArray expectedMetadata = new NibbleArray();
        Arrays.fill(expectedMetadata.getData(), (byte) 255);
        NibbleArray expectedAdd = null;
        for (int i = 0; i < 4096; i++) {
            int id = ids[storage.getAt(i)];
            int x = i & 15, y = (i >> 8) & 15, z = (i >> 4) & 15;
            if (((id >> 12) & 15) != 0) {
                if (expectedAdd == null) expectedAdd = new NibbleArray();
                expectedAdd.set(x, y, z, (id >> 12) & 15);
            }
            expectedBlocks[i] = (byte) ((id >> 4) & 255);
            expectedMetadata.set(x, y, z, id & 15);
        }
        byte[] blocks = new byte[4128];
        Arrays.fill(blocks, (byte) 91);
        byte[] metadata = new byte[2048];
        Arrays.fill(metadata, (byte) 255);
        NibbleArray add = ChunkSectionNbtBridge.pack(storage.getBackingLongArray(), bits, ids, blocks, metadata);
        assertArrayEquals(expectedBlocks, blocks);
        assertArrayEquals(expectedMetadata.getData(), metadata);
        if (expectedAdd == null) assertNull(add);
        else assertArrayEquals(expectedAdd.getData(), add.getData());
    }

    private static final class CountingPalette implements IBlockStatePalette {
        final IBlockState[] states = new IBlockState[16];
        int reads;
        CountingPalette() {
            for (int i = 0; i < states.length; i++) {
                states[i] = (IBlockState) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] { IBlockState.class }, (proxy, method, args) -> null);
            }
        }
        @Override public IBlockState getBlockState(int id) { reads++; return states[id]; }
        @Override public int idFor(IBlockState state) { throw new AssertionError(); }
        @Override public void read(PacketBuffer buffer) { throw new AssertionError(); }
        @Override public void write(PacketBuffer buffer) { throw new AssertionError(); }
        @Override public int getSerializedSize() { throw new AssertionError(); }
    }
}
