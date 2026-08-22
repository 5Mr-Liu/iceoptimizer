package dev.rlcraft.ice.optimizer.compat.hud;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import dev.rlcraft.ice.optimizer.client.ClientOptimizerRuntime;
import dev.rlcraft.ice.optimizer.client.ModernRendererRuntime;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyMatrixStateTracker;
import dev.rlcraft.ice.optimizer.compat.texture.AnimatedTextureVisibilityBridge;
import dev.rlcraft.ice.optimizer.render.backend.BackendLifecycleState;
import dev.rlcraft.ice.optimizer.render.frame.RenderPass;
import dev.rlcraft.ice.optimizer.render.hud.FontLayoutCache;
import dev.rlcraft.ice.optimizer.render.hud.HudOutputValidator;
import dev.rlcraft.ice.optimizer.render.hud.LwjglHudOutputSelfTest;
import dev.rlcraft.ice.optimizer.render.hud.LwjglHudRenderer;
import dev.rlcraft.ice.optimizer.runtime.MonotonicTokenCounter;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.EventBus;
import org.lwjgl.opengl.GL11;

/**
 * Exact Forge HUD scope and fixed-function GUI/font emitter bridge.
 *
 * <p>Every state/event/legacy draw boundary drains the bounded stream before
 * the observable operation.  Captured glyphs keep the original triangle-strip
 * topology and can be replayed operation-for-operation if a stream slot is not
 * safe.</p>
 */
public final class HudRenderBridge {
    private static final String MODULE = "modern-hud-stream";
    private static final int MAX_HUD_DEPTH = 8;
    private static final int MAX_FONT_DEPTH = 16;
    private static final int MAX_CACHED_FONT_CHARS = 1024;
    private static final AtomicLong NEXT_TOKEN = new AtomicLong(1L);
    private static final ThreadLocal<State> STATE = new ThreadLocal<State>() {
        @Override protected State initialValue() { return new State(); }
    };
    private static volatile boolean coreBridgeInstalled;

    private HudRenderBridge() {
    }

    public static synchronized boolean installCoreBridge() {
        if (coreBridgeInstalled) return true;
        try {
            ClassLoader loader = HudRenderBridge.class.getClassLoader();
            Class<?> bootstrap = Class.forName(
                "dev.rlcraft.ice.hooks.HudRenderBootstrap", true, loader);
            Object installed = bootstrap.getMethod("install", Class.class)
                .invoke(null, HudRenderBridge.class);
            if (Boolean.TRUE.equals(installed)) {
                coreBridgeInstalled = true;
                return true;
            }
            OptimizerBridge.failure(MODULE,
                new IllegalStateException("Core HUD bridge signature mismatch"));
        } catch (ClassNotFoundException missingCore) {
            return false;
        } catch (Throwable error) {
            Throwable cause = error instanceof InvocationTargetException
                && ((InvocationTargetException) error).getCause() != null
                ? ((InvocationTargetException) error).getCause() : error;
            FatalErrors.rethrowIfFatal(cause);
            OptimizerBridge.failure(MODULE, cause);
        }
        return false;
    }

    public static long begin(Object overlay, float partialTicks) {
        long token = nextToken();
        if (token == 0L) return 0L;
        State state = STATE.get();
        if (state.hudDepth >= MAX_HUD_DEPTH) {
            HudScope current = state.currentHud();
            if (current != null) flush(current);
            state.hudOverflow++;
            return -token;
        }
        HudScope parent = state.currentHud();
        if (parent != null) flush(parent);
        HudScope scope = state.hudScopes[state.hudDepth++];
        scope.reset(token);
        if (state.hudOverflow != 0 || parent != null || state.legacyDepth != 0) {
            return token;
        }
        try {
            Minecraft minecraft = Minecraft.getMinecraft();
            if (minecraft == null || !minecraft.isCallingFromMinecraftThread()) {
                return token;
            }
            ModernRendererRuntime runtime = ClientOptimizerRuntime.INSTANCE.modernRenderer();
            LwjglHudRenderer renderer = runtime == null ? null : runtime.hudRenderer();
            if (runtime == null || renderer == null) return token;
            scope.runtime = runtime;
            scope.renderer = renderer;
            scope.resourceGeneration = runtime.resourceGeneration();
            scope.contextGeneration = runtime.glContextGeneration();
            renderer.prepare(scope.resourceGeneration, scope.contextGeneration);
            scope.sample = runtime.beginRenderBackendSample(
                OptimizationModule.MODERN_HUD_STREAM, RenderPass.HUD_GUI);
            BackendLifecycleState lifecycle = runtime.backendLifecycleState(
                OptimizationModule.MODERN_HUD_STREAM);
            if (lifecycle == BackendLifecycleState.OUTPUT_VALIDATE) {
                LwjglHudOutputSelfTest.Result result = HudOutputValidator.validate(
                    scope.contextGeneration, runtime.cacheBudget());
                runtime.recordValidation(OptimizationModule.MODERN_HUD_STREAM,
                    result.isEquivalent(), result.getDetail());
            }
            scope.modern = scope.sample != null && scope.sample.usesModernArm();
            scope.passToken = runtime.beginObservedPass(RenderPass.HUD_GUI,
                scope.sample == null
                    ? dev.rlcraft.ice.optimizer.render.backend.RenderBackendId.LEGACY
                    : scope.sample.backendId());
            scope.initialModernFlushes = renderer.getModernFlushes();
        } catch (Throwable error) {
            Throwable fatal = FatalErrors.findFatal(error);
            if (fatal != null) throw unwindBeginFatal(state, scope, fatal);
            try { fail(scope, error); }
            catch (Throwable reportingFailure) {
                throw unwindBeginFatal(state, scope, reportingFailure);
            }
        }
        return token;
    }

