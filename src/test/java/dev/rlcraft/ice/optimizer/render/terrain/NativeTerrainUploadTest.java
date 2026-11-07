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
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import org.junit.After;
import org.junit.Test;
import sun.misc.Unsafe;
import static org.junit.Assert.*;

public class NativeTerrainUploadTest {
    @Test public void arenaReplacementReusesTheOldRangeBeforeRequestingGrowth() throws Exception {
        assertTrue(allocate(NativeTerrainGl43.class).retireBeforeUpload());
        exerciseReplacement(true, false);
    }

    @Test public void fixedPagesRetainOldMeshUntilReplacementIsComplete() throws Exception {
        assertFalse(allocate(NativeTerrainGl21.class).retireBeforeUpload());
        exerciseReplacement(false, false);
    }

    @Test public void failedArenaReplacementCannotLeaveFreedGeometryDrawable() throws Exception {
        exerciseReplacement(true, true);
    }

    @Test public void failedFixedPageReplacementKeepsThePreviouslyPublishedMesh() throws Exception {
        exerciseReplacement(false, true);
    }

    private static void exerciseReplacement(final boolean retireFirst, final boolean failUpload) throws Exception {
        final int count = 65532;
        final NativeTerrainRenderer owner = allocate(NativeTerrainRenderer.class);
        final TestChunk chunk = allocate(TestChunk.class);
        final TestBuffer vbo = allocate(TestBuffer.class);
        final NativeTerrainAllocator vertices = new NativeTerrainAllocator(), indices = new NativeTerrainAllocator();
        NativeTerrainAllocation oldAllocation = new NativeTerrainAllocation(vertices.allocate(count), indices.allocate(count), count);
        NativeTerrainRenderer.Section section = new NativeTerrainRenderer.Section(chunk);
        final NativeTerrainRenderer.Mesh old = new NativeTerrainRenderer.Mesh(section, 3, oldAllocation, count,
            TerrainVertexSpace.capture(freshBuilder(count), chunk), null);
        section.put(old);
        chunk.section = section;
        chunk.clearNeedsUpdate();
        vbo.count = count;
        final IdentityHashMap<VertexBuffer, NativeTerrainRenderer.Mesh> meshes = new IdentityHashMap<>();
        meshes.put(vbo, old);
        field("meshes").set(owner, meshes);
        field("stride").setInt(owner, 28);
        field("backend").set(owner, new NativeTerrainBackend(28) {
            @Override boolean retireBeforeUpload() { return retireFirst; }
            NativeTerrainAllocation upload(java.nio.ByteBuffer source, int size) {
                assertEquals(count * 28, source.remaining());
                if (retireFirst) { assertTrue(meshes.isEmpty()); assertEquals(0, vbo.count); }
                else assertSame(old, meshes.get(vbo));
                if (failUpload) throw new IllegalStateException("simulated upload failure");
                NativeTerrainAllocator.Range vertex = vertices.allocate(size), index = indices.allocate(size);
                assertEquals(retireFirst ? 0 : count, vertex.first);
                assertEquals(retireFirst ? 0 : count, index.first);
                return new NativeTerrainAllocation(vertex, index, size);
            }
            void free(NativeTerrainAllocation mesh) { vertices.free(mesh.vertices); indices.free(mesh.indices); }
            void uploadIndices(java.nio.ByteBuffer source, int[] copies, int size) { throw new AssertionError(); }
            long geometryCapacityBytes() { return 0; }
            void prepare(int[] words, int bytes) { throw new AssertionError(); }
            void submit(int[] words, int base, int start, int size) { throw new AssertionError(); }
            void delete() { }
        });
        field("framePrepared").setBoolean(owner, true);
        field("drawQueueDirty").setBoolean(owner, false);
        field("instance").set(null, owner);
        BufferBuilder builder = freshBuilder(count);
        try {
            upload(new Compiled(), chunk, builder, vbo);
            if (failUpload) fail("upload must propagate the failure");
            assertEquals(count, vbo.count);
            assertNotSame(old, meshes.get(vbo));
            assertSame(meshes.get(vbo), ((NativeTerrainRenderer.Section) chunk.section).layers[3]);
            assertFalse(field("framePrepared").getBoolean(owner));
            assertTrue(field("drawQueueDirty").getBoolean(owner));
        } catch (IllegalStateException failure) {
            assertTrue(failUpload);
            assertEquals("simulated upload failure", failure.getMessage());
            assertTrue(chunk.needsUpdate());
            if (retireFirst) {
                assertTrue(meshes.isEmpty()); assertNull(chunk.section); assertEquals(0, vbo.count);
                assertFalse(field("framePrepared").getBoolean(owner));
                assertTrue(field("drawQueueDirty").getBoolean(owner));
                assertEquals(0, vertices.allocate(count).first);
            } else {
                assertSame(old, meshes.get(vbo)); assertSame(section, chunk.section); assertEquals(count, vbo.count);
            }
        }
        assertEquals(0, builder.getVertexCount());
    }

