package dev.rlcraft.ice.optimizer.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.ModuleCircuitBreaker;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.compat.chunk.TerrainRenderListAccessor;
import dev.rlcraft.ice.optimizer.compat.texture.AnimatedTextureVisibilityBridge;
import dev.rlcraft.ice.optimizer.render.backend.AdaptiveBackendController;
import dev.rlcraft.ice.optimizer.render.backend.BackendLifecycleState;
import dev.rlcraft.ice.optimizer.render.backend.CandidateDataSource;
import dev.rlcraft.ice.optimizer.render.backend.CapabilityReport;
import dev.rlcraft.ice.optimizer.render.backend.ModernCapability;
import dev.rlcraft.ice.optimizer.render.texture.SpriteVisibilityTracker;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.render.terrain.LwjglTerrainArena;
import dev.rlcraft.ice.optimizer.render.terrain.ModernTerrainBridge;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.BitSet;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.util.BlockRenderLayer;
import org.junit.Test;
import sun.misc.Unsafe;

public final class RendererCandidateObservationTest {
    @Test
    public void terminalVisibilitySleepsEvenWhenAnotherBackendIsAwake() {
        ModernRendererRuntime runtime = runtime();
        AdaptiveBackendController visibility = backend(OptimizationModule.MODERN_TEXTURE_VISIBILITY);
        visibility.fallback("no candidate work", false);
        runtime.components.backends.put(OptimizationModule.MODERN_TEXTURE_VISIBILITY, visibility);
        runtime.components.backends.put(OptimizationModule.MODERN_TERRAIN_BACKEND,
            backend(OptimizationModule.MODERN_TERRAIN_BACKEND));
        SpriteVisibilityTracker tracker = tracker(new Pixels());
        runtime.components.spriteVisibility = tracker;
        runtime.refreshTextureVisibilityObservation(1L);
        assertNull(runtime.observedSpriteVisibility());
        assertSame("upload scopes retain their own tracker", tracker, runtime.spriteVisibility());
    }

    @Test
    public void observationIsSelectedOnceForTheFrameAndStopsAtTheNextBoundary() {
        ModernRendererRuntime runtime = runtime();
        AdaptiveBackendController visibility = backend(OptimizationModule.MODERN_TEXTURE_VISIBILITY);
        runtime.components.backends.put(OptimizationModule.MODERN_TEXTURE_VISIBILITY, visibility);
        SpriteVisibilityTracker tracker = tracker(new Pixels());
        runtime.components.spriteVisibility = tracker;
        runtime.refreshTextureVisibilityObservation(1L);
        assertSame(tracker, runtime.observedSpriteVisibility());
        visibility.fallback("qualification failed mid-frame", false);
        // Draws in the current frame keep the already-selected observer. The
        // getter does not query controller state or synchronized pending data.
        for (int draw = 0; draw < 1000; draw++) assertSame(tracker, runtime.observedSpriteVisibility());
        runtime.refreshTextureVisibilityObservation(2L);
        assertNull(runtime.observedSpriteVisibility());
    }

    @Test
    public void legacyPendingPixelsKeepFrameInfrastructureAndCatchUpBeforeDrawing() throws Exception {
        ModernRendererRuntime runtime = runtime();
        AdaptiveBackendController visibility = backend(OptimizationModule.MODERN_TEXTURE_VISIBILITY);
        visibility.fallback("dormant", false);
        runtime.components.backends.put(OptimizationModule.MODERN_TEXTURE_VISIBILITY, visibility);
        Pixels sink = new Pixels();
        SpriteVisibilityTracker tracker = tracker(sink);
        runtime.components.spriteVisibility = tracker;
        Object sprite = new Object();
        completeFrame(tracker, 1L, 1L, 1L);
        assertTrue(tracker.register(sprite, 2, 1L, 1L));
        int[][] pixels = {{17}};
        assertTrue(tracker.deferIfInvisible(sprite, 2, 7, pixels, 1, 1, 0, 0,
            false, false, 1L, 1L, 1L));
        pixels[0][0] = 99;
        runtime.refreshTextureVisibilityObservation(2L);
        assertSame(tracker, runtime.observedSpriteVisibility());
        assertTrue(requiresInfrastructure(runtime, 2L));
        tracker.beginFrame(2L, 1L, 1L);
        assertTrue(runtime.observedSpriteVisibility().markVisible(sprite, 2, 2L, 1L, 1L));
        assertEquals(17, sink.pixel);
        assertEquals(1, sink.uploads);
        assertEquals(0L, tracker.getPendingBytes());
        tracker.endFrame(2L, true);
        runtime.refreshTextureVisibilityObservation(3L);
        assertNull(runtime.observedSpriteVisibility());
        assertFalse(requiresInfrastructure(runtime, 3L));
    }