    public static void end(long token) {
        finishHud(token, null);
    }

    public static void abort(long token, Throwable error) {
        finishHud(token, error == null
            ? new IllegalStateException("HUD traversal aborted") : error);
    }

    /** Hard barrier inserted before every unclassified Tessellator draw. */
    public static void barrier() {
        State state = STATE.get();
        HudScope scope = state.currentHud();
        if (scope == null) return;
        FontScope font = state.currentFont();
        if (font != null && font.glyph.capturing) {
            switchGlyphToLegacy(scope, font.glyph);
        } else {
            flush(scope);
        }
    }

    /** Posts exactly once and preserves the original return/exception behavior. */
    public static boolean post(final Object bus, final Object event) {
        State state = STATE.get();
        HudScope scope = state.currentHud();
        if (scope != null) barrier();
        boolean legacyBoundary = scope != null && scope.runtime != null
            && scope.requiresLegacyBoundary;
        boolean resynchronize = scope != null && scope.modern
            && scope.runtime != null;
        state.legacyDepth++;
        try {
            final ModernRendererRuntime runtime = scope == null
                ? null : scope.runtime;
            return dispatchEvent(legacyBoundary, new EventInvocation() {
                @Override public boolean invoke() {
                    return ((EventBus) bus).post((Event) event);
                }
            }, new LegacyEventInvocation() {
                @Override public boolean invoke(final EventInvocation invocation)
                    throws Exception {
                    return runtime.callLegacy("Forge HUD event",
                        new Callable<Boolean>() {
                            @Override public Boolean call() {
                                return Boolean.valueOf(invocation.invoke());
                            }
                        }).booleanValue();
                }
            });
        } finally {
            state.legacyDepth--;
            if (legacyBoundary) scope.requiresLegacyBoundary = false;
            EarlyGlStateTracker.invalidate();
            EarlyMatrixStateTracker.invalidate();
            if (resynchronize) {
                try {
                    if (!scope.runtime.resynchronizeHudState()) scope.modern = false;
                    else scope.requiresLegacyBoundary = false;
                } catch (Throwable bridgeFailure) {
                    scope.modern = false;
                    safeFail(scope, bridgeFailure);
                }
            }
        }
    }

