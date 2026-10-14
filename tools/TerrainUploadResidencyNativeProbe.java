package dev.rlcraft.ice.optimizer.render.terrain;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.resource.*;
import java.nio.*;
import java.util.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Independent device readback proves ordered uploads and bounded slot reuse. */
public final class TerrainUploadResidencyNativeProbe {
    public static void main(String[] args) throws Exception {
        Pbuffer context = new Pbuffer(32,32,new PixelFormat(),null,null);
        try {
            context.makeCurrent();
            CacheBudget budget = new CacheBudget(8L<<20,8L<<20,16L<<20);
            RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
            ResourceLedger ledger = TerrainSubmissionNativeProbe.ledger(guard,budget);
            LwjglTerrainUploadStream stream = new LwjglTerrainUploadStream(ledger);
            int target = GL15.glGenBuffers(), sentinelRead = GL15.glGenBuffers(), sentinelWrite = GL15.glGenBuffers();
            int capacity = 4<<20;
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,target);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER,capacity,GL15.GL_STATIC_DRAW);
            RenderHandle handle = ledger.register(RenderResourceKind.BUFFER,target,capacity,1,1);
            byte[] expected = new byte[capacity];
            ByteBuffer zero = BufferUtils.createByteBuffer(capacity);
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER,0,zero);
            Random random = new Random(12102026L);
            int staged = 0, fallback = 0;
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,sentinelRead);
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,sentinelWrite);
            try {
                for (int iteration=0; iteration<240; iteration++) {
                    int size = iteration%13==0 ? (1<<20)+112 : 65536+(iteration%7)*112;
                    int offset = random.nextInt((capacity-size)/16)*16;
                    byte[] bytes = new byte[size]; random.nextBytes(bytes);
                    ByteBuffer source = BufferUtils.createByteBuffer(size).put(bytes); source.flip();
                    if(stream.upload(handle,offset,source)) staged++;
                    else { GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER,offset,source); fallback++; }
                    require(source.position()==0 && source.limit()==size,"source cursor mutated");
                    System.arraycopy(bytes,0,expected,offset,size);
                    require(GL11.glGetInteger(GL31.GL_COPY_READ_BUFFER_BINDING)==sentinelRead,"copy read binding");
                    require(GL11.glGetInteger(GL31.GL_COPY_WRITE_BUFFER_BINDING)==sentinelWrite,"copy write binding");
                    if(iteration%7==0) stream.endFrame();
                }
                stream.endFrame();
                ByteBuffer actual = BufferUtils.createByteBuffer(capacity);
                GL15.glGetBufferSubData(GL15.GL_ARRAY_BUFFER,0,actual);
                for(int i=0;i<capacity;i++) require(actual.get(i)==expected[i],"device byte mismatch "+i);
                require(GL15.glGetBufferParameteri(GL15.GL_ARRAY_BUFFER,GL15.GL_BUFFER_MAPPED)==0,"draw buffer mapped");
                require(staged>0 && fallback>0,"both copy and ordered fallback must execute");
                System.out.println("ordered_bytes_exact=true uploads="+staged+" fallback="+fallback+" gpu_bytes="+budget.snapshot().getGpuUsed());
                stream.close(true); stream.close(true);
                GL11.glFinish(); ledger.collect(1,64);
                require(budget.snapshot().getGpuUsed()==capacity,"upload stream budget leaked");
                ledger.retire(handle,null); ledger.collect(1,64);
                require(budget.snapshot().getGpuUsed()==0,"resident budget leaked");
                require(GL11.glGetError()==0,"GL error");
                System.out.println("resident_unmapped=true bindings_restored=true retirement_budget_zero=true");
            } finally { stream.close(true); ledger.destroyAll(1); GL15.glDeleteBuffers(sentinelRead); GL15.glDeleteBuffers(sentinelWrite); }
        } finally { context.destroy(); }
    }
    private static void require(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
