package dev.rlcraft.ice.optimizer.client;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFutureTask;
import dev.rlcraft.ice.optimizer.compat.chunk.ChunkVertexBufferAccessor;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.renderer.chunk.ChunkCompileTaskGenerator;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import org.junit.Test;
import sun.misc.Unsafe;
import static org.junit.Assert.*;

public class NativeTerrainPauseLifecycleTest {
    @Test public void rebuildRetiresBothOwnersWithoutReplacingChunksOrBufferIds() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Unsafe unsafe = (Unsafe) field.get(null);
        ReusableChunk chunk = (ReusableChunk) unsafe.allocateInstance(ReusableChunk.class);
        chunk.buffers = new TestBuffer[4];
        for (int layer = 0; layer < 4; layer++) {
            TestBuffer buffer = (TestBuffer) unsafe.allocateInstance(TestBuffer.class);
            buffer.id = 10 + layer;
            buffer.count = layer == 3 ? 0 : 4;
            // Native geometry has a count but no storage in the legacy VBO.
            buffer.capacity = layer < 2 ? 112 : 0;
            chunk.buffers[layer] = buffer;
        }
        chunk.setNeedsUpdate(true);
        Object[] chunks = { null, chunk };
        NativeTerrainPauseLifecycle.rebuildMeshes(chunks);
        assertSame(chunk, chunks[1]);
        assertSame(CompiledChunk.DUMMY, chunk.compiled);
        assertTrue(chunk.needsUpdate());
        assertFalse(chunk.needsImmediateUpdate());
        for (int layer = 0; layer < 4; layer++) {
            TestBuffer buffer = chunk.buffers[layer];
            assertSame(buffer, chunk.getVertexBufferByLayer(layer));
            assertEquals(10 + layer, buffer.id);
            assertEquals(0, buffer.count);
            assertEquals(0, buffer.capacity);
            assertEquals(layer < 2 ? 1 : 0, buffer.uploads);
        }
        NativeTerrainPauseLifecycle.rebuildMeshes(chunks);
        assertEquals("Already empty storage must not upload again", 1, chunk.buffers[0].uploads);
        NativeTerrainPauseLifecycle.rebuildMeshes(null);
    }

    @Test public void cancelsUploadWaitingTasksBeforeWaitingForTheirBuilders() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        // This regression exercises task cancellation without a GL context.
        WaitingChunk chunk = (WaitingChunk) ((Unsafe) field.get(null)).allocateInstance(WaitingChunk.class);
        chunk.task = new ChunkCompileTaskGenerator(chunk, ChunkCompileTaskGenerator.Type.REBUILD_CHUNK, 0.0);
        chunk.task.setStatus(ChunkCompileTaskGenerator.Status.UPLOADING);
        ArrayBlockingQueue<Object> builders = new ArrayBlockingQueue<>(1);
        Object builder = new Object();
        // The worker has submitted this upload, but the client has not run it.
        ListenableFutureTask<Object> upload = ListenableFutureTask.create(() -> null);
        Futures.addCallback(upload, new FutureCallback<Object>() {
            @Override public void onSuccess(Object ignored) { builders.add(builder); }
            @Override public void onFailure(Throwable failure) { builders.add(builder); }
        });
        chunk.task.addFinishRunnable(() -> upload.cancel(false));
        assertNull(builders.poll());
        NativeTerrainPauseLifecycle.cancelAndDrain(new Object[] { null, chunk }, () -> {
            assertTrue("Upload must be cancelled before the client's blocking take", upload.isCancelled());
            try { assertSame(builder, builders.poll(1, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { throw new AssertionError(error); }
        });
        assertTrue(chunk.task.isFinished());
        assertEquals(ChunkCompileTaskGenerator.Status.DONE, chunk.task.getStatus());
    }

    private static class WaitingChunk extends RenderChunk {
        ChunkCompileTaskGenerator task;
        private WaitingChunk() { super(null, null, 0); }
        @Override public void stopCompileTask() { task.finish(); }
        @Override public void setNeedsUpdate(boolean immediate) { }
    }

    private static class ReusableChunk extends RenderChunk {
        TestBuffer[] buffers;
        CompiledChunk compiled;
        private ReusableChunk() { super(null, null, 0); }
        @Override public VertexBuffer getVertexBufferByLayer(int layer) { return buffers[layer]; }
        @Override public void setCompiledChunk(CompiledChunk value) { compiled = value; }
    }

    private static class TestBuffer extends VertexBuffer implements ChunkVertexBufferAccessor {
        int id, count, capacity, uploads;
        private TestBuffer() { super(DefaultVertexFormats.BLOCK); }
        @Override public void bufferData(ByteBuffer data) {
            assertEquals(0, data.remaining());
            capacity = data.remaining();
            uploads++;
        }
        public int ice$glBufferId() { return id; }
        public int ice$vertexCount() { return count; }
        public int ice$vertexStrideBytes() { return 28; }
        public int ice$capacityBytes() { return capacity; }
        public void ice$setCapacityBytes(int value) { capacity = value; }
        public void ice$setVertexCount(int value) { count = value; }
    }
}