    static boolean dispatchEvent(boolean legacyBoundary,
                                 EventInvocation invocation,
                                 LegacyEventInvocation legacy) {
        if (invocation == null) throw new IllegalArgumentException("event invocation");
        if (!legacyBoundary) return invocation.invoke();
        if (legacy == null) throw new IllegalArgumentException("legacy invocation");
        try {
            return legacy.invoke(invocation);
        } catch (RuntimeException error) {
            throw error;
        } catch (Error error) {
            throw error;
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static boolean tryTexturedRect(Object gui, int x, int y, int u, int v,
                                           int width, int height, float z) {
        final float scale = 1.0F / 256.0F;
        return recordQuad((float) x, (float) y, z, u * scale, v * scale,
            (float) (x + width), (float) (y + height),
            (u + width) * scale, (v + height) * scale);
    }

    public static boolean tryTexturedRectFloat(Object gui, float x, float y,
                                                int u, int v, int width,
                                                int height, float z) {
        final float scale = 1.0F / 256.0F;
        return recordQuad(x, y, z, u * scale, v * scale,
            x + width, y + height, (u + width) * scale,
            (v + height) * scale);
    }

    public static boolean tryTexturedSprite(Object gui, int x, int y,
                                             Object sprite, int width,
                                             int height, float z) {
        if (!(sprite instanceof TextureAtlasSprite)) return false;
        TextureAtlasSprite value = (TextureAtlasSprite) sprite;
        // This GUI overload carries an explicit atlas sprite, so it must catch
        // up a deferred animation before either the native stream or the
        // untouched vanilla emitter can sample it.
        AnimatedTextureVisibilityBridge.spriteVisible(value);
        return recordQuad((float) x, (float) y, z, value.getMinU(), value.getMinV(),
            (float) (x + width), (float) (y + height),
            value.getMaxU(), value.getMaxV());
    }

    public static boolean tryCustomTexture(int x, int y, float u, float v,
                                            int width, int height,
                                            float textureWidth,
                                            float textureHeight) {
        if (!finite(textureWidth) || !finite(textureHeight)
            || textureWidth == 0.0F || textureHeight == 0.0F) return false;
        float scaleU = 1.0F / textureWidth;
        float scaleV = 1.0F / textureHeight;
        return recordQuad((float) x, (float) y, 0.0F, u * scaleU, v * scaleV,
            (float) (x + width), (float) (y + height),
            (u + width) * scaleU, (v + height) * scaleV);
    }

    public static boolean tryScaledTexture(int x, int y, float u, float v,
                                            int sourceWidth, int sourceHeight,
                                            int width, int height,
                                            float textureWidth,
                                            float textureHeight) {
        if (!finite(textureWidth) || !finite(textureHeight)
            || textureWidth == 0.0F || textureHeight == 0.0F) return false;
        float scaleU = 1.0F / textureWidth;
        float scaleV = 1.0F / textureHeight;
        return recordQuad((float) x, (float) y, 0.0F, u * scaleU, v * scaleV,
            (float) (x + width), (float) (y + height),
            (u + sourceWidth) * scaleU, (v + sourceHeight) * scaleV);
    }

    public static long fontStringBegin(Object font, String text, float x,
                                       float y, int color, boolean shadow) {
        State state = STATE.get();
        HudScope hud = state.currentHud();
        if (hud == null || state.legacyDepth != 0 || state.hudOverflow != 0
            || !eligible(hud)) {
            if (hud != null) flush(hud);
            return 0L;
        }
        long token = nextToken();
        if (token == 0L) return 0L;
        if (state.fontDepth >= MAX_FONT_DEPTH) {
            flush(hud);
            state.fontOverflow++;
            return -token;
        }
        FontScope parent = state.currentFont();
        if (parent != null && parent.glyph.capturing) {
            switchGlyphToLegacy(hud, parent.glyph);
        }
        FontScope scope = state.fontScopes[state.fontDepth++];
        scope.reset(token, hud.token, shadow, font, text, x, y,
            hud.resourceGeneration);
        try {
            if (cacheableFont(font, text)) {
                scope.cacheEligible = true;
                FontLayoutCache cache = hud.runtime.fonts();
                if (cache != null) {
                    scope.cached = cache.get(font, text, shadow ? 1 : 0, false,
                        hud.resourceGeneration);
                }
            }
        } catch (Throwable cacheFailure) {
            Throwable fatal = FatalErrors.findFatal(cacheFailure);
            if (fatal != null) {
                throw unwindFontBeginFatal(state, scope, fatal);
            }
            scope.cacheEligible = false;
            scope.cached = null;
            try { disableFontCache(hud, cacheFailure); }
            catch (Throwable disableFailure) {
                throw unwindFontBeginFatal(state, scope, disableFailure);
            }
        }
        return token;
    }

    /** Returns Integer.MIN_VALUE on a certified cache miss. */
    public static int tryCachedFontString(long token, Object font, String text,
                                          float x, float y, int color,
                                          boolean shadow) {
        State state = STATE.get();
        FontScope scope = state.currentFont();
        HudScope hud = state.currentHud();
        if (token <= 0L || scope == null || scope.token != token
            || hud == null || scope.hudToken != hud.token
            || scope.cached == null || scope.font != font
            || scope.shadow != shadow || scope.originX != x || scope.originY != y
            || !same(scope.text, text) || !(font instanceof FontRenderCacheAccess)
            || !eligible(hud)) return Integer.MIN_VALUE;
        FontLayoutCache.GlyphLayout layout = scope.cached;
        float finalX = x + layout.getAdvance();
        if (!finite(finalX)) return Integer.MIN_VALUE;
        FontRenderCacheAccess access = (FontRenderCacheAccess) font;
        int effective = normalizedFontColor(color, shadow);
        float red = (float) (effective >> 16 & 255) / 255.0F;
        float green = (float) (effective >> 8 & 255) / 255.0F;
        float blue = (float) (effective & 255) / 255.0F;
        float alpha = (float) (effective >> 24 & 255) / 255.0F;
        int page = 0;
        if (layout.glyphCount() != 0) {
            page = layout.texturePage(0);
            for (int glyph = 1; glyph < layout.glyphCount(); glyph++) {
                if (layout.texturePage(glyph) != page) {
                    return Integer.MIN_VALUE;
                }
            }
            if (!hud.renderer.canRecordGlyphRun(layout.glyphCount())) {
                flush(hud);
                if (!eligible(hud)
                    || !hud.renderer.canRecordGlyphRun(layout.glyphCount())) {
                    return Integer.MIN_VALUE;
                }
            }
        }
        try {
            access.ice$beginCachedFont(x, y, red, green, blue, alpha);
            GlStateManager.color(red, green, blue, alpha);
            if (layout.glyphCount() != 0) GlStateManager.bindTexture(page);
            access.ice$finishCachedFont(finalX);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            disableFontCache(hud, failure);
            return Integer.MIN_VALUE;
        }
        if (layout.glyphCount() != 0 && !layout.record(hud.renderer, x, y)) {
            // The preflight and record occur on the same render thread with no
            // intervening stream mutation. Treat any mismatch as a cache fuse;
            // replay now because the FontRenderer state was already committed.
            IllegalStateException failure = new IllegalStateException(
                "cached font stream preflight/record mismatch");
            disableFontCache(hud, failure);
            try { layout.replayLegacy(x, y); }
            catch (Throwable replayFailure) {
                addSuppressed(failure, replayFailure);
                FatalErrors.rethrowIfFatal(replayFailure);
                safeFail(hud, failure);
                // FontRenderer's cursor/color state is already committed and
                // replay may have issued a strict prefix. Returning a cache
                // miss would execute the whole original string again and can
                // duplicate transparent glyphs, so this call is handled even
                // though the HUD backend is now quarantined.
                scope.cachedReplay = true;
                return (int) finalX;
            }
        }
        scope.cachedReplay = true;
        hud.fontCacheHits++;
        return (int) finalX;
    }

    public static void fontStringEnd(long token) {
        finishFont(token, null);
    }

    public static void fontStringAbort(long token, Throwable error) {
        finishFont(token, error == null
            ? new IllegalStateException("font traversal aborted") : error);
    }

    public static void fontBegin(int mode) {
        State state = STATE.get();
        HudScope hud = state.currentHud();
        FontScope font = state.currentFont();
        if (hud != null && font != null && font.hudToken == hud.token
            && state.legacyDepth == 0 && state.fontOverflow == 0
            && mode == GL11.GL_TRIANGLE_STRIP && eligible(hud)) {
            font.glyph.begin(mode);
            return;
        }
        if (hud != null) flush(hud);
        GL11.glBegin(mode);
        if (font != null) font.glyph.beginPassthrough(mode);
    }

    public static void fontTexCoord(float u, float v) {
        Glyph glyph = currentGlyph();
        if (glyph == null) {
            GL11.glTexCoord2f(u, v);
            return;
        }
        if (glyph.capturing && glyph.captureTexCoord(u, v)) return;
        if (glyph.capturing) {
            HudScope hud = STATE.get().currentHud();
            if (hud != null) switchGlyphToLegacy(hud, glyph);
        }
        GL11.glTexCoord2f(u, v);
    }

    public static void fontVertex(float x, float y, float z) {
        Glyph glyph = currentGlyph();
        if (glyph == null) {
            GL11.glVertex3f(x, y, z);
            return;
        }
        if (glyph.capturing && glyph.captureVertex(x, y, z)) return;
        if (glyph.capturing) {
            HudScope hud = STATE.get().currentHud();
            if (hud != null) switchGlyphToLegacy(hud, glyph);
        }
        GL11.glVertex3f(x, y, z);
    }

    public static void fontEnd() {
        State state = STATE.get();
        FontScope font = state.currentFont();
        if (font == null || !font.glyph.isActive()) {
            GL11.glEnd();
            return;
        }
        HudScope hud = state.currentHud();
        Glyph glyph = font.glyph;
        if (glyph.passthrough) {
            Throwable failure = null;
            try { GL11.glEnd(); }
            catch (Throwable error) { failure = error; }
            finally { glyph.clear(); }
            if (failure != null) {
                EarlyGlStateTracker.invalidate();
                fail(hud, failure);
                rethrow(failure);
            }
            return;
        }
        boolean replayAttempted = false;
        boolean replayCompleted = false;
        Throwable failure = null;
        try {
            if (glyph.vertexCount == 4) {
                font.capture(glyph.vertices,
                    EarlyGlStateTracker.boundTextureForUnit(0));
            }
            boolean recorded = hud != null && glyph.vertexCount == 4
                && eligible(hud) && hud.renderer.recordGlyph(glyph.vertices);
            if (!recorded && hud != null && glyph.vertexCount == 4) {
                flush(hud);
                recorded = eligible(hud)
                    && hud.renderer.recordGlyph(glyph.vertices);
            }
            if (!recorded) {
                replayAttempted = true;
                glyph.replay(true);
                replayCompleted = true;
            }
        } catch (Throwable error) {
            failure = error;
            // Recording is CPU-only and atomic.  If it failed before any
            // immediate replay began, the exact captured primitive can still
            // be drawn once through the original fixed-function path.
            if (!replayAttempted) {
                replayAttempted = true;
                try {
                    glyph.replay(true);
                    replayCompleted = true;
                } catch (Throwable replayFailure) {
                    failure = appendFailure(failure, replayFailure);
                }
            }
        } finally {
            glyph.clear();
        }
        if (failure != null) {
            EarlyGlStateTracker.invalidate();
            fail(hud, failure);
            // A successful exact replay preserves the original font result;
            // quarantine only the HUD candidate.  Once replay itself failed,
            // repeating the glyph could duplicate a transparent prefix.
            if (!replayCompleted) rethrow(failure);
        }
    }

    public static void directColor(float red, float green, float blue, float alpha) {
        barrier();
        GL11.glColor4f(red, green, blue, alpha);
        EarlyGlStateTracker.color(red, green, blue, alpha);
    }

    /** Avoids a barrier only when the tracked active-unit binding is unchanged. */
    public static void textureBarrier(int texture) {
        if (EarlyGlStateTracker.boundTextureForActiveUnit() != texture) barrier();
    }

    private static boolean recordQuad(float x0, float y0, float z, float u0,
                                      float v0, float x1, float y1, float u1,
                                      float v1) {
        if (!finite(x0) || !finite(y0) || !finite(z) || !finite(u0)
            || !finite(v0) || !finite(x1) || !finite(y1) || !finite(u1)
            || !finite(v1)) return false;
        State state = STATE.get();
        HudScope scope = state.currentHud();
        if (scope == null || state.legacyDepth != 0 || state.hudOverflow != 0
            || state.fontOverflow != 0 || !eligible(scope)) return false;
        if (scope.renderer.recordQuad(x0, y0, z, u0, v0, x1, y1, u1, v1)) {
            return true;
        }
        flush(scope);
        return eligible(scope) && scope.renderer.recordQuad(x0, y0, z,
            u0, v0, x1, y1, u1, v1);
    }

    private static boolean eligible(HudScope scope) {
        if (scope == null || !scope.modern || scope.failed || scope.runtime == null
            || scope.renderer == null) return false;
        if (scope.runtime.resourceGeneration() != scope.resourceGeneration
            || scope.runtime.glContextGeneration() != scope.contextGeneration) {
            scope.stale = true;
            scope.modern = false;
            return false;
        }
        return LwjglHudRenderer.isSafeState(EarlyGlStateTracker.snapshot());
    }

    private static void flush(HudScope scope) {
        if (scope == null || scope.renderer == null) return;
        try {
            if (!scope.renderer.hasCommands()) return;
            boolean generationCurrent = scope.runtime != null
                && hudGenerationCurrent(scope.resourceGeneration,
                    scope.contextGeneration,
                    scope.runtime.resourceGeneration(),
                    scope.runtime.glContextGeneration());
            if (!generationCurrent) {
                // The intercepted vanilla calls have already been consumed by
                // this queue, so a stale batch may not simply be discarded.
                // An unknown tracker makes LwjglHudRenderer replay the exact
                // primitive stream through its compatibility path without
                // touching generation-owned VBOs.
                scope.stale = true;
                scope.modern = false;
                EarlyGlStateTracker.invalidate();
                EarlyMatrixStateTracker.invalidate();
            }
            // A thrown flush may have crossed a native draw boundary.  The
            // concrete result below clears that uncertainty only when it
            // proves no modern submission occurred.
            boolean boundaryBeforeFlush = scope.requiresLegacyBoundary;
            scope.requiresLegacyBoundary = true;
            LwjglHudRenderer.FlushResult result = scope.runtime.flushHudStream();
            scope.requiresLegacyBoundary = boundaryAfterFlush(
                boundaryBeforeFlush, result);
            if (result.submittedModern()) {
                scope.modernWork = true;
            }
            if (result != LwjglHudRenderer.FlushResult.MODERN
                && result != LwjglHudRenderer.FlushResult.EMPTY) {
                scope.fallbacks++;
                if (result == LwjglHudRenderer.FlushResult.LEGACY_BUSY
                    || result == LwjglHudRenderer.FlushResult.LEGACY_STATE) {
                    scope.modern = false;
                }
                if (result == LwjglHudRenderer.FlushResult.FAILED_BEFORE_DRAW
                    || result == LwjglHudRenderer.FlushResult.FAILED_AFTER_DRAW) {
                    scope.failed = true;
                    scope.modern = false;
                }
            }
        } catch (Throwable error) {
            Throwable failure = error;
            try { scope.renderer.discard(); }
            catch (Throwable discardFailure) {
                failure = appendFailure(failure, discardFailure);
            }
            fail(scope, failure);
        }
    }

    static boolean boundaryAfterFlush(boolean prior,
                                      LwjglHudRenderer.FlushResult result) {
        return prior || result != null && result.submittedModern();
    }

    static boolean hudGenerationCurrent(long queuedResources,
                                        long queuedContext,
                                        long currentResources,
                                        long currentContext) {
        return queuedResources > 0L && queuedContext > 0L
            && queuedResources == currentResources
            && queuedContext == currentContext;
    }

    private static void switchGlyphToLegacy(HudScope hud, Glyph glyph) {
        flush(hud);
        try {
            glyph.replay(false);
        } catch (Throwable failure) {
            glyph.clear();
            EarlyGlStateTracker.invalidate();
            fail(hud, failure);
            rethrow(failure);
        }
        glyph.capturing = false;
        glyph.passthrough = true;
    }

    private static Glyph currentGlyph() {
        FontScope font = STATE.get().currentFont();
        return font == null || !font.glyph.isActive() ? null : font.glyph;
    }

    private static void finishHud(long token, Throwable traversalError) {
        if (token == 0L) return;
        State state = STATE.get();
        if (token < 0L) {
            if (state.hudOverflow > 0) state.hudOverflow--;
            return;
        }
        HudScope scope = state.currentHud();
        if (scope == null || scope.token != token) {
            drainAll(state, new IllegalStateException("HUD scope token mismatch"));
            return;
        }
        Throwable fatal = null;
        while (state.fontDepth > 0
            && state.currentFont().hudToken == scope.token) {
            try { finishTopFont(state, traversalError); }
            catch (Throwable failure) {
                fatal = appendFailure(fatal, failure);
            }
        }
        try { flush(scope); }
        catch (Throwable failure) {
            fatal = appendFailure(fatal, failure);
        }
        if (scope.renderer != null) {
            try {
                if (scope.renderer.getModernFlushes()
                    > scope.initialModernFlushes) scope.modernWork = true;
            } catch (Throwable cleanupFailure) {
                try { safeFail(scope, cleanupFailure); }
                catch (Throwable failure) {
                    fatal = appendFailure(fatal, failure);
                }
            }
        }
        if (scope.runtime != null) {
            try {
                scope.runtime.recordHudFontCache(scope.fontCacheHits,
                    scope.fontCacheMisses);
            } catch (Throwable cleanupFailure) {
                try { safeFail(scope, cleanupFailure); }
                catch (Throwable failure) {
                    fatal = appendFailure(fatal, failure);
                }
            }
            try {
                scope.runtime.endRenderBackendSample(scope.sample,
                    traversalError == null && !scope.failed && !scope.stale,
                    scope.modernWork);
            } catch (Throwable cleanupFailure) {
                try { safeFail(scope, cleanupFailure); }
                catch (Throwable failure) {
                    fatal = appendFailure(fatal, failure);
                }
            }
        }
        try { scope.closePass(); }
        catch (Throwable failure) {
            fatal = appendFailure(fatal, failure);
        } finally {
            scope.clearReferences();
            state.hudDepth--;
        }
        FatalErrors.rethrowIfFatal(fatal);
    }

    private static void finishFont(long token, Throwable traversalError) {
        if (token == 0L) return;
        State state = STATE.get();
        if (token < 0L) {
            if (state.fontOverflow > 0) state.fontOverflow--;
            return;
        }
        FontScope scope = state.currentFont();
        if (scope == null || scope.token != token) {
            drainFonts(state, new IllegalStateException("font scope token mismatch"));
            return;
        }
        finishTopFont(state, traversalError);
    }

    private static void finishTopFont(State state, Throwable traversalError) {
        FontScope scope = state.currentFont();
        if (scope == null) return;
        Glyph glyph = scope.glyph;
        HudScope hud = state.currentHud();
        Throwable failure = null;
        try {
            if (glyph.capturing) {
                if (hud != null) {
                    try { switchGlyphToLegacy(hud, glyph); }
                    catch (Throwable error) {
                        failure = appendFailure(failure, error);
                    }
                    if (glyph.passthrough) {
                        try { GL11.glEnd(); }
                        catch (Throwable error) {
                            failure = appendFailure(failure, error);
                        } finally {
                            glyph.clear();
                        }
                    }
                    if (traversalError == null) {
                        safeFail(hud, new IllegalStateException(
                            "unterminated font primitive"));
                    }
                }
            } else if (glyph.passthrough) {
                try { GL11.glEnd(); }
                catch (Throwable error) {
                    failure = appendFailure(failure, error);
                } finally {
                    glyph.clear();
                }
            }
            if (traversalError == null && failure == null) {
                sealFontCache(scope, hud);
            }
        } catch (Throwable error) {
            failure = appendFailure(failure, error);
        } finally {
            glyph.clear();
            scope.clear();
            state.fontDepth--;
        }
        if (failure != null) {
            EarlyGlStateTracker.invalidate();
            if (traversalError != null) addSuppressed(traversalError, failure);
            safeFail(hud, failure);
        }
    }

    private static void drainAll(State state, Throwable mismatch) {
        Throwable fatal = null;
        try { drainFonts(state, mismatch); }
        catch (Throwable failure) { fatal = appendFailure(fatal, failure); }
        while (state.hudDepth > 0) {
            HudScope scope = state.currentHud();
            try {
                flush(scope);
                fail(scope, mismatch);
                if (scope.runtime != null) {
                    try {
                        scope.runtime.endRenderBackendSample(scope.sample,
                            false, scope.modernWork);
                    } catch (Throwable cleanupFailure) {
                        try { safeFail(scope, cleanupFailure); }
                        catch (Throwable failure) {
                            fatal = appendFailure(fatal, failure);
                        }
                    }
                }
            } catch (Throwable failure) {
                fatal = appendFailure(fatal, failure);
            } finally {
                try { scope.closePass(); }
                catch (Throwable closeFailure) {
                    fatal = appendFailure(fatal, closeFailure);
                } finally {
                    scope.clearReferences();
                    state.hudDepth--;
                }
            }
        }
        state.hudOverflow = 0;
        state.legacyDepth = 0;
        STATE.remove();
        FatalErrors.rethrowIfFatal(fatal);
    }

    private static void drainFonts(State state, Throwable mismatch) {
        Throwable fatal = null;
        while (state.fontDepth > 0) {
            HudScope hud = state.currentHud();
            try { finishTopFont(state, mismatch); }
            catch (Throwable failure) {
                fatal = appendFailure(fatal, failure);
            }
            if (hud != null) try { fail(hud, mismatch); }
            catch (Throwable failure) {
                fatal = appendFailure(fatal, failure);
            }
        }
        state.fontOverflow = 0;
        FatalErrors.rethrowIfFatal(fatal);
    }

    private static void fail(HudScope scope, Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        if (scope == null) return;
        scope.failed = true;
        scope.modern = false;
        if (scope.runtime != null) try { scope.runtime.hudBackendFailure(error); }
        catch (Throwable reportingFailure) {
            FatalErrors.rethrowIfFatal(reportingFailure);
        }
    }

    private static void safeFail(HudScope scope, Throwable error) {
        try { fail(scope, error); }
        catch (Throwable reportingFailure) {
            FatalErrors.rethrowIfFatal(reportingFailure);
        }
    }

    private static Throwable appendFailure(Throwable first, Throwable next) {
        if (first == null) return next;
        Throwable nextFatal = FatalErrors.findFatal(next);
        if (nextFatal != null && FatalErrors.findFatal(first) == null) {
            addSuppressed(nextFatal, first);
            return nextFatal;
        }
        addSuppressed(first, next);
        return first;
    }

    private static void addSuppressed(Throwable first, Throwable next) {
        if (first == null || next == null || first == next) return;
        first.addSuppressed(next);
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) throw (Error) failure;
        throw new IllegalStateException("HUD font replay failed", failure);
    }

    private static long nextToken() {
        return MonotonicTokenCounter.nextOrZero(NEXT_TOKEN,
            "HUD bridge token");
    }

    private static RuntimeException unwindBeginFatal(State state,
                                                      HudScope scope,
                                                      Throwable fatal) {
        Throwable failure = fatal;
        try { scope.closePass(); }
        catch (Throwable closeFailure) {
            failure = appendFailure(failure, closeFailure);
        } finally {
            scope.clearReferences();
            state.hudDepth--;
            if (state.hudDepth == 0 && state.hudOverflow == 0
                && state.fontDepth == 0 && state.fontOverflow == 0
                && state.legacyDepth == 0) STATE.remove();
        }
        FatalErrors.rethrowIfFatal(failure);
        return new IllegalStateException("fatal HUD bridge failure", failure);
    }

    private static RuntimeException unwindFontBeginFatal(State state,
                                                          FontScope scope,
                                                          Throwable fatal) {
        scope.clear();
        state.fontDepth--;
        FatalErrors.rethrowIfFatal(fatal);
        return new IllegalStateException("fatal HUD font bridge failure", fatal);
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static boolean cacheableFont(Object font, String text) {
        if (font == null || font.getClass() != FontRenderer.class
            || !(font instanceof FontRenderCacheAccess) || text == null
            || text.length() > MAX_CACHED_FONT_CHARS) return false;
        try {
            FontRenderer renderer = (FontRenderer) font;
            if (renderer.getUnicodeFlag() || renderer.getBidiFlag()
                || !((FontRenderCacheAccess) font).ice$fontStylesClear()) {
                return false;
            }
            for (int index = 0; index < text.length(); index++) {
                char value = text.charAt(index);
                if (value < 32 || value > 126) return false;
            }
            return true;
        } catch (Throwable unavailable) {
            FatalErrors.rethrowIfFatal(unavailable);
            return false;
        }
    }

    private static void sealFontCache(FontScope scope, HudScope hud) {
        if (scope == null || hud == null || !scope.cacheEligible
            || scope.cached != null || scope.cachedReplay || scope.captureFailed
            || !(scope.font instanceof FontRenderCacheAccess)
            || hud.runtime == null || hud.runtime.resourceGeneration()
                != scope.fontGeneration) return;
        float finalX;
        try { finalX = ((FontRenderCacheAccess) scope.font).ice$fontPosX(); }
        catch (Throwable unavailable) {
            FatalErrors.rethrowIfFatal(unavailable);
            return;
        }
        float advance = finalX - scope.originX;
        if (!finite(advance) || advance < 0.0F) return;
        int floats = scope.glyphCount * 20;
        float[] geometry = floats == 0 ? new float[0]
            : Arrays.copyOf(scope.geometry, floats);
        int[] pages = scope.glyphCount == 0 ? new int[0]
            : Arrays.copyOf(scope.pages, scope.glyphCount);
        try {
            FontLayoutCache cache = hud.runtime.fonts();
            if (cache != null && cache.put(scope.font, scope.text,
                scope.shadow ? 1 : 0, false, scope.fontGeneration,
                new FontLayoutCache.GlyphLayout(geometry, pages, advance))) {
                hud.fontCacheMisses++;
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            disableFontCache(hud, failure);
        }
    }

    private static void disableFontCache(HudScope hud, Throwable failure) {
        FatalErrors.rethrowIfFatal(failure);
        if (hud == null || hud.runtime == null) return;
        try {
            FontLayoutCache cache = hud.runtime.fonts();
            if (cache != null) cache.disable(failure);
        } catch (Throwable disableFailure) {
            FatalErrors.rethrowIfFatal(disableFailure);
        }
    }

    private static int normalizedFontColor(int color, boolean shadow) {
        if ((color & -67108864) == 0) color |= -16777216;
        if (shadow) color = (color & 16579836) >> 2 | color & -16777216;
        return color;
    }

    private static boolean same(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private static final class State {
        private final HudScope[] hudScopes = new HudScope[MAX_HUD_DEPTH];
        private final FontScope[] fontScopes = new FontScope[MAX_FONT_DEPTH];
        private int hudDepth;
        private int hudOverflow;
        private int fontDepth;
        private int fontOverflow;
        private int legacyDepth;

        private State() {
            for (int i = 0; i < hudScopes.length; i++) hudScopes[i] = new HudScope();
            for (int i = 0; i < fontScopes.length; i++) fontScopes[i] = new FontScope();
        }

        private HudScope currentHud() {
            return hudDepth == 0 ? null : hudScopes[hudDepth - 1];
        }

        private FontScope currentFont() {
            return fontDepth == 0 ? null : fontScopes[fontDepth - 1];
        }
    }

    private static final class HudScope {
        private long token;
        private ModernRendererRuntime runtime;
        private ModernRendererRuntime.RenderBackendSample sample;
        private LwjglHudRenderer renderer;
        private long resourceGeneration;
        private long contextGeneration;
        private long initialModernFlushes;
        private int fallbacks;
        private int fontCacheHits;
        private int fontCacheMisses;
        private boolean modern;
        private boolean modernWork;
        private boolean requiresLegacyBoundary;
        private boolean failed;
        private boolean stale;
        private long passToken;

        private void reset(long value) {
            token = value;
            runtime = null;
            sample = null;
            renderer = null;
            resourceGeneration = 0L;
            contextGeneration = 0L;
            initialModernFlushes = 0L;
            fallbacks = 0;
            fontCacheHits = 0;
            fontCacheMisses = 0;
            modern = false;
            modernWork = false;
            requiresLegacyBoundary = false;
            failed = false;
            stale = false;
            passToken = 0L;
        }

        private void closePass() {
            long value = passToken;
            passToken = 0L;
            if (runtime != null && value != 0L) {
                try { runtime.endObservedPass(value); }
                catch (Throwable cleanupFailure) {
                    safeFail(this, cleanupFailure);
                }
            }
        }

        private void clearReferences() {
            runtime = null;
            sample = null;
            renderer = null;
        }
    }

    interface EventInvocation { boolean invoke(); }

    interface LegacyEventInvocation {
        boolean invoke(EventInvocation invocation) throws Exception;
    }

    private static final class FontScope {
        private long token;
        private long hudToken;
        private boolean shadow;
        private Object font;
        private String text;
        private float originX;
        private float originY;
        private long fontGeneration;
        private boolean cacheEligible;
        private boolean cachedReplay;
        private boolean captureFailed;
        private FontLayoutCache.GlyphLayout cached;
        private float[] geometry;
        private int[] pages;
        private int glyphCount;
        private final Glyph glyph = new Glyph();

        private void reset(long value, long hud, boolean isShadow,
                           Object font, String text, float x, float y,
                           long generation) {
            token = value;
            hudToken = hud;
            shadow = isShadow;
            this.font = font;
            this.text = text;
            originX = x;
            originY = y;
            fontGeneration = generation;
            cacheEligible = false;
            cachedReplay = false;
            captureFailed = false;
            cached = null;
            geometry = null;
            pages = null;
            glyphCount = 0;
            glyph.clear();
        }

        private void capture(float[] vertices, int page) {
            if (!cacheEligible || cached != null || cachedReplay || captureFailed) {
                return;
            }
            if (vertices == null || vertices.length < 20 || page <= 0
                || text == null || glyphCount >= text.length()) {
                captureFailed = true;
                return;
            }
            if (glyphCount != 0 && pages[0] != page) {
                captureFailed = true;
                return;
            }
            try {
                int next = glyphCount + 1;
                int needed = Math.multiplyExact(next, 20);
                if (geometry == null) {
                    int initialGlyphs = Math.max(1, Math.min(text.length(), 16));
                    geometry = new float[Math.multiplyExact(initialGlyphs, 20)];
                    pages = new int[initialGlyphs];
                } else if (needed > geometry.length) {
                    int grownGlyphs = Math.min(text.length(),
                        Math.max(next, pages.length * 2));
                    geometry = Arrays.copyOf(geometry,
                        Math.multiplyExact(grownGlyphs, 20));
                    pages = Arrays.copyOf(pages, grownGlyphs);
                }
                int base = glyphCount * 20;
                for (int vertex = 0; vertex < 4; vertex++) {
                    int source = vertex * 5;
                    int target = base + source;
                    geometry[target] = vertices[source] - originX;
                    geometry[target + 1] = vertices[source + 1] - originY;
                    geometry[target + 2] = vertices[source + 2];
                    geometry[target + 3] = vertices[source + 3];
                    geometry[target + 4] = vertices[source + 4];
                }
                pages[glyphCount] = page;
                glyphCount = next;
            } catch (Throwable failure) {
                captureFailed = true;
                geometry = null;
                pages = null;
                glyphCount = 0;
                FatalErrors.rethrowIfFatal(failure);
            }
        }

        private void clear() {
            token = 0L;
            hudToken = 0L;
            shadow = false;
            font = null;
            text = null;
            originX = 0.0F;
            originY = 0.0F;
            fontGeneration = 0L;
            cacheEligible = false;
            cachedReplay = false;
            captureFailed = false;
            cached = null;
            geometry = null;
            pages = null;
            glyphCount = 0;
            glyph.clear();
        }
    }

    /** Captures the exact texcoord/vertex call order for lossless replay. */
    private static final class Glyph {
        private static final byte TEXCOORD = 1;
        private static final byte VERTEX = 2;
        private final byte[] operations = new byte[12];
        private final float[] first = new float[12];
        private final float[] second = new float[12];
        private final float[] third = new float[12];
        private final float[] vertices = new float[20];
        private int mode;
        private int operationCount;
        private int vertexCount;
        private float u;
        private float v;
        private boolean capturing;
        private boolean passthrough;

        private void begin(int value) {
            clear();
            mode = value;
            capturing = true;
        }

        private void beginPassthrough(int value) {
            clear();
            mode = value;
            passthrough = true;
        }

        private boolean captureTexCoord(float nextU, float nextV) {
            if (operationCount >= operations.length) return false;
            operations[operationCount] = TEXCOORD;
            first[operationCount] = nextU;
            second[operationCount] = nextV;
            operationCount++;
            u = nextU;
            v = nextV;
            return true;
        }

        private boolean captureVertex(float x, float y, float z) {
            if (operationCount >= operations.length || vertexCount >= 4) return false;
            operations[operationCount] = VERTEX;
            first[operationCount] = x;
            second[operationCount] = y;
            third[operationCount] = z;
            operationCount++;
            int offset = vertexCount * 5;
            vertices[offset] = x;
            vertices[offset + 1] = y;
            vertices[offset + 2] = z;
            vertices[offset + 3] = u;
            vertices[offset + 4] = v;
            vertexCount++;
            return true;
        }

        private void replay(boolean close) {
            boolean begun = false;
            boolean endAttempted = false;
            Throwable failure = null;
            try {
                GL11.glBegin(mode);
                begun = true;
                for (int operation = 0; operation < operationCount; operation++) {
                    if (operations[operation] == TEXCOORD) {
                        GL11.glTexCoord2f(first[operation], second[operation]);
                    } else {
                        GL11.glVertex3f(first[operation], second[operation],
                            third[operation]);
                    }
                }
                if (close) {
                    endAttempted = true;
                    GL11.glEnd();
                    begun = false;
                }
            } catch (Throwable error) {
                failure = error;
            } finally {
                // On an operation failure, close a begin which definitely has
                // not yet seen glEnd.  Never retry a throwing glEnd because its
                // native outcome is uncertain.
                if (failure != null && begun && !endAttempted) {
                    try { GL11.glEnd(); }
                    catch (Throwable cleanupFailure) {
                        failure = appendFailure(failure, cleanupFailure);
                    }
                }
            }
            if (failure != null) rethrow(failure);
        }

        private boolean isActive() { return capturing || passthrough; }

        private void clear() {
            mode = 0;
            operationCount = 0;
            vertexCount = 0;
            u = 0.0F;
            v = 0.0F;
            capturing = false;
            passthrough = false;
        }
    }
}
