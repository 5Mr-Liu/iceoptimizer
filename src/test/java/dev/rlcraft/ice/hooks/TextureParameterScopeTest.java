package dev.rlcraft.ice.hooks;

import static org.junit.Assert.*;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

public final class TextureParameterScopeTest {
    @After public void reset() {
        AnimatedTextureBootstrap.resetForTest();
        TextureUploadBootstrap.resetForTest();
    }

    @Test public void rewritesAllFirstLevelParametersAndOnlySkipsContinuousSameParameters() {
        TextureParameterScope.beginVerifiedForTest();
        assertFalse(skip(0));
        assertTrue(skip(1));
        assertTrue(skip(2));
        assertFalse("a second helper starts at zero", skip(0));
        assertTrue(skip(1));
        assertFalse("gaps do not reuse parameters", skip(3));
        assertFalse(TextureUploadBootstrap.skipParameters(4, true, false, true));
        assertTrue(TextureUploadBootstrap.skipParameters(5, true, false, true));
    }

    @Test public void bindingBoundaryPreventsCarryingParametersToTheNextSprite() {
        TextureParameterScope.beginVerifiedForTest();
        assertFalse(skip(0));
        AnimatedTextureBootstrap.textureBarrier();
        assertFalse(skip(1));
        TextureParameterScope.afterSprite();
        TextureParameterScope.beginVerifiedForTest();
        assertFalse(skip(0));
        AnimatedTextureBootstrap.afterSprite();
        assertFalse(skip(1));
    }

    @Test public void continuousReviewedSpritesReuseOnlyTheirFirstHelper() {
        TextureParameterScope.beginVerifiedForTest();
        assertFalse(skip(0));assertTrue(skip(1));
        TextureParameterScope.afterSprite();
        TextureParameterScope.beginVerifiedForTest();
        assertTrue("same atlas and parameters across sprites",skip(0));
        assertTrue(skip(1));
        assertFalse("another helper in the same sprite must reapply",skip(0));
        assertTrue(skip(1));
        TextureParameterScope.afterSprite();
        TextureParameterScope.beginVerifiedForTest();
        assertFalse("changed flags require setup",TextureUploadBootstrap.skipParameters(0,true,true,false));
        assertTrue(TextureUploadBootstrap.skipParameters(1,true,true,false));
    }

    @Test public void unknownOrOutOfScopeUploadDiscardsTheCompletedSpriteState() {
        TextureParameterScope.beginVerifiedForTest();assertFalse(skip(0));
        TextureParameterScope.afterSprite();
        AnimatedTextureBootstrap.beforeSprite(new Object());
        AnimatedTextureBootstrap.afterSprite();
        TextureParameterScope.beginVerifiedForTest();assertFalse(skip(0));
        TextureParameterScope.afterSprite();
        assertFalse("out-of-scope upload changes parameters",skip(0));
        TextureParameterScope.beginVerifiedForTest();assertFalse(skip(0));
    }

    @Test public void reviewedSpriteWithoutUploadsKeepsTheSameAtlasParameters() {
        TextureParameterScope.beginVerifiedForTest();assertFalse(skip(0));
        TextureParameterScope.afterSprite();
        TextureParameterScope.beginVerifiedForTest();TextureParameterScope.afterSprite();
        TextureParameterScope.beginVerifiedForTest();assertTrue(skip(0));
    }

    @Test public void nestedSpritesDisableReuseWithoutResumingTheOuterCache() {
        TextureParameterScope.beginVerifiedForTest();
        assertFalse(skip(0));
        TextureParameterScope.beginVerifiedForTest();
        assertFalse(skip(1));
        TextureParameterScope.afterSprite();
        assertFalse(skip(2));
    }

    @Test public void zeroTokenAndUninstalledModernDelegateStillClearAbortAndEnd() {
        TextureParameterScope.beginVerifiedForTest();
        assertFalse(skip(0));
        AnimatedTextureBootstrap.abort(0L, new IllegalStateException("sprite failed"));
        assertFalse(skip(1));
        TextureParameterScope.beginVerifiedForTest();
        assertFalse(skip(0));
        AnimatedTextureBootstrap.end(0L);
        assertFalse(skip(1));
    }

