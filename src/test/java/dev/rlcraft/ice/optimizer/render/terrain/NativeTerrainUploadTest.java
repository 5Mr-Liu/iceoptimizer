package dev.rlcraft.ice.optimizer.render.terrain;

import dev.rlcraft.ice.optimizer.compat.chunk.ChunkVertexBufferAccessor;
import dev.rlcraft.ice.optimizer.compat.chunk.NativeTerrainChunkAccessor;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.chunk.ChunkCompileTaskGenerator;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.util.BlockRenderLayer;
import org.junit.After;
import org.junit.Test;
import sun.misc.Unsafe;
import static org.junit.Assert.*;

public class NativeTerrainUploadTest {
    @After public void clearOwner() throws Exception { field("instance").set(null, null); }

    @Test public void emptyResortCanLeaveThePreviousUploadBytesInARecycledBuilder() throws Exception {
        BufferBuilder builder = recycledBuilder();
        CompiledChunk empty = new CompiledChunk();
        empty.setLayerStarted(BlockRenderLayer.TRANSLUCENT);
        TestChunk chunk = allocate(TestChunk.class);
        ChunkCompileTaskGenerator task = new ChunkCompileTaskGenerator(chunk,
            ChunkCompileTaskGenerator.Type.RESORT_TRANSPARENCY, 0);
        task.setCompiledChunk(empty);
        // The actual game method returns without touching any builder when
        // the layer was started but emitted no geometry/state.
        chunk.resortTransparency(0, 0, 0, task);
        assertEquals(0, builder.getVertexCount());
        assertEquals(112, builder.getByteBuffer().remaining());
        TestBuffer vbo = allocate(TestBuffer.class);
        vbo.count = 4;
        upload(empty, chunk, builder, vbo);
        assertEquals(0, vbo.count);
        assertNull(field("instance").get(null)); // Empty work needs no GL owner.
    }

    @Test public void unusedBuilderNeedsNeitherVertexFormatNorCoordinateState() throws Exception {
        BufferBuilder builder = new BufferBuilder(256);
        TestBuffer vbo = allocate(TestBuffer.class);
        upload(new CompiledChunk(), allocate(TestChunk.class), builder, vbo);
        assertEquals(0, vbo.count);
        assertNull(field("instance").get(null));
    }

    @Test public void zeroVerticesClearGeometryEvenIfTheBlockRendererReportedUsed() throws Exception {
        Compiled used = new Compiled();
        TestBuffer vbo = allocate(TestBuffer.class);
        upload(used, allocate(TestChunk.class), recycledBuilder(), vbo);
        assertEquals(0, vbo.count);
        assertNull(field("instance").get(null));
    }

    @Test public void emptyReplacementRetiresResidentGeometryAndInvalidatesTheDrawQueue() throws Exception {
        NativeTerrainRenderer owner = allocate(NativeTerrainRenderer.class);
        TestChunk chunk = allocate(TestChunk.class);
        TestBuffer vbo = allocate(TestBuffer.class);
        NativeTerrainRenderer.Section section = new NativeTerrainRenderer.Section(chunk);
        NativeTerrainRenderer.Mesh mesh = NativeTerrainSectionTest.mesh(section, 3);
        section.put(mesh);
        chunk.section = section;
        IdentityHashMap<VertexBuffer, NativeTerrainRenderer.Mesh> meshes = new IdentityHashMap<>();
        meshes.put(vbo, mesh);
        NativeTerrainAllocator vertices = new NativeTerrainAllocator(), indices = new NativeTerrainAllocator();
        vertices.allocate(mesh.range.first + mesh.range.count);
        indices.allocate(mesh.indexRange.first + mesh.indexRange.count);
        field("meshes").set(owner, meshes);
        field("ranges").set(owner, vertices);
        field("indexRanges").set(owner, indices);
        field("framePrepared").setBoolean(owner, true);
        field("instance").set(null, owner);
        upload(new CompiledChunk(), chunk, recycledBuilder(), vbo);
        assertTrue(meshes.isEmpty());
        assertNull(chunk.section);
        assertEquals(0, section.residentMask);
        assertEquals(0, vbo.count);
        assertFalse(field("framePrepared").getBoolean(owner));
        assertTrue(field("drawQueueDirty").getBoolean(owner));
        assertEquals(mesh.range.first, vertices.allocate(4).first);
        assertEquals(mesh.indexRange.first, indices.allocate(4).first);
    }

    private static BufferBuilder recycledBuilder() {
        BufferBuilder builder = new BufferBuilder(256);
        builder.begin(7, DefaultVertexFormats.BLOCK);
        builder.setTranslation(-160, -64, -320);
        for (int i = 0; i < 4; i++)
            builder.pos(i & 1, i >> 1, 0).color(255, 255, 255, 255).tex(0, 0).lightmap(240, 240).endVertex();
        builder.finishDrawing();
        builder.reset();
        return builder;
    }

    private static void upload(CompiledChunk compiled, TestChunk chunk, BufferBuilder builder, TestBuffer vbo) {
        TerrainUploadContext.begin(BlockRenderLayer.TRANSLUCENT, chunk, compiled);
        NativeTerrainRenderer.upload(TerrainUploadContext.take(), builder, vbo);
    }

    private static Field field(String name) throws Exception {
        Field field = NativeTerrainRenderer.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }

    private static class Compiled extends CompiledChunk {
        Compiled() { setLayerUsed(BlockRenderLayer.TRANSLUCENT); }
    }
    private static class TestChunk extends RenderChunk implements NativeTerrainChunkAccessor {
        Object section;
        private TestChunk() { super(null, null, 0); }
        public Object ice$nativeSection() { return section; }
        public void ice$nativeSection(Object value) { section = value; }
    }
    private static class TestBuffer extends VertexBuffer implements ChunkVertexBufferAccessor {
        int count;
        private TestBuffer() { super(DefaultVertexFormats.BLOCK); }
        public int ice$glBufferId() { return 0; }
        public int ice$vertexCount() { return count; }
        public int ice$vertexStrideBytes() { return 28; }
        public int ice$capacityBytes() { return 0; }
        public void ice$setCapacityBytes(int value) { }
        public void ice$setVertexCount(int value) { count = value; }
    }
}