    @Test
    public void generationRestartReenablesSourceCertificationWithoutStaleEvidence() {
        ModernRendererRuntime runtime = runtime();
        AdaptiveBackendController visibility = backend(OptimizationModule.MODERN_TEXTURE_VISIBILITY);
        runtime.components.backends.put(OptimizationModule.MODERN_TEXTURE_VISIBILITY, visibility);
        SpriteVisibilityTracker tracker = tracker(new Pixels());
        runtime.components.spriteVisibility = tracker;
        completeFrame(tracker, 1L, 1L, 1L);
        visibility.fallback("dormant", false);
        runtime.refreshTextureVisibilityObservation(2L);
        assertNull(runtime.observedSpriteVisibility());
        tracker.invalidate(2L, 3L);
        visibility.begin(2L);
        visibility.capabilityResult(CapabilityReport.builder().build());
        runtime.refreshTextureVisibilityObservation(3L);
        assertSame(tracker, runtime.observedSpriteVisibility());
        assertFalse(tracker.sourcesReady());
        Object sprite = new Object();
        assertTrue(tracker.register(sprite, 2, 2L, 3L));
        tracker.beginFrame(3L, 2L, 3L);
        tracker.endFrame(3L, true);
        assertFalse(tracker.deferIfInvisible(sprite, 2, 7, new int[][] {{17}},
            1, 1, 0, 0, false, false, 3L, 2L, 3L));
        completeFrame(tracker, 4L, 2L, 3L);
        assertTrue(tracker.deferIfInvisible(sprite, 2, 7, new int[][] {{17}},
            1, 1, 0, 0, false, false, 4L, 2L, 3L));
    }

    @Test
    public void coldTerrainEventIsLatchedUntilItsFrameBoundaryCooldown() throws Exception {
        ModernRendererRuntime runtime = runtime();
        AdaptiveBackendController terrain = backend(OptimizationModule.MODERN_TERRAIN_BACKEND,
            CandidateDataSource.TERRAIN_UPLOAD);
        terrain.prepareProfiledWorkload(1, 1L);
        terrain.advanceFrame(1L);
        assertTrue(terrain.advanceFrame(601L));
        runtime.components.backends.put(OptimizationModule.MODERN_TERRAIN_BACKEND, terrain);
        assertFalse(requiresInfrastructure(runtime, 602L));
        assertFalse(runtime.resumePendingTerrainCandidate(1201L));
        runtime.observeTerrainCandidateUpload();
        assertEquals(BackendLifecycleState.LEGACY, terrain.lifecycleState());
        assertFalse(runtime.resumePendingTerrainCandidate(1200L));
        assertTrue(runtime.resumePendingTerrainCandidate(1201L));
        assertEquals(BackendLifecycleState.OUTPUT_VALIDATE, terrain.lifecycleState());
        assertFalse(terrain.isModernActive());
        assertFalse(terrain.activateAtSafeBoundary());
        assertTrue(requiresInfrastructure(runtime, 1201L));
        assertFalse(runtime.resumePendingTerrainCandidate(1202L));
    }

    @Test
    public void disposingTheGraphClearsCachedObserversAndCandidateNotifications() throws Exception {
        ModernRendererRuntime runtime = runtime();
        AdaptiveBackendController visibility = backend(OptimizationModule.MODERN_TEXTURE_VISIBILITY);
        runtime.components.backends.put(OptimizationModule.MODERN_TEXTURE_VISIBILITY, visibility);
        runtime.components.spriteVisibility = tracker(new Pixels());
        runtime.refreshTextureVisibilityObservation(1L);
        assertTrue(runtime.observedSpriteVisibility() != null);
        Method clear = ModernRendererRuntime.class.getDeclaredMethod("clearPublishedGraph");
        clear.setAccessible(true);
        clear.invoke(runtime);
        assertNull(runtime.observedSpriteVisibility());
        assertFalse(runtime.resumePendingTerrainCandidate(1201L));
    }

