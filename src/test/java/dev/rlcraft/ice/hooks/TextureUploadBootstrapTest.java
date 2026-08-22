package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.rlcraft.ice.optimizer.bridge.UnsafeLegacyReplayException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import org.junit.After;
import org.junit.Test;

public final class TextureUploadBootstrapTest {
    @After
    public void reset() {
        TextureUploadBootstrap.resetForTest();
    }

    @Test
    public void returnsFalseBeforeTheRegularModInstallsItsDelegate() {
        assertFalse(TextureUploadBootstrap.tryUploadLevel(
            0, new int[] { 1 }, 1, 1, 0, 0, false, false, false));
        assertFalse(TextureUploadBootstrap.tryUpload(
            0, new int[][] { { 1 } }, 1, 1, 0, 0, false, false, false));
    }

    @Test
    public void loadsWithOnlyTheCoreOutputOnItsClassPath() throws Exception {
        URL coreOutput = TextureUploadBootstrap.class.getProtectionDomain()
            .getCodeSource().getLocation();
        URLClassLoader isolated = new URLClassLoader(new URL[] { coreOutput }, null);
        try {
            Class<?> bootstrap = Class.forName(
                "dev.rlcraft.ice.hooks.TextureUploadBootstrap", true, isolated);
            Method entry = bootstrap.getMethod("tryUploadLevel", int.class, int[].class,
                int.class, int.class, int.class, int.class,
                boolean.class, boolean.class, boolean.class);
            Object result = entry.invoke(null, 0, new int[] { 1 }, 1, 1, 0, 0,
                false, false, false);
            assertFalse((Boolean) result);
        } finally {
            isolated.close();
        }
    }

    @Test
    public void delegatesBothUploadShapesAfterInstallation() {
        assertTrue(TextureUploadBootstrap.install(WorkingBridge.class));
        assertTrue(TextureUploadBootstrap.tryUploadLevel(
            2, new int[] { 7 }, 1, 1, 3, 4, true, false, true));
        assertTrue(TextureUploadBootstrap.tryUpload(
            3, new int[][] { { 9 } }, 1, 1, 4, 5, false, true, false));
    }

    @Test
    public void delegateFailureKeepsTheOriginalUploadPathAvailable() {
        assertTrue(TextureUploadBootstrap.install(ThrowingBridge.class));
        assertFalse(TextureUploadBootstrap.tryUploadLevel(
            0, new int[] { 1 }, 1, 1, 0, 0, false, false, false));
        assertFalse(TextureUploadBootstrap.tryUpload(
            0, new int[][] { { 1 } }, 1, 1, 0, 0, false, false, false));
    }

    @Test
    public void unsafeRestoreFailureEscapesInsteadOfReplayingOriginalUpload() {
        assertTrue(TextureUploadBootstrap.install(UnsafeBridge.class));
        UnsafeLegacyReplayException expected = UnsafeBridge.failure;
        try {
            TextureUploadBootstrap.tryUploadLevel(0, new int[] { 1 }, 1, 1,
                0, 0, false, false, false);
            fail("unsafe level replay marker was swallowed");
        } catch (UnsafeLegacyReplayException actual) {
            assertSame(expected, actual);
        }
        try {
            TextureUploadBootstrap.tryUpload(0, new int[][] { { 1 } }, 1, 1,
                0, 0, false, false, false);
            fail("unsafe batch replay marker was swallowed");
        } catch (UnsafeLegacyReplayException actual) {
            assertSame(expected, actual);
        }
    }

    public static final class WorkingBridge {
        public static boolean tryUploadLevel(int mipLevel, int[] data, int width, int height,
                                             int originX, int originY, boolean linearFiltering,
                                             boolean clamped, boolean mipFiltering) {
            return mipLevel == 2 && data[0] == 7 && width == 1 && height == 1
                && originX == 3 && originY == 4 && linearFiltering
                && !clamped && mipFiltering;
        }

        public static boolean tryUpload(int maxMips, int[][] data, int width, int height,
                                        int originX, int originY, boolean linearFiltering,
                                        boolean clamped, boolean mipFiltering) {
            return maxMips == 3 && data[0][0] == 9 && width == 1 && height == 1
                && originX == 4 && originY == 5 && !linearFiltering
                && clamped && !mipFiltering;
        }
    }

    public static final class ThrowingBridge {
        public static boolean tryUploadLevel(int mipLevel, int[] data, int width, int height,
                                             int originX, int originY, boolean linearFiltering,
                                             boolean clamped, boolean mipFiltering) {
            throw new IllegalStateException("synthetic level failure");
        }

        public static boolean tryUpload(int maxMips, int[][] data, int width, int height,
                                        int originX, int originY, boolean linearFiltering,
                                        boolean clamped, boolean mipFiltering) {
            throw new IllegalStateException("synthetic batch failure");
        }
    }

    public static final class UnsafeBridge {
        private static final UnsafeLegacyReplayException failure =
            new UnsafeLegacyReplayException("unknown PBO binding",
                new IllegalStateException("restore"));

        public static boolean tryUploadLevel(int mipLevel, int[] data,
                                             int width, int height,
                                             int originX, int originY,
                                             boolean linearFiltering,
                                             boolean clamped,
                                             boolean mipFiltering) {
            throw failure;
        }

        public static boolean tryUpload(int maxMips, int[][] data, int width,
                                        int height, int originX, int originY,
                                        boolean linearFiltering,
                                        boolean clamped,
                                        boolean mipFiltering) {
            throw failure;
        }
    }
}
