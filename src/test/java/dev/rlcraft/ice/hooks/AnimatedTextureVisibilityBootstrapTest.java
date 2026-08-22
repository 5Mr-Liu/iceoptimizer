package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import org.junit.After;
import org.junit.Test;

public final class AnimatedTextureVisibilityBootstrapTest {
    @After public void reset() {
        AnimatedTextureVisibilityBootstrap.resetForTest();
    }

    @Test
    public void absentDelegateIsAnExactNoOp() {
        AnimatedTextureVisibilityBootstrap.terrainChunk("chunk", "layer");
        AnimatedTextureVisibilityBootstrap.terrainDraw("container", "layer");
        AnimatedTextureVisibilityBootstrap.bufferDraw("tessellator");
    }

    @Test
    public void installsAllBoundariesAtomically() {
        WorkingBridge.reset();
        assertTrue(AnimatedTextureVisibilityBootstrap.install(
            WorkingBridge.class));
        AnimatedTextureVisibilityBootstrap.terrainChunk("chunk", "layer");
        AnimatedTextureVisibilityBootstrap.terrainDraw("container", "layer");
        AnimatedTextureVisibilityBootstrap.bufferDraw("tessellator");
        assertEquals(1, WorkingBridge.terrain);
        assertEquals(1, WorkingBridge.terrainDraw);
        assertEquals(1, WorkingBridge.buffer);
    }

    @Test
    public void delegateFailureNeverEscapesIntoTheOriginalDraw() {
        assertTrue(AnimatedTextureVisibilityBootstrap.install(
            ThrowingBridge.class));
        AnimatedTextureVisibilityBootstrap.terrainChunk(new Object(), new Object());
        AnimatedTextureVisibilityBootstrap.terrainDraw(new Object(), new Object());
        AnimatedTextureVisibilityBootstrap.bufferDraw(new Object());
    }

    @Test
    public void coreBootstrapLoadsWithoutMinecraftOrMainRuntime() throws Exception {
        URL output = AnimatedTextureVisibilityBootstrap.class
            .getProtectionDomain().getCodeSource().getLocation();
        URLClassLoader isolated = new URLClassLoader(new URL[] {output}, null);
        try {
            Class<?> type = Class.forName(
                "dev.rlcraft.ice.hooks.AnimatedTextureVisibilityBootstrap",
                true, isolated);
            Method terrain = type.getMethod("terrainChunk", Object.class,
                Object.class);
            Method terrainDraw = type.getMethod("terrainDraw", Object.class,
                Object.class);
            Method buffer = type.getMethod("bufferDraw", Object.class);
            terrain.invoke(null, new Object(), new Object());
            terrainDraw.invoke(null, new Object(), new Object());
            buffer.invoke(null, new Object());
        } finally {
            isolated.close();
        }
    }

    public static final class WorkingBridge {
        private static int terrain;
        private static int terrainDraw;
        private static int buffer;
        private static void reset() { terrain = terrainDraw = buffer = 0; }
        public static void terrainChunk(Object chunk, Object layer) {
            if ("chunk".equals(chunk) && "layer".equals(layer)) terrain++;
        }
        public static void terrainDraw(Object container, Object layer) {
            if ("container".equals(container) && "layer".equals(layer)) {
                terrainDraw++;
            }
        }
        public static void bufferDraw(Object tessellator) {
            if ("tessellator".equals(tessellator)) buffer++;
        }
    }

    public static final class ThrowingBridge {
        public static void terrainChunk(Object chunk, Object layer) {
            throw new IllegalStateException("test");
        }
        public static void terrainDraw(Object container, Object layer) {
            throw new IllegalStateException("test");
        }
        public static void bufferDraw(Object tessellator) {
            throw new IllegalStateException("test");
        }
    }
}