    @Test
    public void actualTerrainDrawCallbacksPerformNoReflectionWhenVisibilitySleeps() throws Exception {
        ModernRendererRuntime runtime = runtime();
        Pixels sink = new Pixels();
        SpriteVisibilityTracker tracker = tracker(sink);
        runtime.components.spriteVisibility = tracker;
        AdaptiveBackendController visibility = backend(OptimizationModule.MODERN_TEXTURE_VISIBILITY);
        runtime.components.backends.put(OptimizationModule.MODERN_TEXTURE_VISIBILITY, visibility);
        CountingChunk chunk = new CountingChunk();
        TerrainRenderListAccessor container = new CountingContainer(chunk);
        Field instance = field(Minecraft.class, "instance");
        Object previousMinecraft = instance.get(null);
        Field renderer = field(ClientOptimizerRuntime.class, "modernRenderer");
        Object previousRenderer = renderer.get(ClientOptimizerRuntime.INSTANCE);
        Minecraft minecraft = (Minecraft) unsafe().allocateInstance(Minecraft.class);
        field(Minecraft.class, "thread").set(minecraft, Thread.currentThread());
        try {
            instance.set(null, minecraft);
            renderer.set(ClientOptimizerRuntime.INSTANCE, runtime);
            runtime.refreshTextureVisibilityObservation(1L);
            AnimatedTextureVisibilityBridge.terrainChunk(chunk, BlockRenderLayer.SOLID);
            AnimatedTextureVisibilityBridge.terrainDraw(container, BlockRenderLayer.SOLID);
            assertEquals(2, chunk.compiledReads);
            assertEquals(2, chunk.compiled.spriteReads);

            visibility.fallback("dormant", false);
            runtime.refreshTextureVisibilityObservation(2L);
            chunk.compiledReads = chunk.compiled.spriteReads = 0;
            for (int draw = 0; draw < 1000; draw++) {
                AnimatedTextureVisibilityBridge.terrainChunk(chunk, BlockRenderLayer.SOLID);
                AnimatedTextureVisibilityBridge.terrainDraw(container, BlockRenderLayer.SOLID);
            }
            assertEquals(0, chunk.compiledReads);
            assertEquals(0, chunk.compiled.spriteReads);

            // A texture update can publish a pending frame after the cached
            // dormant selection. The existing publication callback must arm
            // this very draw, without waiting for refresh of frame three.
            completeFrame(tracker, 1L, 1L, 1L);
            Object sprite = new Object();
            assertTrue(tracker.register(sprite, 2, 1L, 1L));
            assertTrue(tracker.deferIfInvisible(sprite, 2, 7, new int[][] {{17}},
                1, 1, 0, 0, false, false, 1L, 1L, 1L));
            ClientEpochs epochs = (ClientEpochs) field(ModernRendererRuntime.class, "epochs").get(runtime);
            epochs.nextFrame();
            epochs.nextFrame();
            runtime.recordTextureVisibilityDeferred(4L);
            chunk.compiled.sprites = new BitSet();
            chunk.compiled.sprites.set(2);
            AnimatedTextureVisibilityBridge.terrainDraw(container, BlockRenderLayer.SOLID);
            assertEquals(17, sink.pixel);
            assertEquals(1, sink.uploads);
            assertEquals(0L, tracker.getPendingBytes());
            assertEquals(1, chunk.compiledReads);
            runtime.refreshTextureVisibilityObservation(3L);
            assertNull(runtime.observedSpriteVisibility());
        } finally {
            renderer.set(ClientOptimizerRuntime.INSTANCE, previousRenderer);
            instance.set(null, previousMinecraft);
        }
    }

