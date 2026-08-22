package dev.rlcraft.ice.optimizer.compat.chunk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Map;
import org.junit.Assume;
import org.junit.Test;

/** ABI proof against the exact ChunkAnimator 1.2.1 binary used by Dregora. */
public final class ChunkAnimatorRealJarTest {
    @Test
    @SuppressWarnings("unchecked")
    public void realPendingAnimationMapIsReadableByTheFailOpenProbe()
        throws Exception {
        File jar = realJar();
        URLClassLoader loader = new URLClassLoader(
            new URL[] { jar.toURI().toURL() }, getClass().getClassLoader());
        try {
            Class<?> owner = Class.forName(
                "lumien.chunkanimator.ChunkAnimator", true, loader);
            Class<?> handlerClass = Class.forName(
                "lumien.chunkanimator.handler.AnimationHandler", true, loader);
            Object animator = owner.getConstructor().newInstance();
            Object handler = handlerClass.getConstructor().newInstance();
            owner.getField("INSTANCE").set(null, animator);
            owner.getField("animationHandler").set(animator, handler);

            ChunkAnimatorRenderBridge.Probe probe =
                ChunkAnimatorRenderBridge.inspectForTest(owner);
            assertEquals(ChunkAnimatorRenderBridge.Status.READY,
                probe.status());

            Field pendingField = handlerClass.getDeclaredField("timeStamps");
            pendingField.setAccessible(true);
            Map<Object, Object> pending =
                (Map<Object, Object>) pendingField.get(handler);
            Object renderChunkIdentity = new Object();
            assertFalse(probe.requiresCompatibilityDraw(renderChunkIdentity));
            pending.put(renderChunkIdentity, new Object());
            assertTrue(probe.requiresCompatibilityDraw(renderChunkIdentity));
            pending.clear();
        } finally {
            loader.close();
        }
    }

    private static File realJar() {
        String configured = System.getProperty(
            "ice.chunkanimator.jar", "").trim();
        Assume.assumeTrue(
            "run with -PchunkAnimatorJar=<ChunkAnimator-1.12.2-1.2.1.jar>",
            !configured.isEmpty());
        File jar = new File(configured);
        Assume.assumeTrue(jar.isFile());
        return jar;
    }
}
