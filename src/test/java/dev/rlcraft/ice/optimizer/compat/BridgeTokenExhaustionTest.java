package dev.rlcraft.ice.optimizer.compat;

import static org.junit.Assert.assertEquals;

import dev.rlcraft.ice.optimizer.compat.hud.HudRenderBridge;
import dev.rlcraft.ice.optimizer.compat.optifine.OptifinePassLifecycleBridge;
import dev.rlcraft.ice.optimizer.compat.optifine.OptifineRegionBridge;
import dev.rlcraft.ice.optimizer.compat.optifine.OptifineShaderLifecycleBridge;
import dev.rlcraft.ice.optimizer.compat.particle.FbpParticleRenderBridge;
import dev.rlcraft.ice.optimizer.compat.particle.ParticleRenderBridge;
import dev.rlcraft.ice.optimizer.compat.renderlib.RenderLibRenderBridge;
import dev.rlcraft.ice.optimizer.compat.texture.AnimatedTextureUploadBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.entity.Entity;
import org.junit.Test;

public final class BridgeTokenExhaustionTest {
    @Test
    public void everySplitPhaseBridgeUsesTheZeroLegacySentinelAtExhaustion()
        throws Exception {
        assertExhausted(AnimatedTextureUploadBridge.class, "begin",
            new Class<?>[] {Object.class}, new Object[] {null});
        assertExhausted(HudRenderBridge.class, "begin",
            new Class<?>[] {Object.class, Float.TYPE},
            new Object[] {null, Float.valueOf(0.0F)});
        assertExhausted(OptifineShaderLifecycleBridge.class, "begin",
            new Class<?>[] {Object.class}, new Object[] {null});
        assertExhausted(OptifinePassLifecycleBridge.class, "beginShadow",
            new Class<?>[0], new Object[0]);
        assertExhausted(OptifineRegionBridge.class, "begin",
            new Class<?>[] {Object.class}, new Object[] {null});
        assertExhausted(ParticleRenderBridge.class, "begin",
            new Class<?>[] {Object.class, Entity.class, Float.TYPE},
            new Object[] {null, null, Float.valueOf(0.0F)});
        assertExhausted(FbpParticleRenderBridge.class, "enter",
            new Class<?>[] {Object.class}, new Object[] {null});
        assertExhausted(RenderLibRenderBridge.class, "beginEntityTraversal",
            new Class<?>[] {Object.class}, new Object[] {null});
    }

    private static void assertExhausted(Class<?> owner, String methodName,
                                        Class<?>[] parameterTypes,
                                        Object[] arguments) throws Exception {
        Field field = owner.getDeclaredField("NEXT_TOKEN");
        field.setAccessible(true);
        AtomicLong counter = (AtomicLong) field.get(null);
        long saved = counter.get();
        Method method = owner.getMethod(methodName, parameterTypes);
        try {
            counter.set(Long.MAX_VALUE);
            assertEquals(owner.getSimpleName(), 0L,
                ((Long) method.invoke(null, arguments)).longValue());
            assertEquals(owner.getSimpleName(), 0L,
                ((Long) method.invoke(null, arguments)).longValue());
            assertEquals(Long.MAX_VALUE, counter.get());
        } finally {
            counter.set(saved);
        }
    }
}