    @Test public void unknownSpritesNeverEnableReuse() {
        AnimatedTextureBootstrap.beforeSprite(new Object());
        assertFalse(skip(0));
        assertFalse(skip(1));
        assertFalse(TextureParameterScope.reviewedClassForTest(Object.class));
    }

    @Test public void uploadScopesDoNotShareParameterStateBetweenThreads() throws Exception {
        TextureParameterScope.beginVerifiedForTest();
        assertFalse(skip(0));
        assertTrue(skip(1));
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure =
            new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread other = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    assertFalse("another GL/upload thread has no sprite scope", skip(2));
                    TextureParameterScope.beginVerifiedForTest();
                    assertFalse(skip(0));
                    assertTrue(skip(1));
                } catch (Throwable unexpected) { failure.set(unexpected); }
                finally { TextureParameterScope.endAtlas(); }
            }
        });
        other.start();
        other.join(5000L);
        assertFalse("fixture must terminate", other.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertTrue("the render thread retains only its own helper sequence", skip(2));
    }

    @Test public void recognizesTheExactFoamFixDefinitionFromItsDefiningJar() throws Exception {
        String configured = System.getProperty("ice.foamfix.jar", "").trim();
        Assume.assumeTrue(!configured.isEmpty());
        URLClassLoader loader = new URLClassLoader(new URL[] { new File(configured).toURI().toURL() }, getClass().getClassLoader());
        try {
            Class<?> type = Class.forName(TextureParameterScope.FOAMFIX_SPRITE, false, loader);
            assertTrue(TextureParameterScope.reviewedClassForTest(type));
        } finally { loader.close(); }
    }

    @Test public void realFoamFixSpriteEnablesParametersEvenWithDormantAtlasBackend() throws Exception {
        String configured = System.getProperty("ice.foamfix.jar", "").trim();
        Assume.assumeTrue(!configured.isEmpty());
        URLClassLoader loader = new URLClassLoader(new URL[] { new File(configured).toURI().toURL() }, getClass().getClassLoader());
        try {
            Class<?> type = Class.forName(TextureParameterScope.FOAMFIX_SPRITE, true, loader);
            java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(String.class);
            constructor.setAccessible(true);
            Object sprite = constructor.newInstance("ice-scope-test");
            assertTrue(TextureUploadBootstrap.install(ConfigurationBridge.class));
            ConfigurationBridge.enabled = true;
            AnimatedTextureBootstrap.begin(new Object()); // No Modern delegate installed.
            AnimatedTextureBootstrap.beforeSprite(sprite);
            assertFalse(skip(0));
            assertTrue("a real reviewed sprite scope must reach the parameter guard", skip(1));
            AnimatedTextureBootstrap.afterSprite();
            AnimatedTextureBootstrap.beforeSprite(sprite);
            assertTrue("next reviewed sprite retains atlas parameters",skip(0));
            AnimatedTextureBootstrap.afterSprite();
            ConfigurationBridge.enabled = false;
            AnimatedTextureBootstrap.beforeSprite(sprite);
            assertFalse(skip(0));
            assertFalse("disabled FoamFix module must preserve all writes", skip(1));
            AnimatedTextureBootstrap.afterSprite();
        } finally { loader.close(); }
    }

    public static final class ConfigurationBridge {
        static boolean enabled;
        public static boolean allowParameterDeduplication() { return enabled; }
        public static boolean tryUploadLevel(int mip, int[] data, int width, int height,
            int x, int y, boolean blur, boolean clamp, boolean mipFilter) { return false; }
        public static boolean tryUpload(int maxMip, int[][] data, int width, int height,
            int x, int y, boolean blur, boolean clamp, boolean mipFilter) { return false; }
    }

    private static boolean skip(int mip) {
        return TextureUploadBootstrap.skipParameters(mip, false, false, true);
    }
}
