package dev.rlcraft.ice.optimizer.render.particle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.junit.Test;
import org.lwjgl.BufferUtils;

public final class LwjglParticleRendererDataTest {
    @Test
    public void compatibilityAttributeLayoutAvoidsSecondaryColorAlias()
        throws Exception {
        assertEquals(8, attributeLocation("CORNER_0_ATTRIBUTE"));
        assertEquals(9, attributeLocation("CORNER_1_ATTRIBUTE"));
        assertEquals(10, attributeLocation("CORNER_2_ATTRIBUTE"));
        assertEquals(11, attributeLocation("CORNER_3_ATTRIBUTE"));
        assertEquals(12, attributeLocation("UV_BOUNDS_ATTRIBUTE"));
        assertEquals(13, attributeLocation("COLOR_ATTRIBUTE"));
        assertEquals(14, attributeLocation("LIGHT_ATTRIBUTE"));
        assertTrue(LwjglParticleRenderer.instancingVertexShader(true)
            .contains("gl_SecondaryColor"));
    }

    @Test
    public void compatibilityShaderUses120AttributesAndGpuShader4VertexId() {
        String source = LwjglParticleRenderer.instancingVertexShader(true);
        assertTrue(source.startsWith("#version 120\n"));
        assertTrue(source.contains(
            "#extension GL_EXT_gpu_shader4 : require\n"));
        assertTrue(source.contains("attribute vec3 iceCorner0;"));
        assertFalse(source.contains("\nin vec"));
        assertTrue(source.contains("gl_VertexID"));
    }

    @Test
    public void restoreFailureInvalidatesTheSoftwareMirrorAndKeepsPrimaryError() {
        EarlyGlStateTracker.beginProbe();
        EarlyGlStateTracker.useProgram(0);
        EarlyGlStateTracker.bindFramebuffer(36160, 0);
        EarlyGlStateTracker.depthFunction(513);
        EarlyGlStateTracker.bindBuffer(35051, 0);
        Throwable primary = new IllegalStateException("primary");
        Throwable restore = new IllegalStateException("restore");
        assertSame(primary, LwjglParticleRenderer.restoreFailure(primary, restore));
        assertFalse(EarlyGlStateTracker.isKnown());
        assertEquals(1, primary.getSuppressed().length);
        assertSame(restore, primary.getSuppressed()[0]);
    }

    @Test
    public void unsafeOrBusyArmAppendsExactBytesToTheOriginalBuilder() {
        CacheBudget budget = new CacheBudget(1024L, 1024L * 1024L,
            1024L * 1024L);
        RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        ResourceLedger ledger = new ResourceLedger(guard, budget,
            new ResourceLedger.Destroyer() {
                @Override public void destroy(RenderResourceKind kind, int nativeId) { }
            }, 16);
        LwjglParticleRenderer renderer = new LwjglParticleRenderer(guard,
            ledger, budget, 64);
        renderer.prepare(1L, 1L);

        float x = 1.25F;
        float y = -2.5F;
        float z = 3.75F;
        double[] corners = {
            -0.1D, -0.2D, -0.3D, -0.4D, 0.5D, 0.6D,
            0.7D, 0.8D, 0.9D, 1.0D, -1.1D, 1.2D
        };
        assertTrue(renderer.recordQuad(x, y, z, corners,
            0.1F, 0.2F, 0.8F, 0.9F, 0.25F, 0.5F, 0.75F, 1.0F,
            208, 112));
        BufferBuilder fallback = new BufferBuilder(256);
        fallback.begin(7, DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP);
        assertEquals(LwjglParticleRenderer.FlushResult.LEGACY_APPENDED,
            renderer.flush(null, null, fallback));
        assertEquals(4, fallback.getVertexCount());
        assertEquals(0, renderer.size());

        ByteBuffer expected = BufferUtils.createByteBuffer(
            ParticleVertexEncoder.BYTES_PER_QUAD).order(ByteOrder.nativeOrder());
        assertTrue(ParticleVertexEncoder.putQuad(expected, x, y, z, corners,
            0.1F, 0.2F, 0.8F, 0.9F, 0.25F, 0.5F, 0.75F, 1.0F,
            208, 112));
        for (int index = 0; index < ParticleVertexEncoder.BYTES_PER_QUAD; index++) {
            assertEquals(expected.get(index), fallback.getByteBuffer().get(index));
        }

        renderer.close(false);
        assertEquals(0L, budget.snapshot().getDirectUsed());
    }