    @Test
    public void installedLegacyUploadBoundaryCanNotifyACompletelyDormantTerrain() throws Exception {
        ModernRendererRuntime runtime = runtime();
        AdaptiveBackendController terrain = backend(OptimizationModule.MODERN_TERRAIN_BACKEND,
            CandidateDataSource.TERRAIN_UPLOAD);
        terrain.prepareProfiledWorkload(1, 1L);
        terrain.advanceFrame(1L);
        terrain.advanceFrame(601L);
        runtime.components.backends.put(OptimizationModule.MODERN_TERRAIN_BACKEND, terrain);
        RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        CacheBudget budget = new CacheBudget(1L, 1L, 32L * 1024L * 1024L);
        ResourceLedger ledger = new ResourceLedger(guard, budget, new ResourceLedger.Destroyer() {
            @Override public void destroy(RenderResourceKind kind, int id) {
                throw new AssertionError("no native resources exist in this cold fixture");
            }
        }, 16);
        runtime.components.threadGuard = guard;
        runtime.components.terrainArena = new LwjglTerrainArena(guard, ledger, budget,
            CapabilityReport.builder().build(), 16L * 1024L * 1024L, 1L);
        Field renderer = field(ClientOptimizerRuntime.class, "modernRenderer");
        Object previous = renderer.get(ClientOptimizerRuntime.INSTANCE);
        try {
            renderer.set(ClientOptimizerRuntime.INSTANCE, runtime);
            assertFalse(runtime.shouldObserveTerrain());
            assertNull(runtime.currentStamp);
            // Bypass only the VertexBuffer constructor's native allocation;
            // the installed bridge, runtime callback and arena owner lookup
            // below are real production code and require no active GL frame.
            VertexBuffer vertexBuffer = (VertexBuffer) unsafe().allocateInstance(VertexBuffer.class);
            ModernTerrainBridge.beforeLegacyUpload(vertexBuffer);
            assertEquals(BackendLifecycleState.LEGACY, terrain.lifecycleState());
            assertTrue(runtime.resumePendingTerrainCandidate(1201L));
            assertEquals(BackendLifecycleState.OUTPUT_VALIDATE, terrain.lifecycleState());
            assertFalse(terrain.isModernActive());
        } finally {
            renderer.set(ClientOptimizerRuntime.INSTANCE, previous);
        }
    }

    private static ModernRendererRuntime runtime() {
        ModernRendererRuntime runtime = new ModernRendererRuntime(new ClientEpochs(),
            new CacheBudget(1L, 1L, 1L));
        runtime.initialized = true;
        return runtime;
    }

    private static AdaptiveBackendController backend(OptimizationModule module) {
        return backend(module, CandidateDataSource.NONE);
    }

    private static AdaptiveBackendController backend(OptimizationModule module, CandidateDataSource source) {
        ModuleCircuitBreaker breaker = new ModuleCircuitBreaker(module);
        breaker.configure(true, 3);
        AdaptiveBackendController result = new AdaptiveBackendController(breaker,
            EnumSet.noneOf(ModernCapability.class), source);
        result.begin(1L);
        result.capabilityResult(CapabilityReport.builder().build());
        for (int frame = 0; frame < 120; frame++) result.warmupFrame(true);
        return result;
    }

    private static boolean requiresInfrastructure(ModernRendererRuntime runtime, long frame) throws Exception {
        Method method = ModernRendererRuntime.class.getDeclaredMethod("requiresFrameInfrastructure", Long.TYPE);
        method.setAccessible(true);
        return ((Boolean) method.invoke(runtime, frame)).booleanValue();
    }

    private static SpriteVisibilityTracker tracker(Pixels sink) {
        return new SpriteVisibilityTracker(64, 4096L, sink);
    }

    private static void completeFrame(SpriteVisibilityTracker tracker, long frame, long resources, long atlas) {
        tracker.beginFrame(frame, resources, atlas);
        tracker.observeTerrainSource(true);
        tracker.observeBufferSource(true);
        tracker.endFrame(frame, true);
    }

    private static final class Pixels implements SpriteVisibilityTracker.CatchUpSink {
        int pixel;
        int uploads;
        @Override public boolean upload(SpriteVisibilityTracker.DeferredUpload upload) {
            pixel = upload.copyPixels()[0][0];
            uploads++;
            return true;
        }
    }

    public static final class CountingChunk {
        final CountingCompiled compiled = new CountingCompiled();
        int compiledReads;
        public CountingCompiled getCompiledChunk() { compiledReads++; return compiled; }
    }

    public static final class CountingCompiled {
        int spriteReads;
        BitSet sprites;
        public BitSet getAnimatedSprites(BlockRenderLayer layer) { spriteReads++; return sprites; }
    }

    private static final class CountingContainer implements TerrainRenderListAccessor {
        final List<RenderChunk> chunks;
        @SuppressWarnings({"unchecked", "rawtypes"})
        CountingContainer(CountingChunk chunk) { chunks = (List) Collections.singletonList(chunk); }
        @Override public List<RenderChunk> ice$renderChunks() { return chunks; }
        @Override public boolean ice$initialized() { return true; }
        @Override public double ice$viewEntityX() { return 0; }
        @Override public double ice$viewEntityY() { return 0; }
        @Override public double ice$viewEntityZ() { return 0; }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Unsafe unsafe() throws Exception {
        return (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
    }
}