    private static BufferBuilder freshBuilder(int count) {
        BufferBuilder builder = new BufferBuilder(count * 7);
        builder.begin(7, DefaultVertexFormats.BLOCK);
        builder.addVertexData(new int[count * 7]);
        builder.finishDrawing();
        return builder;
    }

    @Test public void pausedNativeUploadRetainsLayerForMaterialCapture() throws Exception {
        dev.rlcraft.ice.early.EarlyRendererOwnership.initialize(false,true);
        dev.rlcraft.ice.optimizer.OptimizerRegistry.setRuntimePaused(true);
        try {
            TerrainUploadContext.begin(BlockRenderLayer.TRANSLUCENT,allocate(TestChunk.class),new CompiledChunk());
            assertEquals(BlockRenderLayer.TRANSLUCENT,TerrainUploadContext.take().getLayer());
            assertNull(TerrainUploadContext.take());
        } finally {
            dev.rlcraft.ice.optimizer.OptimizerRegistry.setRuntimePaused(false);
            dev.rlcraft.ice.early.EarlyRendererOwnership.initialize(false,false);
        }
    }
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

    @Test public void shaderVerticesKeepTheirExtendedAttributesAndSkipStaticFaceRejection() throws Exception {
        VertexFormat extended = new VertexFormat(DefaultVertexFormats.BLOCK);
        extended.addElement(new VertexFormatElement(0, VertexFormatElement.EnumType.FLOAT,
            VertexFormatElement.EnumUsage.GENERIC, 7));
        assertEquals(56, extended.getSize());
        BufferBuilder builder = new BufferBuilder(256);
        builder.begin(7, extended);
        int[] data = new int[56];
        for (int i = 0; i < data.length; i++) data[i] = Float.floatToRawIntBits(i);
        builder.addVertexData(data);
        builder.finishDrawing();
        NativeTerrainUploadTask.Prepared prepared = NativeTerrainUploadTask.prepare(
            BlockRenderLayer.SOLID, builder, allocate(TestChunk.class), new Compiled());
        assertEquals(4, prepared.count);
        assertEquals(56, prepared.stride);
        assertNull(prepared.faces);
        assertEquals(224, builder.getByteBuffer().remaining());
        for (int i = 0; i < data.length; i++) assertEquals(data[i], builder.getByteBuffer().getInt(i * 4));
    }

    @Test public void shaderPassInvalidatesCameraQueueEvenWithoutTheOrdinaryBfs() throws Exception {
        NativeTerrainRenderer owner = allocate(NativeTerrainRenderer.class);
        field("shaderPack").setBoolean(owner, true);
        field("framePrepared").setBoolean(owner, true);
        field("drawQueueDirty").setBoolean(owner, false);
        field("instance").set(null, owner);
        NativeTerrainRenderer.beginFrame();
        assertFalse(field("framePrepared").getBoolean(owner));
        assertTrue(field("drawQueueDirty").getBoolean(owner));
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
        vertices.allocate(mesh.allocation.vertices.first + mesh.allocation.vertices.count);
        indices.allocate(mesh.allocation.indices.first + mesh.allocation.indices.count);
        field("meshes").set(owner, meshes);
        field("backend").set(owner, new NativeTerrainBackend(28) {
            NativeTerrainAllocation upload(java.nio.ByteBuffer source, int count) { throw new AssertionError(); }
            void free(NativeTerrainAllocation mesh) { vertices.free(mesh.vertices); indices.free(mesh.indices); }
            void uploadIndices(java.nio.ByteBuffer source, int[] copies, int count) { throw new AssertionError(); }
            long geometryCapacityBytes() { return 0; }
            void submit(int[] words, int base, int start, int count) { throw new AssertionError(); }
            void prepare(int[] words, int bytes) { throw new AssertionError(); }
            void delete() { }
        });
        field("framePrepared").setBoolean(owner, true);
        field("instance").set(null, owner);
        upload(new CompiledChunk(), chunk, recycledBuilder(), vbo);
        assertTrue(meshes.isEmpty());
        assertNull(chunk.section);
        assertEquals(0, section.residentMask);
        assertEquals(0, vbo.count);
        assertFalse(field("framePrepared").getBoolean(owner));
        assertTrue(field("drawQueueDirty").getBoolean(owner));
        assertEquals(mesh.allocation.vertices.first, vertices.allocate(4).first);
        assertEquals(mesh.allocation.indices.first, indices.allocate(4).first);
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
        Compiled() { setLayerUsed(BlockRenderLayer.TRANSLUCENT); setLayerUsed(BlockRenderLayer.SOLID); }
    }
    private static class TestChunk extends RenderChunk implements NativeTerrainChunkAccessor {
        Object section;
        private TestChunk() { super(null, null, 0); }
        public Object ice$nativeSection() { return section; }
        public void ice$nativeSection(Object value) { section = value; }
        @Override public BlockPos getPosition() { return BlockPos.ORIGIN; }
        @Override public CompiledChunk getCompiledChunk() { return CompiledChunk.DUMMY; }
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
