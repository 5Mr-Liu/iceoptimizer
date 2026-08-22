package dev.rlcraft.ice.optimizer.render.visibility;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import dev.rlcraft.ice.optimizer.compat.chunk.TerrainRenderChunkIndexAccessor;
import dev.rlcraft.ice.optimizer.compat.chunk.TerrainRenderInfoAccessor;
import dev.rlcraft.ice.optimizer.compat.chunk.TerrainVisibilityAccessor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import net.minecraft.util.math.BlockPos;
import org.junit.Before;
import org.junit.Test;

public final class PrimitiveTerrainVisibilityBridgeTest {
    @Before
    public void reset() {
        PrimitiveTerrainVisibilityBridge.resetForTest();
        OptimizerRegistry.breaker(OptimizationModule.MODERN_VISIBILITY_GRID)
            .configure(true, 3);
        OptimizerRegistry.breaker(OptimizationModule.MODERN_VISIBILITY_HZB)
            .configure(true, 3);
    }

    @Test
    public void onlineLifecycleFallsThroughDuringWarmupThenPreservesExactBfsOrder() {
        FakeOwner owner = FakeOwner.line(3);
        for (int frame = 1; frame <= 120; frame++) {
            assertFalse(runFrame(owner, frame));
        }
        assertTrue(runFrame(owner, 121));
        assertEquals(Arrays.asList(1, 0, 2), owner.outputIndices());
        assertEquals(Arrays.asList(FakeDirection.DOWN, FakeDirection.UP,
            FakeDirection.NORTH, FakeDirection.SOUTH, FakeDirection.WEST,
            FakeDirection.EAST), owner.firstExpansionDirections());
    }

    @Test
    public void modernTraversalMarksAChunkBeforeItsFrustumTest() {
        FakeOwner owner = FakeOwner.line(3);
        for (int frame = 1; frame <= 120; frame++) runFrame(owner, frame);
        owner.camera.hiddenIndex = 2;
        assertTrue(runFrame(owner, 121));
        assertEquals(Arrays.asList(1, 0), owner.outputIndices());
        assertEquals(121, owner.chunks[2].lastFrame);
        assertTrue(owner.camera.observedMarkedBeforeTest);
    }

    @Test
    public void failedPreflightDoesNotConsumeTheLegacySeedQueue() {
        FakeOwner owner = FakeOwner.line(1);
        Queue<Object> incompatible = new java.util.LinkedList<Object>();
        owner.chunks[0].ice$setFrameIndex(1);
        incompatible.add(new FakeInfo(owner.chunks[0], null, 0));
        assertFalse(PrimitiveTerrainVisibilityBridge.tryTraverse(owner, incompatible,
            Integer.valueOf(0), owner.camera, 1, true, false, 0, false));
        assertEquals(1, incompatible.size());
        assertTrue(owner.infos.isEmpty());
    }

    @Test
    public void unseenWorkloadCapturesLegacyBeforeValidationCanAdvance() {
        FakeOwner owner = FakeOwner.line(3);
        for (int frame = 1; frame <= 120; frame++) runFrame(owner, frame);
        assertFalse(runFrame(owner, 121, 99));
        assertTrue(runFrame(owner, 122, 99));
    }

    @Test
    public void outputMismatchFusesOnlyThePrimitiveGrid() {
        FakeOwner owner = FakeOwner.line(3);
        for (int frame = 1; frame <= 120; frame++) runFrame(owner, frame);
        owner.modernCounterBias = 1;
        assertTrue(runFrame(owner, 121));
        assertFalse(OptimizerRegistry.breaker(
            OptimizationModule.MODERN_VISIBILITY_GRID).isOperational());
        assertTrue(OptimizerRegistry.breaker(
            OptimizationModule.MODERN_VISIBILITY_HZB).isOperational());
    }

    @Test
    public void declinedOriginNeverInvokesItsObservableHashCode() {
        FakeOwner owner = FakeOwner.line(1);
        ArrayDeque<Object> queue = new ArrayDeque<Object>();
        owner.chunks[0].ice$setFrameIndex(1);
        queue.add(new FakeInfo(owner.chunks[0], null, 0));
        Object origin = new Object() {
            @Override public int hashCode() {
                throw new AssertionError("origin hashCode must not be observed");
            }
        };
        assertFalse(PrimitiveTerrainVisibilityBridge.tryTraverse(owner, queue,
            origin, owner.camera, 1, true, false, 0, false));
        assertEquals(1, queue.size());
    }

