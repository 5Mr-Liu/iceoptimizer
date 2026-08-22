package dev.rlcraft.ice.optimizer.render.resource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.render.hud.LwjglHudRenderer;
import dev.rlcraft.ice.optimizer.render.particle.LwjglFbpPacketRenderer;
import dev.rlcraft.ice.optimizer.render.particle.LwjglParticleRenderer;
import dev.rlcraft.ice.optimizer.render.texture.LwjglAnimatedTextureUploadStream;
import dev.rlcraft.ice.optimizer.render.terrain.LwjglTerrainArena;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.Test;

public final class FenceOwnershipWitnessTest {
    @Test
    public void everyDirectRingRetainsAnUncertainFenceWithoutRetryingIt()
        throws Exception {
        assertRetains(LwjglHudRenderer.class);
        assertRetains(LwjglParticleRenderer.class);
        assertRetains(LwjglFbpPacketRenderer.class);
        assertRetains(LwjglAnimatedTextureUploadStream.class);
        assertRetains(LwjglTerrainArena.class, "IndirectSlot");
    }

    private static void assertRetains(Class<?> owner) throws Exception {
        assertRetains(owner, "Slot");
    }

    private static void assertRetains(Class<?> owner, String slotName)
        throws Exception {
        Class<?> slotType = null;
        for (Class<?> nested : owner.getDeclaredClasses()) {
            if (slotName.equals(nested.getSimpleName())) {
                slotType = nested;
                break;
            }
        }
        assertTrue(owner.getName(), slotType != null);
        Constructor<?> constructor;
        Object slot;
        try {
            constructor = slotType.getDeclaredConstructor();
            constructor.setAccessible(true);
            slot = constructor.newInstance();
        } catch (NoSuchMethodException textureSlot) {
            constructor = slotType.getDeclaredConstructor(Boolean.TYPE);
            constructor.setAccessible(true);
            slot = constructor.newInstance(Boolean.FALSE);
        }

        final IllegalStateException injected = new IllegalStateException(
            owner.getSimpleName() + " Fence delete");
        ResourceLedger.RetirementFence fence =
            new ResourceLedger.RetirementFence() {
                @Override public boolean isSignaled() { return true; }
                @Override public void destroy() { throw injected; }
            };
        Field published = slotType.getDeclaredField("fence");
        Field uncertain = slotType.getDeclaredField("uncertainFence");
        Field poisoned = slotType.getDeclaredField("poisoned");
        published.setAccessible(true);
        uncertain.setAccessible(true);
        poisoned.setAccessible(true);
        published.set(slot, fence);

        Method destroy = owner.getDeclaredMethod("destroySlotFence", slotType);
        destroy.setAccessible(true);
        try {
            destroy.invoke(null, slot);
            throw new AssertionError("expected Fence deletion failure");
        } catch (InvocationTargetException expected) {
            assertSame(injected, expected.getCause());
        }

        assertEquals("published Fence must be cleared before native delete",
            null, published.get(slot));
        assertSame("uncertain Fence ownership witness must remain", fence,
            uncertain.get(slot));
        assertTrue("slot must remain poisoned", poisoned.getBoolean(slot));
    }
}
