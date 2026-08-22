package dev.rlcraft.ice.optimizer.compat;

import static org.junit.Assert.assertSame;

import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import dev.rlcraft.ice.optimizer.compat.bettercaves.BetterCavesOptimizationBridge;
import dev.rlcraft.ice.optimizer.compat.chunk.BetterFoliageAoBridge;
import dev.rlcraft.ice.optimizer.compat.chunk.BetterFoliageOptifineColorBridge;
import dev.rlcraft.ice.optimizer.compat.chunk.ChunkPrimitiveSortBridge;
import dev.rlcraft.ice.optimizer.compat.chunk.ChunkVboUploadBridge;
import dev.rlcraft.ice.optimizer.compat.chunk.DynamicTreesConnectionBridge;
import dev.rlcraft.ice.optimizer.compat.entity.QualityToolsAttributeBridge;
import dev.rlcraft.ice.optimizer.compat.entity.QuarkItemSyncBridge;
import dev.rlcraft.ice.optimizer.compat.hud.HudRenderBridge;
import dev.rlcraft.ice.optimizer.compat.lycanites.LycanitesObjRenderBridge;
import dev.rlcraft.ice.optimizer.compat.optifine.OptifinePassLifecycleBridge;
import dev.rlcraft.ice.optimizer.compat.optifine.OptifineRegionBridge;
import dev.rlcraft.ice.optimizer.compat.optifine.OptifineShaderLifecycleBridge;
import dev.rlcraft.ice.optimizer.compat.particle.FbpParticleRenderBridge;
import dev.rlcraft.ice.optimizer.compat.particle.ParticleRenderBridge;
import dev.rlcraft.ice.optimizer.compat.renderlib.RenderLibRenderBridge;
import dev.rlcraft.ice.optimizer.compat.srp.SrpKirinRenderBridge;
import dev.rlcraft.ice.optimizer.compat.texture.AnimatedTextureUploadBridge;
import dev.rlcraft.ice.optimizer.compat.texture.AnimatedTextureVisibilityBridge;
import dev.rlcraft.ice.optimizer.compat.xaero.XaeroGpuTimerBridge;
import dev.rlcraft.ice.optimizer.render.legacy.LegacyGlIsland;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.Test;

public final class CompatFatalFallbackTest {
    @Test
    public void fallbackReportersRethrowFatalBeforeNullRuntimeShortcuts()
        throws Exception {
        assertFatalBoundary(FbpParticleRenderBridge.class, "reportFailure");
        assertFatalBoundary(ParticleRenderBridge.class, "safeParticleFailure");
        assertFatalBoundary(AnimatedTextureUploadBridge.class,
            "safeTextureBackendFailure");
        assertFatalBoundary(AnimatedTextureVisibilityBridge.class, "failure");
        assertFatalBoundary(HudRenderBridge.class, "safeFail");
        assertFatalBoundary(OptifineShaderLifecycleBridge.class, "safeReport");
        assertFatalBoundary(OptifineRegionBridge.class, "fail");
        assertFatalBoundary(BetterCavesOptimizationBridge.class, "fail");
        assertFatalBoundary(BetterFoliageAoBridge.class, "fail");
        assertFatalBoundary(BetterFoliageOptifineColorBridge.class, "fail");
        assertFatalBoundary(DynamicTreesConnectionBridge.class, "fail");
        assertFatalBoundary(QuarkItemSyncBridge.class, "fail");
        assertFatalBoundary(QualityToolsAttributeBridge.class, "fail");
    }

    @Test
    public void optimizerBridgeFailureRethrowsWrappedFatalForUnknownModule() {
        OutOfMemoryError fatal = new OutOfMemoryError("bridge fatal");
        try {
            OptimizerBridge.failure("missing-module-for-fatal-test",
                new IllegalStateException("wrapped", fatal));
            throw new AssertionError("OptimizerBridge swallowed fatal cause");
        } catch (OutOfMemoryError expected) {
            assertSame(fatal, expected);
        }
    }

    @Test
    public void cleanupAggregatorsPromoteWrappedFatalOverOrdinaryPrimary()
        throws Exception {
        assertFatalAggregation(ChunkPrimitiveSortBridge.class);
        assertFatalAggregation(ChunkVboUploadBridge.class);
        assertFatalAggregation(LycanitesObjRenderBridge.class);
        assertFatalAggregation(LegacyGlIsland.class);
        assertFatalAggregation(OptifinePassLifecycleBridge.class);
        assertFatalAggregation(OptifineRegionBridge.class);
        assertFatalAggregation(ParticleRenderBridge.class);
        assertFatalAggregation(RenderLibRenderBridge.class);
        assertFatalAggregation(dev.rlcraft.ice.optimizer.compat.renderlib.RenderLibTileEntityBridge.class);
        assertFatalAggregation(SrpKirinRenderBridge.class);
        assertFatalAggregation(XaeroGpuTimerBridge.class);
    }

    private static void assertFatalBoundary(Class<?> owner, String name)
        throws Exception {
        Method target = null;
        for (Method method : owner.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (name.equals(method.getName()) && Modifier.isStatic(
                method.getModifiers()) && parameters.length != 0
                && parameters[parameters.length - 1] == Throwable.class) {
                target = method;
                break;
            }
        }
        if (target == null) throw new AssertionError(
            owner.getName() + "." + name + " boundary not found");
        target.setAccessible(true);
        Object[] arguments = new Object[target.getParameterTypes().length];
        OutOfMemoryError fatal = new OutOfMemoryError(owner.getSimpleName());
        arguments[arguments.length - 1] = fatal;
        try {
            target.invoke(null, arguments);
            throw new AssertionError(owner.getName() + "." + name
                + " swallowed OutOfMemoryError");
        } catch (InvocationTargetException expected) {
            assertSame(fatal, expected.getCause());
        }
    }

    private static void assertFatalAggregation(Class<?> owner)
        throws Exception {
        Method append = owner.getDeclaredMethod("appendFailure",
            Throwable.class, Throwable.class);
        append.setAccessible(true);
        IllegalStateException primary = new IllegalStateException("primary");
        OutOfMemoryError fatal = new OutOfMemoryError(owner.getSimpleName());
        Object result = append.invoke(null, primary,
            new IllegalStateException("wrapped cleanup", fatal));
        assertSame(owner.getName(), fatal, result);
    }
}
