package dev.rlcraft.ice.optimizer.client;

import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.frame.FrameCoordinator;
import dev.rlcraft.ice.optimizer.render.frame.PassGraph;
import dev.rlcraft.ice.optimizer.render.legacy.GlStateMirror;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.render.texture.SpriteVisibilityTracker;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.nio.ByteBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Exercises the production pause boundary with an atlas and a bound unpack PBO. */
public final class RendererRuntimePauseNativeProbe {
    public static void main(String[] args) throws Exception {
        Pbuffer context = new Pbuffer(16, 16, new PixelFormat(), null, null);
        try {
            context.makeCurrent();
            ClientEpochs epochs = new ClientEpochs();
            epochs.nextFrame();
            ModernRendererRuntime runtime = new ModernRendererRuntime(epochs,
                new CacheBudget(1L << 20, 1L << 20, 1L << 20));
            RendererComponents graph = runtime.components;
            graph.threadGuard = RenderThreadGuard.captureCurrent();
            graph.coordinator = new FrameCoordinator(graph.threadGuard, PassGraph.standard(),
                new GlStateMirror(4), reason -> { });
            runtime.initialized = true;
            int atlas = texture();
            int callerTexture = texture();
            int unpack = GL15.glGenBuffers();
            int[] caughtUp = {0};
            graph.spriteVisibility = new SpriteVisibilityTracker(64, 4096L, upload -> {
                require(runtime.isAnimatedAtlasBound(), "atlas mirror");
                require(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) == GL13.GL_TEXTURE0, "upload unit");
                require(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) == atlas, "upload texture");
                require(GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING) == 0, "upload unpack binding");
                ByteBuffer pixel = BufferUtils.createByteBuffer(4);
                pixel.putInt(upload.copyPixels()[0][0]).flip();
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 1, 1,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
                caughtUp[0]++;
                return true;
            });
            runtime.observeAnimatedAtlas(atlas);
            SpriteVisibilityTracker tracker = graph.spriteVisibility;
            long resources = epochs.currentResourceGeneration(), atlasGeneration = epochs.currentAtlasGeneration();
            for (int scenario = 0; scenario < 2; scenario++) {
                boolean known = scenario == 1;
                int expectedPixel = known ? 0x5a8c31ff : 0x7f4a29ff;
                tracker.beginFrame(1L, resources, atlasGeneration);
                tracker.observeTerrainSource(true);
                tracker.observeBufferSource(true);
                tracker.endFrame(1L, true);
                Object sprite = new Object();
                require(tracker.register(sprite, 0, resources, atlasGeneration), "register");
                require(tracker.deferIfInvisible(sprite, 0, atlas, new int[][] {{expectedPixel}},
                    1, 1, 0, 0, false, false, 1L, resources, atlasGeneration), "defer");
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, callerTexture);
                GL13.glActiveTexture(GL13.GL_TEXTURE3);
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpack);
                GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, 64L, GL15.GL_STREAM_DRAW);
                EarlyGlStateTracker.invalidate();
                if (known) {
                    EarlyGlStateTracker.activeTexture(GL13.GL_TEXTURE0);
                    EarlyGlStateTracker.bindTexture(callerTexture);
                    EarlyGlStateTracker.activeTexture(GL13.GL_TEXTURE3);
                    EarlyGlStateTracker.bindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpack);
                }
                OptimizerRegistry.setRuntimePaused(true);
                require(runtime.setRuntimePaused(true), "pause");
                require(caughtUp[0] == scenario + 1 && tracker.getPendingBytes() == 0L, "caught up once");
                require(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) == GL13.GL_TEXTURE3, "restore active unit");
                require(GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING) == unpack, "restore unpack");
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                require(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) == callerTexture, "restore texture");
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlas);
                ByteBuffer pixel = BufferUtils.createByteBuffer(4);
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
                require(pixel.getInt(0) == expectedPixel, "deferred pixel");
                OptimizerRegistry.setRuntimePaused(false);
                require(runtime.setRuntimePaused(false) && runtime.components == graph, "resume preserves graph");
                require(GL11.glGetError() == GL11.GL_NO_ERROR, "GL error");
                System.out.println("state_mirror=" + (known ? "known" : "unknown")
                    + " atlas_pixel_exact=true active_unit_restore=true texture_restore=true unpack_restore=true");
            }
            GL11.glDeleteTextures(atlas);
            GL11.glDeleteTextures(callerTexture);
            GL15.glDeleteBuffers(unpack);
            System.out.println("pause_texture_catchup=true paused_registry=true atlas_pixel_exact=true"
                + " active_unit_restore=true texture_restore=true unpack_restore=true resume_graph_retained=true");
        } finally {
            OptimizerRegistry.setRuntimePaused(false);
            context.destroy();
        }
    }

    private static int texture() {
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1, 1, 0,
            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        return texture;
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