    @Test
    public void optifineTraversalPreservesPreOffsetPathFilteringAndSideLists() {
        FakeOwner owner = FakeOwner.optifineLine(3);
        for (int frame = 1; frame <= 120; frame++) {
            assertFalse(runFrame(owner, frame, 1, (byte) 1));
        }
        assertTrue(runFrame(owner, 121, 1, (byte) 1));
        assertEquals(Arrays.asList(1, 0, 2), owner.outputIndices());
        assertEquals(Arrays.asList(1, 0, 2), owner.entityOutputIndices());
        assertEquals(Arrays.asList(1, 0, 2), owner.tileOutputIndices());
        assertEquals(Arrays.asList(FakeDirection.DOWN, FakeDirection.NORTH,
            FakeDirection.SOUTH, FakeDirection.WEST, FakeDirection.EAST),
            owner.firstExpansionDirections(5));
        assertTrue(owner.camera.receivedOptifineNullBounds);
    }

    private static boolean runFrame(FakeOwner owner, int frame) {
        FakeChunk start = owner.chunks[1 < owner.chunks.length ? 1 : 0];
        return runFrame(owner, frame, start.index);
    }

    private static boolean runFrame(FakeOwner owner, int frame, int originKey) {
        return runFrame(owner, frame, originKey, (byte) 0);
    }

    private static boolean runFrame(FakeOwner owner, int frame, int originKey,
                                    byte seedPath) {
        owner.beginFrame();
        ArrayDeque<Object> queue = new ArrayDeque<Object>();
        FakeChunk start = owner.chunks[1 < owner.chunks.length ? 1 : 0];
        assertTrue(start.ice$setFrameIndex(frame));
        FakeInfo seed = new FakeInfo(start, null, 0);
        seed.path = seedPath;
        queue.add(seed);
        boolean modern = PrimitiveTerrainVisibilityBridge.tryTraverse(owner, queue,
            new BlockPos(originKey, 0, 0), owner.camera, frame, true,
            owner.optifine, 12, owner.optifine);
        if (!modern) owner.legacy(queue, frame, true);
        PrimitiveTerrainVisibilityBridge.afterTraversal(owner, modern);
        return modern;
    }

    private enum FakeDirection {
        DOWN, UP, NORTH, SOUTH, WEST, EAST;
        private FakeDirection opposite() { return values()[ordinal() ^ 1]; }
    }

    private static final class FakeOwner implements TerrainVisibilityAccessor {
        private static final long ALL = (1L << 36) - 1L;
        private final FakeChunk[] chunks;
        private final FakeCamera camera = new FakeCamera();
        private List<Object> infos = new ArrayList<Object>();
        private List<Object> entityInfos = new ArrayList<Object>();
        private List<Object> tileInfos = new ArrayList<Object>();
        private final List<FakeDirection> offsetCalls = new ArrayList<FakeDirection>();
        private final boolean optifine;
        private int modernCounterBias;

        private FakeOwner(int count, boolean optifine) {
            this.optifine = optifine;
            chunks = new FakeChunk[count];
            for (int i = 0; i < count; i++) chunks[i] = new FakeChunk(i, ALL);
        }

        private static FakeOwner line(int count) { return new FakeOwner(count, false); }
        private static FakeOwner optifineLine(int count) {
            return new FakeOwner(count, true);
        }

        private void beginFrame() {
            infos = new ArrayList<Object>();
            entityInfos = new ArrayList<Object>();
            tileInfos = new ArrayList<Object>();
            offsetCalls.clear();
            camera.observedMarkedBeforeTest = false;
            camera.receivedOptifineNullBounds = false;
        }

        private void legacy(Queue<Object> queue, int frame, boolean many) {
            while (!queue.isEmpty()) {
                FakeInfo info = (FakeInfo) queue.poll();
                FakeChunk current = info.chunk;
                ice$appendRenderInfo(info, current);
                for (FakeDirection direction : FakeDirection.values()) {
                    if (optifine && many && (info.path
                        & 1 << direction.opposite().ordinal()) != 0) continue;
                    if (optifine && many && info.incoming != null
                        && !current.ice$isVisible(info.incoming.opposite(), direction)) {
                        continue;
                    }
                    FakeChunk neighbor = (FakeChunk) ice$getRenderChunkOffset(
                        Integer.valueOf(current.index), current, direction,
                        optifine, 12);
                    if (!optifine && many && (info.has(direction.opposite())
                        || (info.incoming != null && !current.ice$isVisible(
                            info.incoming.opposite(), direction)))) continue;
                    if (neighbor != null && neighbor.ice$setFrameIndex(frame)
                        && ice$isInFrustum(neighbor, camera,
                            optifine ? null : neighbor, frame)) {
                        FakeInfo next = new FakeInfo(neighbor, direction,
                            optifine ? 0 : info.counter + 1);
                        next.ice$setDirection(info.path, direction);
                        queue.add(next);
                    }
                }
            }
        }

        private List<Integer> outputIndices() {
            List<Integer> result = new ArrayList<Integer>();
            for (Object value : infos) result.add(((FakeInfo) value).chunk.index);
            return result;
        }

