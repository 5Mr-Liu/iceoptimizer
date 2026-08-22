package dev.rlcraft.ice.optimizer.render.texture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.backend.CapabilityReport;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class LwjglAnimatedTextureUploadStreamTest {
    @Test
    public void legacyReplayPreservesTheOriginalThrowableAndClearsCommands() {
        RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        ResourceLedger ledger = ledger(guard);
        final IllegalStateException injected = new IllegalStateException(
            "injected TextureUtil failure");
        final AtomicInteger calls = new AtomicInteger();
        LwjglAnimatedTextureUploadStream stream =
            new LwjglAnimatedTextureUploadStream(guard, ledger,
                budget(), CapabilityReport.builder().build(),
                new LwjglAnimatedTextureUploadStream.LegacyUploader() {
                    @Override public void upload(int[][] data, int width,
                                                 int height, int originX,
                                                 int originY, boolean blur,
                                                 boolean clamp) {
                        calls.incrementAndGet();
                        throw injected;
                    }
                });
        try {
            assertTrue(stream.offer(new int[][] { new int[] { 0xFFFFFFFF } },
                1, 1, 0, 0, false, false));
            try {
                stream.flush(1L, 1L, false);
                fail("legacy replay failure must escape");
            } catch (IllegalStateException expected) {
                assertSame(injected, expected);
            }
            assertEquals(1, calls.get());
            assertSame(injected, stream.getLastLegacyReplayFailure());
            assertSame(injected, stream.getLastError());
            assertFalse(stream.hasCommands());
            assertEquals(LwjglAnimatedTextureUploadStream.FlushResult.EMPTY,
                stream.flush(1L, 1L, false));
        } finally {
            stream.reset(false, 1L);
        }
    }

    @Test
    public void fenceFailureStopsBeforeAHealthySiblingCanUpload()
        throws Exception {
        RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        ResourceLedger ledger = ledger(guard);
        LwjglAnimatedTextureUploadStream stream =
            new LwjglAnimatedTextureUploadStream(guard, ledger,
                budget(), CapabilityReport.builder().build());
        try {
            Field poolField = LwjglAnimatedTextureUploadStream.class
                .getDeclaredField("streaming");
            poolField.setAccessible(true);
            Object[] pool = (Object[]) poolField.get(stream);
            Field fenceField = pool[0].getClass().getDeclaredField("fence");
            fenceField.setAccessible(true);
            final IllegalStateException injected =
                new IllegalStateException("injected texture Fence failure");
            fenceField.set(pool[0], new ResourceLedger.RetirementFence() {
                @Override public boolean isSignaled() { throw injected; }
                @Override public void destroy() { }
            });

            Method acquire = LwjglAnimatedTextureUploadStream.class
                .getDeclaredMethod("acquire", pool.getClass(),
                    Boolean.TYPE, Long.TYPE);
            acquire.setAccessible(true);
            assertEquals(null, acquire.invoke(stream, pool, false, 1L));
            assertSame(injected, stream.getLastError());
        } finally {
            stream.reset(false, 1L);
        }
    }

    @Test
    public void wrappedFatalFromLegacyReplayEscapesAfterCommandsAreCleared() {
        RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        ResourceLedger ledger = ledger(guard);
        final OutOfMemoryError fatal = new OutOfMemoryError(
            "injected texture replay fatal");
        final IllegalStateException wrapped = new IllegalStateException(
            "wrapped texture replay fatal", fatal);
        LwjglAnimatedTextureUploadStream stream =
            new LwjglAnimatedTextureUploadStream(guard, ledger,
                budget(), CapabilityReport.builder().build(),
                new LwjglAnimatedTextureUploadStream.LegacyUploader() {
                    @Override public void upload(int[][] data, int width,
                                                 int height, int originX,
                                                 int originY, boolean blur,
                                                 boolean clamp) {
                        throw wrapped;
                    }
                });
        try {
            assertTrue(stream.offer(new int[][] { new int[] { 0xFFFFFFFF } },
                1, 1, 0, 0, false, false));
            try {
                stream.flush(1L, 1L, false);
                fail("wrapped fatal replay must escape");
            } catch (OutOfMemoryError expected) {
                assertSame(fatal, expected);
            }
            assertSame(wrapped, stream.getLastLegacyReplayFailure());
            assertSame(wrapped, stream.getLastError());
            assertFalse(stream.hasCommands());
        } finally {
            stream.reset(false, 1L);
        }
    }

    @Test
    public void wrappedFatalFromFenceQueryEscapesAcquire() throws Exception {
        RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        ResourceLedger ledger = ledger(guard);
        LwjglAnimatedTextureUploadStream stream =
            new LwjglAnimatedTextureUploadStream(guard, ledger,
                budget(), CapabilityReport.builder().build());
        try {
            Field poolField = LwjglAnimatedTextureUploadStream.class
                .getDeclaredField("streaming");
            poolField.setAccessible(true);
            Object[] pool = (Object[]) poolField.get(stream);
            Field fenceField = pool[0].getClass().getDeclaredField("fence");
            fenceField.setAccessible(true);
            final OutOfMemoryError fatal = new OutOfMemoryError(
                "injected texture Fence fatal");
            final IllegalStateException wrapped = new IllegalStateException(
                "wrapped texture Fence fatal", fatal);
            fenceField.set(pool[0], new ResourceLedger.RetirementFence() {
                @Override public boolean isSignaled() { throw wrapped; }
                @Override public void destroy() { }
            });

            Method acquire = LwjglAnimatedTextureUploadStream.class
                .getDeclaredMethod("acquire", pool.getClass(),
                    Boolean.TYPE, Long.TYPE);
            acquire.setAccessible(true);
            try {
                acquire.invoke(stream, pool, false, 1L);
                fail("wrapped fatal Fence query must escape");
            } catch (InvocationTargetException expected) {
                assertSame(fatal, expected.getCause());
            }
            assertSame(wrapped, stream.getLastError());
        } finally {
            stream.reset(false, 1L);
        }
    }

    @Test
    public void fatalModernExitDropsQueuedUploadsBeforeEscaping()
        throws Exception {
        RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        ResourceLedger ledger = ledger(guard);
        LwjglAnimatedTextureUploadStream stream =
            new LwjglAnimatedTextureUploadStream(guard, ledger,
                budget(), CapabilityReport.builder().build());
        try {
            assertTrue(stream.offer(new int[][] { new int[] { 0xFFFFFFFF } },
                1, 1, 0, 0, false, false));
            OutOfMemoryError fatal = new OutOfMemoryError("texture fatal");
            Method clearFatal = LwjglAnimatedTextureUploadStream.class
                .getDeclaredMethod("clearAndRethrowFatal", Throwable.class);
            clearFatal.setAccessible(true);
            try {
                clearFatal.invoke(stream,
                    new IllegalStateException("wrapped texture fatal", fatal));
                fail("wrapped fatal was swallowed");
            } catch (InvocationTargetException expected) {
                assertSame(fatal, expected.getCause());
            }
            assertFalse(stream.hasCommands());
        } finally {
            stream.reset(false, 1L);
        }
    }

    private static CacheBudget budget() {
        return new CacheBudget(1024L * 1024L, 1024L * 1024L,
            1024L * 1024L);
    }

    private static ResourceLedger ledger(RenderThreadGuard guard) {
        return new ResourceLedger(guard, budget(),
            new ResourceLedger.Destroyer() {
                @Override public void destroy(RenderResourceKind kind,
                                              int nativeId) { }
            }, 16);
    }
}
