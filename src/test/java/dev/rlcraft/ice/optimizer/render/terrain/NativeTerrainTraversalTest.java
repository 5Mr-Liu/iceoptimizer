package dev.rlcraft.ice.optimizer.render.terrain;

import dev.rlcraft.ice.optimizer.compat.chunk.*;
import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import org.junit.Test;
import sun.misc.Unsafe;
import static org.junit.Assert.*;

public class NativeTerrainTraversalTest {
    @Test public void shuffledSpatialArrayKeepsBfsOrderAndMergesBothDiamondEntries() throws Exception {
        Owner owner = new Owner();
        NativeTerrainVisibility.State state = new NativeTerrainVisibility.State(owner.spatial);
        run(state, owner);
        assertEquals(Arrays.asList(0, 2, 1, 3, 5, 4), owner.output);
        assertEquals(1, owner.frustumCalls[3]);
    }
    @Test public void frustumRejectionIsVisitedOnceAndEpochWrapDoesNotRetainIt() throws Exception {
        Owner owner = new Owner();
        NativeTerrainVisibility.State state = new NativeTerrainVisibility.State(owner.spatial);
        owner.hidden = 3; run(state, owner);
        assertEquals(Arrays.asList(0, 2, 1), owner.output);
        assertEquals(1, owner.frustumCalls[3]);
        owner.hidden = -1; state.epoch = Integer.MAX_VALUE;
        owner.output.clear(); Arrays.fill(owner.frustumCalls, 0); run(state, owner);
        assertEquals(Arrays.asList(0, 2, 1, 3, 5, 4), owner.output);
        assertEquals(1, state.epoch);
    }
    private static void run(NativeTerrainVisibility.State state, Owner owner) {
        Queue<Object> seeds = new ArrayDeque<Object>(); seeds.add(new Info(owner.chunks[0]));
        state.traverse(owner, seeds, BlockPos.ORIGIN, null, 123, true, false, 256, 8, 8, 8);
    }
    private static final class Chunk extends RenderChunk implements TerrainRenderChunkIndexAccessor {
        int id; BlockPos position; long mask;
        private Chunk() { super(null, null, 0); }
        @Override public BlockPos getPosition() { return position; }
        @Override public int ice$renderChunkIndex() { return id; }
        @Override public boolean ice$setFrameIndex(int frame) { throw new AssertionError("redundant vanilla tracking"); }
        @Override public Object ice$bounds() { return null; }
        @Override public long ice$visibilityMask() { return mask; }
        @Override public boolean ice$isVisible(Object from, Object to) { throw new AssertionError("native mask is available"); }
    }
    private static final class Info implements TerrainRenderInfoAccessor {
        final Chunk chunk; Info(Chunk chunk) { this.chunk = chunk; }
        public Object ice$renderChunk() { return chunk; }
        public Object ice$incomingDirection() { return null; }
        public byte ice$pathDirections() { return 0; }
        public int ice$counter() { return 0; }
        public void ice$setDirection(byte previous, Object direction) { }
        public boolean ice$isCanonicalRenderInfo() { return true; }
    }
    private static final class Owner implements TerrainVisibilityAccessor {
        final Chunk[] chunks = new Chunk[6];
        final Object[] spatial = new Object[6];
        final List<Integer> output = new ArrayList<Integer>();
        final int[] frustumCalls = new int[6];
        int hidden = -1;
        Owner() throws Exception {
            Field field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
            Unsafe unsafe = (Unsafe) field.get(null);
            int[][] positions = {{0,0},{16,0},{0,16},{16,16},{32,16},{16,32}};
            for (int i=0;i<6;i++) {
                Chunk chunk = (Chunk) unsafe.allocateInstance(Chunk.class);
                chunk.id=i;chunk.position=new BlockPos(positions[i][0],0,positions[i][1]);chunk.mask=(1L<<36)-1;
                chunks[i]=chunk;spatial[5-i]=chunk;
            }
            chunks[3].mask = 1L << (EnumFacing.WEST.ordinal()*6+EnumFacing.EAST.ordinal())
                | 1L << (EnumFacing.NORTH.ordinal()*6+EnumFacing.SOUTH.ordinal());
        }
        public List ice$renderInfos() { return output; }
        public List ice$renderInfosEntities() { return null; }
        public List ice$renderInfosTileEntities() { return null; }
        public boolean ice$isOptifineTraversal() { return true; }
        public void ice$appendRenderInfo(Object info,Object chunk) { assertSame(chunk,((Info)info).chunk);output.add(((Chunk)chunk).id); }
        public Object[] ice$renderChunks() { return spatial; }
        public Object[] ice$directions() { return EnumFacing.values(); }
        public Object ice$getRenderChunkOffset(Object origin,Object chunk,Object direction,boolean fog,int distance) {
            BlockPos wanted=((Chunk)chunk).position.offset((EnumFacing)direction,16);
            for(Chunk candidate:chunks)if(candidate.position.equals(wanted))return candidate;
            return null;
        }
        public Object ice$oppositeDirection(Object direction) { return ((EnumFacing)direction).getOpposite(); }
        public Object ice$newRenderInfo(Object chunk,Object direction,int counter) { return new Info((Chunk)chunk); }
        public boolean ice$isInFrustum(Object chunk,Object camera,Object bounds,int frame) {
            int id=((Chunk)chunk).id;frustumCalls[id]++;return id!=hidden;
        }
    }
}