        private List<FakeDirection> firstExpansionDirections() {
            return firstExpansionDirections(6);
        }

        private List<FakeDirection> firstExpansionDirections(int count) {
            return new ArrayList<FakeDirection>(offsetCalls.subList(0,
                Math.min(count, offsetCalls.size())));
        }

        private List<Integer> entityOutputIndices() {
            return indices(entityInfos);
        }

        private List<Integer> tileOutputIndices() {
            return indices(tileInfos);
        }

        private static List<Integer> indices(List<Object> source) {
            List<Integer> result = new ArrayList<Integer>();
            for (Object value : source) result.add(((FakeInfo) value).chunk.index);
            return result;
        }

        @Override public List ice$renderInfos() { return infos; }
        @Override public List ice$renderInfosEntities() {
            return optifine ? entityInfos : null;
        }
        @Override public List ice$renderInfosTileEntities() {
            return optifine ? tileInfos : null;
        }
        @Override public boolean ice$isOptifineTraversal() { return optifine; }
        @Override public void ice$appendRenderInfo(Object info, Object chunk) {
            infos.add(info);
            if (optifine) {
                entityInfos.add(info);
                tileInfos.add(info);
            }
        }
        @Override public Object[] ice$renderChunks() { return chunks; }
        @Override public Object[] ice$directions() { return FakeDirection.values(); }

        @Override
        public Object ice$getRenderChunkOffset(Object origin, Object chunk,
                                               Object direction, boolean fog,
                                               int renderDistance) {
            FakeDirection facing = (FakeDirection) direction;
            offsetCalls.add(facing);
            int index = ((FakeChunk) chunk).index;
            if (facing == FakeDirection.WEST) index--;
            else if (facing == FakeDirection.EAST) index++;
            else return null;
            return index >= 0 && index < chunks.length ? chunks[index] : null;
        }

        @Override public Object ice$oppositeDirection(Object direction) {
            return ((FakeDirection) direction).opposite();
        }

        @Override public Object ice$newRenderInfo(Object chunk, Object direction, int counter) {
            return new FakeInfo((FakeChunk) chunk, (FakeDirection) direction,
                counter + modernCounterBias);
        }

        @Override public boolean ice$isInFrustum(Object chunk, Object camera,
                                                 Object bounds, int frameIndex) {
            FakeCamera target = (FakeCamera) camera;
            if (optifine && bounds == null) target.receivedOptifineNullBounds = true;
            return target.visible((FakeChunk) chunk);
        }
    }

    private static final class FakeChunk implements TerrainRenderChunkIndexAccessor {
        private final int index;
        private final long mask;
        private int lastFrame = Integer.MIN_VALUE;

        private FakeChunk(int index, long mask) {
            this.index = index;
            this.mask = mask;
        }

        @Override public int ice$renderChunkIndex() { return index; }
        @Override public boolean ice$setFrameIndex(int frameIndex) {
            if (lastFrame == frameIndex) return false;
            lastFrame = frameIndex;
            return true;
        }
        @Override public Object ice$bounds() { return this; }
        @Override public long ice$visibilityMask() { return mask; }
        @Override public boolean ice$isVisible(Object from, Object to) {
            int left = ((FakeDirection) from).ordinal();
            int right = ((FakeDirection) to).ordinal();
            return (mask & (1L << (left * 6 + right))) != 0L;
        }
    }

    private static final class FakeInfo implements TerrainRenderInfoAccessor {
        private final FakeChunk chunk;
        private final FakeDirection incoming;
        private final int counter;
        private byte path;

        private FakeInfo(FakeChunk chunk, FakeDirection incoming, int counter) {
            this.chunk = chunk;
            this.incoming = incoming;
            this.counter = counter;
        }

        private boolean has(FakeDirection direction) {
            return (path & 1 << direction.ordinal()) != 0;
        }

        @Override public Object ice$renderChunk() { return chunk; }
        @Override public Object ice$incomingDirection() { return incoming; }
        @Override public byte ice$pathDirections() { return path; }
        @Override public int ice$counter() { return counter; }
        @Override public void ice$setDirection(byte previous, Object direction) {
            path = (byte) (path | previous | 1 << ((FakeDirection) direction).ordinal());
        }
        @Override public boolean ice$isCanonicalRenderInfo() { return true; }
    }

    private static final class FakeCamera {
        private int hiddenIndex = -1;
        private boolean observedMarkedBeforeTest;
        private boolean receivedOptifineNullBounds;
        private boolean visible(FakeChunk chunk) {
            if (chunk.index == hiddenIndex) {
                observedMarkedBeforeTest = chunk.lastFrame != Integer.MIN_VALUE;
                return false;
            }
            return true;
        }
    }
}
