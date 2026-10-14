package dev.rlcraft.ice.optimizer.render.terrain;

import static org.junit.Assert.*;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.arena.GpuArenaAllocator;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

public final class TerrainVertexIndexBuilderTest {
    @Test public void reconstructsEveryRawAttributeAndPreservesSource() {
        CacheBudget budget=budget();TerrainVertexIndexBuilder builder=new TerrainVertexIndexBuilder(budget);
        ByteBuffer source=data(1024,17), original=copy(source);
        // Distinct NaN payloads and signed zero must remain distinct identities.
        for(int i=0;i<1024;i++)source.putInt(i*28,(i%17==0)?0x80000000:0x7fc00000+i%17);
        original=copy(source);
        TerrainVertexIndexBuilder.Result result=builder.encode(source);
        assertNotNull(result);assertEquals(17,result.uniqueVertices);
        assertReconstruction(original,result);
        assertEquals(original,source);assertEquals(0,source.position());
        assertEquals(0,result.allocationBytes%112);
        builder.close();assertEmpty(budget);
    }

    @Test public void normalizingUniqueVerticesMatchesOriginalPerVertexArithmetic() {
        TerrainVertexIndexBuilder builder=new TerrainVertexIndexBuilder(budget());
        ByteBuffer source=data(2048,97);
        for(int i=0;i<2048;i++)for(int axis=0;axis<3;axis++)
            source.putFloat(i*28+axis*4,(i%97-48)*.127F+axis*.013F);
        TerrainVertexIndexBuilder.Result result=builder.encode(source);assertNotNull(result);
        ByteBuffer reference=copy(source);
        LwjglTerrainArena.normalizePositions(reference,reference.limit(),-257,81,4097,1.000001F,.00000763F,-512,0,4096);
        LwjglTerrainArena.normalizePositions(result.bytes,result.indexOffset,-257,81,4097,1.000001F,.00000763F,-512,0,4096);
        assertReconstruction(reference,result);builder.close();
    }

    @Test public void unsignedIndicesAndWorkspaceReuseRemainExact() {
        CacheBudget budget=budget();TerrainVertexIndexBuilder builder=new TerrainVertexIndexBuilder(budget);
        ByteBuffer source=data(80000,40000);
        TerrainVertexIndexBuilder.Result large=builder.encode(source);assertNotNull(large);
        assertEquals(40000,large.uniqueVertices);assertReconstruction(source,large);
        ByteBuffer small=data(128,4);TerrainVertexIndexBuilder.Result next=builder.encode(small);
        assertNotNull(next);assertEquals(4,next.uniqueVertices);assertReconstruction(small,next);
        builder.close();builder.close();assertEmpty(budget);
    }

    @Test public void rejectsUnhelpfulOversizedAndBudgetDeniedInputsWithoutChangingThem() {
        CacheBudget budget=new CacheBudget(1,1,1);TerrainVertexIndexBuilder denied=new TerrainVertexIndexBuilder(budget);
        ByteBuffer source=data(128,4);assertNull(denied.encode(source));assertEmpty(budget);
        denied.close();
        TerrainVertexIndexBuilder builder=new TerrainVertexIndexBuilder(budget());
        assertNull(builder.encode(data(128,128)));
        assertNull(builder.encode(data(131076,4)));
        assertNull(builder.encode(data(131072,65537)));
        assertNotNull(builder.encode(source));builder.close();
    }

    @Test public void metadataKeepsIndexCountSeparateAndArrayCommandsRejectIt() {
        TerrainVertexIndexBuilder builder=new TerrainVertexIndexBuilder(budget());
        TerrainVertexIndexBuilder.Result result=builder.encode(data(128,4));
        GpuArenaAllocator allocator=new GpuArenaAllocator(4096,4096,4,1);
        TerrainMesh mesh=TerrainMesh.indexedMetadataOnly(TerrainLayer.CUTOUT_MIPPED,
            result.indexCount,result.uniqueVertices,0,0,0,1,allocator.allocate(result.allocationBytes));
        assertEquals(128,mesh.getVertexCount());assertEquals(4,mesh.getStoredVertexCount());
        assertEquals(112,mesh.getIndexOffsetBytes());assertEquals(368,mesh.getStorageByteCount());
        try {
            new TerrainCommandGenerator().generate(java.util.Collections.singletonList(mesh),TerrainLayer.CUTOUT_MIPPED);
            fail("Array draw command accepted indexed storage");
        } catch(IllegalArgumentException expected) { }
        allocator.close();builder.close();
    }

    private static CacheBudget budget(){return new CacheBudget(4L<<20,8L<<20,1L<<20);}
    private static ByteBuffer data(int vertices,int unique) {
        ByteBuffer data=ByteBuffer.allocate(vertices*28).order(ByteOrder.nativeOrder());
        for(int i=0;i<vertices;i++)for(int word=0;word<7;word++)data.putInt(i*28+word*4,(i%unique)*37+word*19);
        return data;
    }
    private static ByteBuffer copy(ByteBuffer data) {
        ByteBuffer result=ByteBuffer.allocate(data.limit()).order(ByteOrder.nativeOrder());
        result.put(data.duplicate()).flip();return result;
    }
    private static void assertReconstruction(ByteBuffer source,TerrainVertexIndexBuilder.Result result) {
        for(int vertex=0;vertex<result.indexCount;vertex++) {
            int selected=result.bytes.getShort(result.indexOffset+vertex*2)&65535;
            assertTrue(selected<result.uniqueVertices);
            for(int word=0;word<7;word++)assertEquals(source.getInt(vertex*28+word*4),result.bytes.getInt(selected*28+word*4));
        }
    }
    private static void assertEmpty(CacheBudget budget) {
        assertEquals(0,budget.snapshot().getHeapUsed());assertEquals(0,budget.snapshot().getDirectUsed());
    }
}
