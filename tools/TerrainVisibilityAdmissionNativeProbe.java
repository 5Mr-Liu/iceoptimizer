package dev.rlcraft.ice.optimizer.render.terrain;

import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.backend.CapabilityReport;
import dev.rlcraft.ice.optimizer.render.backend.ModernCapability;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.resource.*;
import dev.rlcraft.ice.optimizer.render.visibility.CurrentFrameTerrainOcclusion;
import dev.rlcraft.ice.optimizer.render.visibility.RenderMatrixBridge;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.lang.reflect.*;
import java.nio.*;
import java.util.*;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.util.BlockRenderLayer;
import org.lwjgl.opengl.*;

/** Offline full-Arena behavior checks: optional culling never owns native submission admission. */
public final class TerrainVisibilityAdmissionNativeProbe {
    private static final long MIB = 1024L * 1024L;
    private static final FrameStamp STAMP = new FrameStamp(1, 1, new ClientEpochs().snapshot());
    private static final float[] IDENTITY = {1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
    private static long frame;

    public static void main(String[] args) throws Exception {
        Pbuffer context = new Pbuffer(64, 64, new PixelFormat().withDepthBits(24), null, null);
        try {
            context.makeCurrent();
            OpenGlHelper.defaultTexUnit = GL13.GL_TEXTURE0;
            OpenGlHelper.lightmapTexUnit = GL13.GL_TEXTURE1;
            if (args.length > 0 && "standard".equals(args[0])) {
                Field capability = ContextCapabilities.class.getField("GL_NV_bindless_multi_draw_indirect");
                capability.setAccessible(true); capability.setBoolean(GLContext.getCapabilities(), false);
            }
            exercise("gpu-budget", BudgetKind.GPU, true);
            exercise("direct-budget", BudgetKind.DIRECT, true);
            exercise("no-faces", null, false);
            System.out.println("terrain_visibility_admission_native=true renderer=" + GL11.glGetString(GL11.GL_RENDERER));
        } finally { context.destroy(); }
    }

    private static void exercise(String label, BudgetKind exhausted, boolean opaque) throws Exception {
        CacheBudget budget = new CacheBudget(8*MIB, 8*MIB, 16*MIB);
        RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        ResourceLedger ledger = TerrainSubmissionNativeProbe.ledger(guard, budget);
        LwjglTerrainArena arena = new LwjglTerrainArena(guard, ledger, budget,
            CapabilityReport.builder().pass(ModernCapability.MULTI_DRAW_INDIRECT).build(), 16*MIB, 1L);
        TerrainSubmissionNativeProbe.TestChunk[] chunks = {
            TerrainSubmissionNativeProbe.chunk(), TerrainSubmissionNativeProbe.chunk()};
        CurrentFrameTerrainOcclusion evidence = new CurrentFrameTerrainOcclusion();
        CacheBudget.Reservation fill = null;
        try {
            for (int i=0;i<chunks.length;i++) {
                BufferBuilder builder = TerrainSubmissionNativeProbe.builder(-.85F+i*.25F, .4F+i*.25F,
                    210-i*120, 40+i*130, 90+i*90, 1);
                if (opaque) for (int vertex=0;vertex<4;vertex++) builder.getByteBuffer().put(vertex*28+15, (byte)255);
                chunks[i].vbo.id = GL15.glGenBuffers(); chunks[i].vbo.count = 4;
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, chunks[i].vbo.id);
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, builder.getByteBuffer(), GL15.GL_STATIC_DRAW);
                publishEvidence(evidence, chunks[i], builder);
            }
            TerrainSubmissionNativeProbe.Container container = new TerrainSubmissionNativeProbe.Container(chunks);
            require(!arena.ownsAny(container, BlockRenderLayer.SOLID), "test must borrow original VBOs");
            require(evidence.getMeshCount()==2, "uploaded source count");
            ByteBuffer bounds = ByteBuffer.allocateDirect(2*64).order(ByteOrder.nativeOrder());
            ByteBuffer faces = ByteBuffer.allocateDirect(2048*128).order(ByteOrder.nativeOrder());
            int faceCount = evidence.writeGpuInput(container, bounds, faces, 2, 2048);
            require(opaque ? faceCount>0 : faceCount==0, "source opacity setup");
            arena.configureNativeBatching(true, false);
            arena.configureVisibility(STAMP, null, null);
            TerrainSubmissionNativeProbe.setupBlend();
            require(arena.render(container.copy(), BlockRenderLayer.SOLID, true, true, ++frame), "native warmup");
            byte[] reference = TerrainSubmissionNativeProbe.pixels();
            require(nonzero(reference), "empty reference image");
            LwjglTerrainGpuVisibility gpu = (LwjglTerrainGpuVisibility) field(arena, "gpuVisibility");
            if (exhausted == BudgetKind.GPU) {
                // Release its unconsumed startup reservations, matching a lazy
                // visibility executor created after terrain consumed the budget.
                gpu.close(true);
                fill = budget.tryReserve(BudgetKind.GPU,
                    budget.snapshot().getGpuLimit()-budget.snapshot().getGpuUsed());
            } else if (exhausted == BudgetKind.DIRECT) {
                fill = budget.tryReserve(BudgetKind.DIRECT,
                    budget.snapshot().getDirectLimit()-budget.snapshot().getDirectUsed());
            }
            if (exhausted != null) require(fill != null, "budget exhaustion setup");
            RenderMatrixBridge.capture(FloatBuffer.wrap(IDENTITY), FloatBuffer.wrap(IDENTITY),
                IntBuffer.wrap(new int[]{0,0,64,64}));
            arena.configureVisibility(STAMP, evidence, RenderMatrixBridge.snapshot());
            TerrainSubmissionNativeProbe.setupBlend();
            long draws = arena.getShaderBatchDrawCalls(), commands = arena.getShaderBatchCommands();
            long dispatches = gpu.getSubmittedFrames();
            require(arena.render(container.copy(), BlockRenderLayer.SOLID, true, true, ++frame), label+" native ownership");
            require(arena.getShaderBatchDrawCalls()>draws, label+" lost native batching");
            require(arena.getShaderBatchCommands()-commands==2, label+" lost ordered commands");
            require(Arrays.equals(reference, TerrainSubmissionNativeProbe.pixels()), label+" pixels changed");
            require(gpu.getSubmittedFrames()==dispatches, label+" dispatched unnecessary compute");
            require(field(gpu, "program")==null, label+" unexpectedly initialized compute program");
            require(evidence.getTested()==0, label+" repeated CPU coverage");
            require(GL11.glGetError()==GL11.GL_NO_ERROR, label+" GL error");
            System.out.println("visibility_admission="+label+" native_draws="+(arena.getShaderBatchDrawCalls()-draws)
                +" commands=2 pixels_equal=true compute_dispatches=0 cpu_visibility_tests=0");
        } finally {
            if (fill != null) fill.close();
            for (TerrainSubmissionNativeProbe.TestChunk chunk : chunks) {
                arena.release(chunk.vbo); if(chunk.vbo.id!=0)GL15.glDeleteBuffers(chunk.vbo.id);
            }
            arena.close(true); GL11.glFinish(); ledger.destroyAll(1);
        }
        require(budget.snapshot().getGpuUsed()==0 && budget.snapshot().getDirectUsed()==0
            && budget.snapshot().getHeapUsed()==0, label+" budget leak");
    }

    @SuppressWarnings("unchecked")
    private static void publishEvidence(CurrentFrameTerrainOcclusion source,
        TerrainSubmissionNativeProbe.TestChunk chunk, BufferBuilder builder) throws Exception {
        Class<?> meshType = Class.forName("dev.rlcraft.ice.optimizer.render.visibility.TerrainOccluderMesh");
        Class<?> opacityType = Class.forName("dev.rlcraft.ice.optimizer.render.visibility.TerrainOccluderMesh$Opacity");
        Object opacity = Proxy.newProxyInstance(opacityType.getClassLoader(), new Class<?>[]{opacityType},
            (proxy, method, args) -> Boolean.TRUE);
        Method capture = meshType.getDeclaredMethod("capture", ByteBuffer.class, int.class, int.class, opacityType);
        capture.setAccessible(true);
        Object mesh = capture.invoke(null, builder.getByteBuffer(), 28, 4, opacity);
        TerrainVertexSpace space = TerrainVertexSpace.capture(builder, chunk);
        require(mesh!=null && space!=null, "immutable coverage source");
        Class<?> entryType = Class.forName("dev.rlcraft.ice.optimizer.render.visibility.CurrentFrameTerrainOcclusion$Entry");
        Constructor<?> constructor = entryType.getDeclaredConstructor(RenderChunk.class, CompiledChunk.class, meshType, TerrainVertexSpace.class);
        constructor.setAccessible(true);
        ((Map<VertexBuffer,Object>)field(source, "pending")).put(chunk.vbo,
            constructor.newInstance(chunk, chunk.compiled, mesh, space));
        source.uploaded(chunk.vbo);
    }
    private static boolean nonzero(byte[] pixels) { for(byte value:pixels)if(value!=0)return true;return false; }
    private static Object field(Object owner, String name) throws Exception {
        Field value=owner.getClass().getDeclaredField(name);value.setAccessible(true);return value.get(owner);
    }
    private static void require(boolean condition, String message) { if(!condition)throw new AssertionError(message); }
}