    @Test
    public void fenceFailureStopsBeforeAnotherSlotCanDraw() throws Exception {
        CacheBudget budget = new CacheBudget(1024L, 1024L * 1024L,
            1024L * 1024L);
        RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        ResourceLedger ledger = new ResourceLedger(guard, budget,
            new ResourceLedger.Destroyer() {
                @Override public void destroy(RenderResourceKind kind, int nativeId) { }
            }, 16);
        LwjglParticleRenderer renderer = new LwjglParticleRenderer(guard,
            ledger, budget, 64);
        try {
            Field slotsField = LwjglParticleRenderer.class.getDeclaredField("slots");
            slotsField.setAccessible(true);
            Object[] slots = (Object[]) slotsField.get(renderer);
            Field bufferField = slots[0].getClass().getDeclaredField("bufferId");
            bufferField.setAccessible(true);
            bufferField.setInt(slots[0], 7);
            Field fenceField = slots[0].getClass().getDeclaredField("fence");
            fenceField.setAccessible(true);
            final IllegalStateException injected =
                new IllegalStateException("injected particle Fence failure");
            fenceField.set(slots[0], new ResourceLedger.RetirementFence() {
                @Override public boolean isSignaled() { throw injected; }
                @Override public void destroy() { }
            });

            Method acquire = LwjglParticleRenderer.class.getDeclaredMethod(
                "acquireSlot");
            acquire.setAccessible(true);
            assertEquals(null, acquire.invoke(renderer));
            assertSame(injected, renderer.getLastError());
        } finally {
            renderer.close(false);
        }
    }

    @Test
    public void submittedFatalPoisonsSlotAndClearsInstancesBeforeEscaping()
        throws Exception {
        CacheBudget budget = new CacheBudget(1024L, 1024L * 1024L,
            1024L * 1024L);
        RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        ResourceLedger ledger = new ResourceLedger(guard, budget,
            new ResourceLedger.Destroyer() {
                @Override public void destroy(RenderResourceKind kind,
                                               int nativeId) { }
            }, 16);
        LwjglParticleRenderer renderer = new LwjglParticleRenderer(guard,
            ledger, budget, 64);
        try {
            renderer.prepare(1L, 1L);
            assertTrue(renderer.recordQuad(0, 0, 0, new double[12],
                0, 0, 1, 1, 1, 1, 1, 1, 0, 0));
            Field slotsField = LwjglParticleRenderer.class.getDeclaredField(
                "slots");
            slotsField.setAccessible(true);
            Object[] slots = (Object[]) slotsField.get(renderer);
            Field errorField = LwjglParticleRenderer.class.getDeclaredField(
                "lastError");
            errorField.setAccessible(true);
            OutOfMemoryError fatal = new OutOfMemoryError("particle fatal");
            errorField.set(renderer, new IllegalStateException(
                "wrapped particle fatal", fatal));
            Method finish = LwjglParticleRenderer.class.getDeclaredMethod(
                "finishFailedSubmission", slots[0].getClass());
            finish.setAccessible(true);
            try {
                finish.invoke(renderer, slots[0]);
                throw new AssertionError("wrapped fatal was swallowed");
            } catch (InvocationTargetException expected) {
                assertSame(fatal, expected.getCause());
            }
            assertEquals(0, renderer.size());
            assertEquals(1L, renderer.getModernFlushes());
            Field poisoned = slots[0].getClass().getDeclaredField("poisoned");
            poisoned.setAccessible(true);
            assertTrue(poisoned.getBoolean(slots[0]));
            Field fence = slots[0].getClass().getDeclaredField("fence");
            fence.setAccessible(true);
            assertFalse(((ResourceLedger.RetirementFence) fence.get(slots[0]))
                .isSignaled());
        } finally {
            renderer.close(false);
        }
    }

    private static int attributeLocation(String name) throws Exception {
        Field field = LwjglParticleRenderer.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(null);
    }
}
