package dev.rlcraft.ice.optimizer.render.terrain;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.compat.chunk.TerrainRenderListAccessor;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.arena.ArenaRange;
import dev.rlcraft.ice.optimizer.render.arena.GpuArenaAllocator;
import dev.rlcraft.ice.optimizer.render.backend.CapabilityReport;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.BlockRenderLayer;
import org.junit.Test;

public final class LwjglTerrainArenaDataTest {
    @Test public void pressureCapacityUsesAvailableSpaceWithoutReleasingTheOldPageEarly() {
        long mib = 1024L * 1024L;
        assertEquals(13L * mib,
            LwjglTerrainArena.physicalPageCapacityUnderBudget(9L * mib, 16L * mib, 13L * mib));
        assertEquals(13L * mib,
            LwjglTerrainArena.physicalPageCapacityUnderBudget(9L * mib, 16L * mib, 13L * mib + 112L));
        assertEquals(9L * mib + 112L,
            LwjglTerrainArena.physicalPageCapacityUnderBudget(9L * mib + 112L, 16L * mib, 9L * mib + 224L));
        assertEquals(0L,
            LwjglTerrainArena.physicalPageCapacityUnderBudget(9L * mib, 16L * mib, 9L * mib - 1L));
    }

    @Test public void physicalPageGrowthTracksExtentWithoutAllocatingTheLogicalMaximum() {
        long maximum = 16L * 1024L * 1024L - 16L * 1024L * 1024L % 112L;
        assertEquals(256L * 1024L, LwjglTerrainArena.physicalPageCapacity(112L, maximum));
        assertEquals(256L * 1024L, LwjglTerrainArena.physicalPageCapacity(256L * 1024L, maximum));
        assertEquals(512L * 1024L, LwjglTerrainArena.physicalPageCapacity(256L * 1024L + 112L, maximum));
        assertEquals(maximum, LwjglTerrainArena.physicalPageCapacity(maximum, maximum));
        assertEquals(maximum, LwjglTerrainArena.physicalPageCapacity(112L, maximum, false));
        assertEquals(maximum, LwjglTerrainArena.physicalPageCapacity(maximum, maximum, false));
        try {
            LwjglTerrainArena.physicalPageCapacity(maximum + 1L, maximum);
            fail("physical storage must not cross a logical page boundary");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void whollyUnownedNativeListStaysUntouchedWithoutGlContext() throws Exception {
        LwjglTerrainArena arena = arena(1024L * 1024L);
        Field buffer = LwjglTerrainArena.class.getDeclaredField("bufferId");
        buffer.setAccessible(true);
        buffer.setInt(arena, 1);
        arena.configureNativeBatching(true, true);
        final List<RenderChunk> visible = new ArrayList<RenderChunk>();
        visible.add(null);
        visible.add(null);
        TerrainRenderListAccessor container = new TerrainRenderListAccessor() {
            public List<RenderChunk> ice$renderChunks() { return visible; }
            public boolean ice$initialized() { return true; }
            public double ice$viewEntityX() { throw new AssertionError("camera queried without owned mesh"); }
            public double ice$viewEntityY() { throw new AssertionError("camera queried without owned mesh"); }
            public double ice$viewEntityZ() { throw new AssertionError("camera queried without owned mesh"); }
        };
        assertFalse(arena.render(container, BlockRenderLayer.SOLID, true, true, 1L));
        assertEquals(2, visible.size());
        assertEquals("not-initialized", arena.getShaderBatchDetail());
        assertEquals(0L, arena.getDrawCalls());
        assertEquals(0L, arena.getShaderBatchFallbacks());
        assertFalse(arena.wasLastSubmissionStarted());
        assertNull(arena.consumeShaderBatchFailure());
        // No context exists: even an attempted state query would fail here.
        // Also exercise the ordinary arena route used after native fallback.
        assertFalse(arena.render(container, BlockRenderLayer.SOLID, false, false, 2L));
        assertEquals(2, visible.size());
    }

    @Test public void renderRegionsNormalizationDoesNotExpandOrAddChunkOriginTwice() {
        ByteBuffer source = ByteBuffer.allocate(28).order(ByteOrder.nativeOrder());
        source.putFloat(0, 241.25F).putFloat(4, 67.5F).putFloat(8, 31.75F);
        for (int index = 12; index < 28; index++) source.put(index, (byte) (index * 7));
        ByteBuffer result = ByteBuffer.allocate(28).order(ByteOrder.nativeOrder());
        LwjglTerrainArena.normalizeIntoStaging(source, 28, 28, 28,
            -256, 0, 512, 1F, 0F, -256, 0, 512, result);
        assertArrayEquals(source.array(), result.array());
        // Region drawing uses Y=0 even when the arena owns a different Y page.
        LwjglTerrainArena.normalizeIntoStaging(source, 28, 28, 28,
            -256, 0, 512, 1F, 0F, -256, 256, 512, result);
        assertEquals(67.5F - 256F, result.getFloat(4), 0F);
        assertEquals(241.25F, result.getFloat(0), 0F);
    }
    @Test
    public void coveragePackingBoundsCountsWithoutAllocatingSnapshots() {
        long packed = LwjglTerrainArena.packCoverage(100, 75, 12);
        assertEquals(100, LwjglTerrainArena.coverageVisible(packed));
        assertEquals(75, LwjglTerrainArena.coverageOwned(packed));
        assertEquals(12, LwjglTerrainArena.coverageRegionRuns(packed));

        long bounded = LwjglTerrainArena.packCoverage(4, 9, 8);
        assertEquals(4, LwjglTerrainArena.coverageVisible(bounded));
        assertEquals(4, LwjglTerrainArena.coverageOwned(bounded));
        assertEquals(4, LwjglTerrainArena.coverageRegionRuns(bounded));
    }

    @Test
    public void retirementQueuePublicationFailureStrandsWithoutEscaping() {
        final Throwable injected = new IllegalStateException("queue failure");
        ArrayDeque<ArenaRange> queue = new ArrayDeque<ArenaRange>() {
            @Override public void addLast(ArenaRange range) { throwUnchecked(injected); }
        };
        CacheBudget budget = new CacheBudget(1024L, 1024L * 1024L,
            32L * 1024L * 1024L);
        RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        ResourceLedger ledger = new ResourceLedger(guard, budget,
            new ResourceLedger.Destroyer() {
                @Override public void destroy(RenderResourceKind kind,
                                              int nativeId) { }
            }, 16);
        LwjglTerrainArena arena = new LwjglTerrainArena(guard, ledger, budget,
            CapabilityReport.builder().build(), 16L * 1024L * 1024L, 1L,
            queue);
        GpuArenaAllocator allocator = new GpuArenaAllocator(128L, 128L, 4L, 1L);
        ArenaRange range = allocator.allocate(28L);
        arena.retireRangeForTest(range);
        assertEquals(1L, arena.getStrandedRanges());
        assertEquals(28L, arena.getStrandedBytes());
        assertSame(injected, arena.consumePublicationFailure());
    }

    @Test
    public void retirementQueueWrappedFatalStrandsThenEscapes() {
        final OutOfMemoryError fatal = new OutOfMemoryError(
            "injected terrain retirement fatal");
        final IllegalStateException wrapped = new IllegalStateException(
            "wrapped terrain retirement fatal", fatal);
        ArrayDeque<ArenaRange> queue = new ArrayDeque<ArenaRange>() {
            @Override public void addLast(ArenaRange range) {
                throw wrapped;
            }
        };
        CacheBudget budget = new CacheBudget(1024L, 1024L * 1024L,
            32L * 1024L * 1024L);
        RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        ResourceLedger ledger = new ResourceLedger(guard, budget,
            new ResourceLedger.Destroyer() {
                @Override public void destroy(RenderResourceKind kind,
                                              int nativeId) { }
            }, 16);
        LwjglTerrainArena arena = new LwjglTerrainArena(guard, ledger, budget,
            CapabilityReport.builder().build(), 16L * 1024L * 1024L, 1L,
            queue);
        GpuArenaAllocator allocator = new GpuArenaAllocator(128L, 128L, 4L,
            1L);
        ArenaRange range = allocator.allocate(28L);
        try {
            arena.retireRangeForTest(range);
            fail("wrapped retirement fatal must escape");
        } catch (OutOfMemoryError expected) {
            assertSame(fatal, expected);
        }
        assertEquals(1L, arena.getStrandedRanges());
        assertEquals(28L, arena.getStrandedBytes());
        assertSame(wrapped, arena.consumePublicationFailure());
    }

    @Test
    public void regionOriginsUseFloorDivisionAcrossZero() {
        assertEquals(-512, LwjglTerrainArena.regionOriginForTest(-257));
        assertEquals(-256, LwjglTerrainArena.regionOriginForTest(-256));
        assertEquals(-256, LwjglTerrainArena.regionOriginForTest(-1));
        assertEquals(0, LwjglTerrainArena.regionOriginForTest(0));
        assertEquals(0, LwjglTerrainArena.regionOriginForTest(255));
        assertEquals(256, LwjglTerrainArena.regionOriginForTest(256));
    }

    @Test
    public void normalizationChangesOnlyPositionsAndKeepsRegionLocalPrecision() {
        byte[] source = new byte[28 * 2];
        ByteBuffer data = ByteBuffer.wrap(source).order(ByteOrder.nativeOrder());
        data.putFloat(0, 1.25F);
        data.putFloat(4, 2.5F);
        data.putFloat(8, 3.75F);
        for (int i = 12; i < 28; i++) data.put(i, (byte) (i * 3));
        data.putFloat(28, -2.0F);
        data.putFloat(32, 4.0F);
        data.putFloat(36, 8.0F);
        for (int i = 40; i < source.length; i++) data.put(i, (byte) (i * 5));

        byte[] normalized = new byte[source.length];
        LwjglTerrainArena.normalizeIntoStaging(ByteBuffer.wrap(source), source.length,
            28, normalized.length, new BlockPos(-17, 32, 511), -256, 0, 256,
            ByteBuffer.wrap(normalized));
        ByteBuffer value = ByteBuffer.wrap(normalized).order(ByteOrder.nativeOrder());
        float scale = 1.000001F;
        float translation = 8.0F * scale - 8.0F;
        assertEquals(1.25F * scale + translation + 239.0F, value.getFloat(0), 0.0F);
        assertEquals(2.5F * scale + translation + 32.0F, value.getFloat(4), 0.0F);
        assertEquals(3.75F * scale + translation + 255.0F, value.getFloat(8), 0.0F);
        assertEquals(-2.0F * scale + translation + 239.0F, value.getFloat(28), 0.0F);
        for (int vertex = 0; vertex < 2; vertex++) {
            int base = vertex * 28;
            byte[] expectedTail = new byte[16];
            byte[] actualTail = new byte[16];
            System.arraycopy(source, base + 12, expectedTail, 0, 16);
            System.arraycopy(normalized, base + 12, actualTail, 0, 16);
            assertArrayEquals(expectedTail, actualTail);
        }
    }

    @Test
    public void extendedBlockFormatMustKeepTheExactVanillaPrefix() {
        VertexFormat extended = new VertexFormat()
            .addElement(DefaultVertexFormats.POSITION_3F)
            .addElement(DefaultVertexFormats.COLOR_4UB)
            .addElement(DefaultVertexFormats.TEX_2F)
            .addElement(DefaultVertexFormats.TEX_2S)
            .addElement(DefaultVertexFormats.NORMAL_3B)
            .addElement(DefaultVertexFormats.PADDING_1B);
        VertexFormat reordered = new VertexFormat()
            .addElement(DefaultVertexFormats.POSITION_3F)
            .addElement(DefaultVertexFormats.TEX_2F)
            .addElement(DefaultVertexFormats.COLOR_4UB)
            .addElement(DefaultVertexFormats.TEX_2S);

        assertTrue(LwjglTerrainArena.hasVanillaBlockPrefix(extended));
        assertFalse(LwjglTerrainArena.hasVanillaBlockPrefix(reordered));
    }

    @Test
    public void productionNormalizationPreservesSourceAndExtendedVertexAttributes() {
        for (int stride : new int[] {28, 32, 36, 56}) {
            for (boolean direct : new boolean[] {false, true}) {
                int sourceBytes = stride * 17;
                ByteBuffer storage = (direct ? ByteBuffer.allocateDirect(sourceBytes + 8)
                    : ByteBuffer.allocate(sourceBytes + 8)).order(ByteOrder.nativeOrder());
                for (int i = 0; i < sourceBytes; i++) storage.put(i, (byte) (i * 11 + 3));
                for (int vertex = 0; vertex < 17; vertex++) {
                    storage.putFloat(vertex * stride, vertex + 0.25F);
                    storage.putFloat(vertex * stride + 4, -vertex - 0.5F);
                    storage.putFloat(vertex * stride + 8, vertex * 2.0F);
                }
                byte[] original = new byte[storage.capacity()];
                storage.duplicate().get(original);
                ByteBuffer source = storage.asReadOnlyBuffer();
                source.position(5);
                source.mark();
                ByteBuffer target = (direct ? ByteBuffer.allocateDirect(17 * 28 + 32)
                    : ByteBuffer.allocate(17 * 28 + 32)).order(ByteOrder.nativeOrder());
                ByteBuffer actual = LwjglTerrainArena.normalizeIntoStaging(source,
                    sourceBytes, stride, 17 * 28, new BlockPos(-17, 32, 511),
                    -256, 0, 256, target);
                assertEquals(0, actual.position());
                assertEquals(17 * 28, actual.limit());
                for (int vertex = 0; vertex < 17; vertex++) {
                    float scale = 1.000001F;
                    float translation = 8.0F * scale - 8.0F;
                    assertEquals((vertex + 0.25F) * scale + translation + 239.0F,
                        actual.getFloat(vertex * 28), 0.0F);
                    assertEquals((-vertex - 0.5F) * scale + translation + 32.0F,
                        actual.getFloat(vertex * 28 + 4), 0.0F);
                    assertEquals((vertex * 2.0F) * scale + translation + 255.0F,
                        actual.getFloat(vertex * 28 + 8), 0.0F);
                    for (int offset = 12; offset < 28; offset++) {
                        assertEquals(original[vertex * stride + offset],
                            actual.get(vertex * 28 + offset));
                    }
                }
                assertEquals(5, source.position());
                assertEquals(storage.capacity(), source.limit());
                source.reset();
                byte[] unchanged = new byte[storage.capacity()];
                storage.duplicate().get(unchanged);
                assertArrayEquals(original, unchanged);
            }
        }
    }

    @Test
    public void indirectCommandEncodingMatchesDrawArraysLayoutExactly() {
        ByteBuffer command = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());
        LwjglTerrainArena.putIndirectCommand(command, 24, 28L * 17L);
        command.flip();
        assertEquals(24, command.getInt());
        assertEquals(1, command.getInt());
        assertEquals(17, command.getInt());
        assertEquals(0, command.getInt());
    }

    @Test
    public void indirectFenceFailureAbortsBeforeUsingAHealthySiblingSlot()
        throws Exception {
        LwjglTerrainArena arena = arena();
        Object[] slots = indirectSlots(arena);
        final Throwable injected = new IllegalStateException(
            "injected indirect Fence failure");
        setFence(slots[0], new ResourceLedger.RetirementFence() {
            @Override public boolean isSignaled() { throwUnchecked(injected); return false; }
            @Override public void destroy() { }
        });

        assertNull(acquireIndirectSlot(arena));
        assertSame(injected, arena.consumeIndirectFailure());
    }

    @Test
    public void indirectFenceWrappedFatalEscapesBeforeUsingSibling()
        throws Exception {
        LwjglTerrainArena arena = arena();
        Object[] slots = indirectSlots(arena);
        final OutOfMemoryError fatal = new OutOfMemoryError(
            "injected indirect Fence fatal");
        final IllegalStateException wrapped = new IllegalStateException(
            "wrapped indirect Fence fatal", fatal);
        setFence(slots[0], new ResourceLedger.RetirementFence() {
            @Override public boolean isSignaled() { throw wrapped; }
            @Override public void destroy() { }
        });

        try {
            acquireIndirectSlot(arena);
            fail("wrapped indirect Fence fatal must escape");
        } catch (InvocationTargetException expected) {
            assertSame(fatal, expected.getCause());
        }
        assertSame(wrapped, arena.consumeIndirectFailure());
    }

    @Test
    public void busyIndirectFenceMayUseAHealthySiblingWithoutFailure()
        throws Exception {
        LwjglTerrainArena arena = arena();
        Object[] slots = indirectSlots(arena);
        setFence(slots[0], new ResourceLedger.RetirementFence() {
            @Override public boolean isSignaled() { return false; }
            @Override public void destroy() { }
        });

        assertSame(slots[1], acquireIndirectSlot(arena));
        assertNull(arena.consumeIndirectFailure());
    }

    @Test
    public void chunkPayloadRejectsEveryNonFiniteBound() {
        double[] values = { Double.NaN, Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY };
        for (double invalid : values) {
            double[] bounds = { 0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D };
            bounds[2] = invalid;
            try {
                new ChunkMeshPayload(1L, stamp(), TerrainLayer.SOLID,
                    new byte[0], 0, 28, 0, 0, 0, bounds,
                    new MaterialSegment[0], null, 0L);
                fail("accepted non-finite bound " + invalid);
            } catch (IllegalArgumentException expected) { }
        }
    }

    private static FrameStamp stamp() {
        ClientEpochs epochs = new ClientEpochs();
        epochs.nextFrame();
        return new FrameStamp(1L, 1L, epochs.snapshot());
    }

    private static LwjglTerrainArena arena() {
        return arena(1024L);
    }

    private static LwjglTerrainArena arena(long heapBudget) {
        CacheBudget budget = new CacheBudget(heapBudget, 1024L * 1024L,
            32L * 1024L * 1024L);
        RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        ResourceLedger ledger = new ResourceLedger(guard, budget,
            new ResourceLedger.Destroyer() {
                @Override public void destroy(RenderResourceKind kind,
                                              int nativeId) { }
            }, 16);
        return new LwjglTerrainArena(guard, ledger, budget,
            CapabilityReport.builder().build(), 16L * 1024L * 1024L, 1L);
    }

    private static Object[] indirectSlots(LwjglTerrainArena arena)
        throws Exception {
        Field field = LwjglTerrainArena.class.getDeclaredField("indirectSlots");
        field.setAccessible(true);
        return (Object[]) field.get(arena);
    }

    private static void setFence(Object slot,
                                 ResourceLedger.RetirementFence fence)
        throws Exception {
        Field field = slot.getClass().getDeclaredField("fence");
        field.setAccessible(true);
        field.set(slot, fence);
    }

    private static Object acquireIndirectSlot(LwjglTerrainArena arena)
        throws Exception {
        Method method = LwjglTerrainArena.class.getDeclaredMethod(
            "acquireIndirectSlot", FrameStamp.class);
        method.setAccessible(true);
        return method.invoke(arena, stamp());
    }

    private static void throwUnchecked(Throwable error) {
        LwjglTerrainArenaDataTest.<RuntimeException>throwAny(error);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwAny(Throwable error) throws T {
        throw (T) error;
    }
}
