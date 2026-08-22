package dev.rlcraft.ice.optimizer.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyMatrixStateTracker;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.nio.FloatBuffer;
import java.util.concurrent.Callable;
import org.junit.Test;
import org.lwjgl.BufferUtils;

public final class ModernRendererRuntimeLifecycleTest {
    @Test
    public void worldSwitchRetainsWarmModelMeshes() {
        assertFalse(ModernRendererRuntime.invalidatesModelMeshesForTest(
            true, false, false));
    }

    @Test
    public void resourceReloadAndContextLossInvalidateModelMeshes() {
        assertTrue(ModernRendererRuntime.invalidatesModelMeshesForTest(
            false, true, false));
        assertTrue(ModernRendererRuntime.invalidatesModelMeshesForTest(
            false, false, true));
        assertTrue(ModernRendererRuntime.invalidatesModelMeshesForTest(
            true, true, false));
    }

    @Test
    public void disposalCollectsOrdinaryFailuresInsteadOfReportingClean() {
        final IllegalStateException first = new IllegalStateException("first");
        final IllegalArgumentException second = new IllegalArgumentException("second");
        Throwable failure = ModernRendererRuntime.cleanupForTest(null,
            throwing(first));
        failure = ModernRendererRuntime.cleanupForTest(failure,
            throwing(second));
        assertSame(first, failure);
        assertEquals(1, failure.getSuppressed().length);
        assertSame(second, failure.getSuppressed()[0]);
    }

    @Test
    public void laterWrappedFatalOutranksEarlierOrdinaryDisposalFailure() {
        final IllegalStateException ordinary = new IllegalStateException("ordinary");
        final OutOfMemoryError fatal = new OutOfMemoryError("fatal");
        Throwable failure = ModernRendererRuntime.cleanupForTest(null,
            throwing(ordinary));
        failure = ModernRendererRuntime.cleanupForTest(failure,
            throwing(new IllegalStateException("wrapper", fatal)));
        assertSame(fatal, failure);
        assertEquals(1, fatal.getSuppressed().length);
        assertSame(ordinary, fatal.getSuppressed()[0]);
    }

    @Test
    public void inactiveLegacyFallbackStillPromotesCheckedFatalWrapper()
        throws Exception {
        final OutOfMemoryError fatal = new OutOfMemoryError(
            "inactive legacy fatal");
        try {
            new ModernRendererRuntime(new ClientEpochs(),
                new CacheBudget(1L, 1L, 1L)).callLegacy("test",
                    new Callable<Void>() {
                @Override public Void call() throws Exception {
                    throw new Exception("checked wrapper", fatal);
                }
            });
            throw new AssertionError("wrapped fatal was swallowed");
        } catch (OutOfMemoryError expected) {
            assertSame(fatal, expected);
        }
    }

    @Test
    public void modelPublicationDoesNotRequireUnrelatedGlobalGlState() {
        EarlyGlStateTracker.beginProbe();
        EarlyGlStateTracker.useProgram(0);
        EarlyGlStateTracker.depthFunction(513);
        EarlyGlStateTracker.bindBuffer(34962, 17);
        EarlyGlStateTracker.seedDrawState(0, 7, 8, false,
            1, 0, 1, 0, true, true, true, 15,
            1.0F, 1.0F, 1.0F, 1.0F);
        EarlyGlStateTracker.clientActiveTexture(33984);
        EarlyMatrixStateTracker.seed(5888, identity(), identity());
        try {
            assertTrue(EarlyGlStateTracker.hasModelDrawState());
            assertTrue("the full snapshot deliberately lacks unrelated state",
                EarlyGlStateTracker.snapshot() == null);
            assertTrue(ModernRendererRuntime.modelPublicationComplete());
        } finally {
            EarlyGlStateTracker.invalidate();
            EarlyMatrixStateTracker.invalidate();
        }
    }

    private static Runnable throwing(final RuntimeException failure) {
        return new Runnable() {
            @Override public void run() { throw failure; }
        };
    }

    private static FloatBuffer identity() {
        FloatBuffer result = BufferUtils.createFloatBuffer(16);
        for (int index = 0; index < 16; index++) {
            result.put(index % 5 == 0 ? 1.0F : 0.0F);
        }
        result.flip();
        return result;
    }
}
