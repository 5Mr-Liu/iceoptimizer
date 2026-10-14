package dev.rlcraft.ice.optimizer.render.terrain;

import dev.rlcraft.ice.optimizer.client.ModernRendererRuntime;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.backend.*;
import dev.rlcraft.ice.optimizer.render.frame.*;
import dev.rlcraft.ice.optimizer.render.legacy.GlStateMirror;
import dev.rlcraft.ice.optimizer.render.resource.*;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.lang.reflect.*;
import java.util.Arrays;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.BlockRenderLayer;
import org.lwjgl.opengl.*;
import static dev.rlcraft.ice.optimizer.render.terrain.TerrainSubmissionNativeProbe.*;

/** Hidden native regression for pausing with arena-only meshes and later vanilla replacement. */
public final class TerrainPauseNativeProbe {
    public static void main(String[] args) throws Exception {
        Pbuffer context = new Pbuffer(64, 64, new PixelFormat().withDepthBits(24), null, null);
        try {
            context.makeCurrent();
            OpenGlHelper.defaultTexUnit = GL13.GL_TEXTURE0;
            OpenGlHelper.lightmapTexUnit = GL13.GL_TEXTURE1;
            ClientEpochs epochs = new ClientEpochs(); epochs.nextFrame();
            CacheBudget budget = new CacheBudget(16L << 20, 16L << 20, 64L << 20);
            RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
            ResourceLedger ledger = ledger(guard, budget);
            LwjglTerrainArena arena = new LwjglTerrainArena(guard, ledger, budget,
                CapabilityReport.builder().pass(ModernCapability.MULTI_DRAW_INDIRECT).build(), 32L << 20, 1L);
            Constructor<ModernRendererRuntime> ctor = ModernRendererRuntime.class.getDeclaredConstructor(ClientEpochs.class, CacheBudget.class);
            ctor.setAccessible(true); ModernRendererRuntime runtime = ctor.newInstance(epochs, budget);
            Object graph = get(runtime, "components");
            set(graph, "threadGuard", guard); set(graph, "resources", ledger); set(graph, "terrainArena", arena);
            set(graph, "coordinator", new FrameCoordinator(guard, PassGraph.standard(), new GlStateMirror(4), reason -> {}));
            set(runtime, "initialized", true);
            TestChunk a = chunk(), b = chunk();
            for (TestChunk chunk : new TestChunk[]{a,b}) {
                TerrainUploadContext.begin(BlockRenderLayer.SOLID, chunk, chunk.compiled);
                require(arena.upload(TerrainUploadContext.take(), builder(chunk == a ? -.9f : -.2f,
                    chunk == a ? .3f : .9f, chunk == a ? 220 : 15, 80, 160, 1), chunk.vbo,
                    new FrameStamp(1, 1, epochs.snapshot()), false, false, false), "owned upload");
            }
            Container visible = new Container(a,b);
            long resident = arena.getAllocatedBufferBytes();
            for (int cycle = 0; cycle < 4; cycle++) {
                setupBlend(); arena.render(visible.copy(), BlockRenderLayer.SOLID, false, false, 1);
                byte[] reference = pixels();
                require(runtime.setRuntimePaused(true), "pause");
                runtime.beginFrame(epochs.nextFrame(), epochs.snapshot());
                setupBlend(); require(runtime.tryRenderTerrainFallback(visible.copy(), BlockRenderLayer.SOLID), "paused draw");
                require(Arrays.equals(reference, pixels()), "paused pixels");
                runtime.endFrame();
                require(arena.getAllocatedBufferBytes() == resident, "pause reallocated terrain");
                require(runtime.setRuntimePaused(false), "resume");
            }
            require(runtime.setRuntimePaused(true), "replacement pause");
            arena.beforeLegacyUpload(a.vbo);
            require(!arena.ownsAny(new Container(a), BlockRenderLayer.SOLID), "vanilla upload did not retire old owner");
            a.vbo.id = GL15.glGenBuffers(); a.vbo.count = 4;
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, a.vbo.id);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, builder(-.8f, .6f, 25, 200, 110, 1).getByteBuffer(), GL15.GL_STATIC_DRAW);
            setupBlend(); require(runtime.tryRenderTerrainFallback(visible.copy(), BlockRenderLayer.SOLID), "paused mixed draw");
            byte[] mixed = pixels();
            setupBlend(); arena.render(visible.copy(), BlockRenderLayer.SOLID, false, false, 2);
            require(Arrays.equals(mixed, pixels()), "mixed pixels");
            runtime.endFrame(); GL11.glFinish(); runtime.endFrame();
            arena.release(a.vbo); arena.release(b.vbo); GL15.glDeleteBuffers(a.vbo.id);
            arena.close(true); GL11.glFinish(); ledger.destroyAll(epochs.currentGlContextGeneration());
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "GL error");
            require(budget.snapshot().getGpuUsed() == 0 && budget.snapshot().getDirectUsed() == 0, "resource leak");
            System.out.println("pause_resume_pixels_exact=true resident_geometry_retained=true paused_vanilla_replacement=true mixed_pixels_exact=true retirement_drained=true");
        } finally { context.destroy(); }
    }
    static Object get(Object owner, String name) throws Exception { Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
    static void set(Object owner, String name, Object value) throws Exception { Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); f.set(owner, value); }
}

