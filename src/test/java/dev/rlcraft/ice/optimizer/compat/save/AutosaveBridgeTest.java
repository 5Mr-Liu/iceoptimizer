package dev.rlcraft.ice.optimizer.compat.save;

import static org.junit.Assert.*;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.world.chunk.Chunk;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import sun.misc.Unsafe;

public class AutosaveBridgeTest {
    @Before public void enable() {
        OptimizerRegistry.breaker(OptimizationModule.VANILLA_AUTOSAVE_SLICES).configure(true, 3);
        OptimizerRegistry.breaker(OptimizationModule.VANILLA_AUTOSAVE_SLICES).patchInstalled("test", "test");
    }
    @After public void disable() { OptimizerRegistry.breaker(OptimizationModule.VANILLA_AUTOSAVE_SLICES).configure(false, 3); }
    @Test public void largePassCompletesByDeadlineAndNeverWritesOffTheCallingThread() throws Exception {
        Provider provider = provider(10001); schedule(provider);
        assertEquals(0, provider.data); assertNotNull(provider.cursor);
        for (int tick = 0; tick < 100; tick++) AutosaveBridge.tick(provider);
        assertNull(provider.cursor); assertEquals(10001, provider.data); assertEquals(10001, provider.extra);
        assertEquals(0, provider.original);
        for (Chunk c : provider.live) assertFalse(((TestChunk)c).dirty);
    }
    @Test public void unloadReplacementAndNewDirtyStateAreHandledAtSnapshotTime() throws Exception {
        Provider provider = provider(60); schedule(provider); AutosaveBridge.tick(provider);
        TestChunk removed = (TestChunk) provider.live.remove(25);
        // The vanilla unload path has synchronously saved this old identity.
        provider.ice$saveChunkData(removed); provider.ice$saveChunkExtraData(removed);
        TestChunk newer = chunk(); provider.live.add(newer);
        TestChunk later = (TestChunk)provider.live.get(40); later.dirty = false;
        AutosaveBridge.tick(provider); later.dirty = true;
        AutosaveBridge.finish(provider);
        assertNull(provider.cursor); assertEquals(59, provider.data); assertEquals(60, provider.extra);
        assertTrue(newer.dirty); // New chunks belong to the next normal save, as with vanilla's snapshot.
        assertTrue(later.dirty); // Changes after this chunk's turn remain dirty for the next save.
    }
    @Test public void explicitSaveFlushPauseAndExceptionsDoNotDiscardAcceptedWork() throws Exception {
        Provider provider = provider(70); schedule(provider); AutosaveBridge.tick(provider);
        provider.canSave = false; AutosaveBridge.tick(provider); assertEquals(24, provider.data);
        provider.canSave = true; provider.throwData = true;
        try { AutosaveBridge.finish(provider); fail(); } catch (IllegalStateException expected) { }
        assertNotNull(provider.cursor); provider.throwData = false;
        AutosaveBridge.finish(provider); assertEquals(70, provider.data); assertNull(provider.cursor);
        provider = provider(70); schedule(provider); AutosaveBridge.tick(provider);
        assertTrue(AutosaveBridge.save(provider, true)); assertNull(provider.cursor);
        assertEquals(70, provider.data); assertEquals(1, provider.original);
        provider = provider(70); schedule(provider); disable(); AutosaveBridge.tick(provider);
        assertEquals(70, provider.data); assertNull(provider.cursor);
    }
    @Test public void explicitOtherProviderSaveDuringWorldCallbackCannotConsumeAutomaticIntent() throws Exception {
        Provider scheduled = provider(60), explicit = provider(40);
        Object server = new Object(), world = new Object();
        ThreadLocal<Object> permit = permit();
        try {
            permit.set(server);
            assertTrue(AutosaveBridge.takeAutomatic(server));
            assertFalse(AutosaveBridge.takeAutomatic(server));
            // The real WorldServer entry consumes this before saveLevel/event callbacks run.
            permit.set(world);
            assertTrue(AutosaveBridge.takeAutomatic(world));
            assertTrue(AutosaveBridge.save(explicit, true));
            assertNull(explicit.cursor); assertEquals(40, explicit.data); assertEquals(1, explicit.original);
            // Only the exact WorldServer -> provider call gets a new one-shot intent.
            permit.set(scheduled);
            assertTrue(AutosaveBridge.save(scheduled, true));
            assertEquals(0, scheduled.data); assertNotNull(scheduled.cursor);
            assertNull(permit.get());
            // Same-provider or other-provider explicit saves after the call are also complete.
            assertTrue(AutosaveBridge.save(scheduled, true));
            assertNull(scheduled.cursor); assertEquals(60, scheduled.data); assertEquals(1, scheduled.original);
        } finally { permit.remove(); }
    }
    @SuppressWarnings("unchecked") private static ThreadLocal<Object> permit() throws Exception {
        Field field = AutosaveBridge.class.getDeclaredField("AUTOMATIC"); field.setAccessible(true);
        return (ThreadLocal<Object>) field.get(null);
    }
    @SuppressWarnings("unchecked") private static void schedule(Provider provider) throws Exception {
        ThreadLocal<Object> scope = permit();
        scope.set(provider);
        try { assertTrue(AutosaveBridge.save(provider, true)); } finally { scope.remove(); }
    }
    private static Provider provider(int count) throws Exception {
        Provider provider = new Provider(); for (int i = 0; i < count; i++) provider.live.add(chunk()); return provider;
    }
    private static TestChunk chunk() throws Exception {
        Field f = Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true);
        TestChunk chunk = (TestChunk)((Unsafe)f.get(null)).allocateInstance(TestChunk.class);
        chunk.dirty = true; return chunk;
    }
    private static class TestChunk extends Chunk {
        boolean dirty;
        private TestChunk() { super(null, 0, 0); }
        @Override public boolean needsSaving(boolean all) { return dirty; }
        @Override public void setModified(boolean value) { dirty = value; }
    }
    private static class Provider implements AutosaveAccess {
        final List<Chunk> live = new ArrayList<Chunk>(); final Thread owner = Thread.currentThread();
        Object cursor; int data, extra, original; boolean canSave = true, throwData;
        public Object ice$autosaveCursor() { return cursor; }
        public void ice$autosaveCursor(Object value) { cursor = value; }
        public Collection<Chunk> ice$loadedChunks() { return live; }
        public boolean ice$isLoaded(Chunk c) { return live.contains(c); }
        public boolean ice$canSave() { return canSave; }
        public boolean ice$originalSaveChunks(boolean all) {
            original++;
            for (Chunk c : live) { if (all) ice$saveChunkExtraData(c); if (c.needsSaving(all)) { ice$saveChunkData(c); c.setModified(false); } }
            return true;
        }
        public void ice$saveChunkData(Chunk c) { assertSame(owner, Thread.currentThread()); if (throwData) throw new IllegalStateException(); data++; }
        public void ice$saveChunkExtraData(Chunk c) { assertSame(owner, Thread.currentThread()); extra++; }
    }
}
