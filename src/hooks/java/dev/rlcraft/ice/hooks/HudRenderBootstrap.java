package dev.rlcraft.ice.hooks;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.EventBus;
import org.lwjgl.opengl.GL11;

/**
 * Core-JAR trampoline for the certified Forge HUD scope.
 *
 * <p>The transformed Minecraft/Forge classes can be loaded before the normal
 * optimizer JAR.  Until the main bridge is installed every candidate draw
 * executes its untouched method and every event is posted directly.</p>
 */
public final class HudRenderBootstrap {
    private static final MethodType BEGIN = MethodType.methodType(long.class,
        Object.class, float.class);
    private static final MethodType VOID = MethodType.methodType(void.class);
    private static final MethodType POST = MethodType.methodType(boolean.class,
        Object.class, Object.class);
    private static final MethodType RECT_INT = MethodType.methodType(boolean.class,
        Object.class, int.class, int.class, int.class, int.class, int.class,
        int.class, float.class);
    private static final MethodType RECT_FLOAT = MethodType.methodType(boolean.class,
        Object.class, float.class, float.class, int.class, int.class, int.class,
        int.class, float.class);
    private static final MethodType RECT_SPRITE = MethodType.methodType(boolean.class,
        Object.class, int.class, int.class, Object.class, int.class, int.class,
        float.class);
    private static final MethodType RECT_CUSTOM = MethodType.methodType(boolean.class,
        int.class, int.class, float.class, float.class, int.class, int.class,
        float.class, float.class);
    private static final MethodType RECT_SCALED = MethodType.methodType(boolean.class,
        int.class, int.class, float.class, float.class, int.class, int.class,
        int.class, int.class, float.class, float.class);
    private static final MethodType END = MethodType.methodType(void.class, long.class);
    private static final MethodType ABORT = MethodType.methodType(void.class,
        long.class, Throwable.class);
    private static final MethodType FONT_STRING_BEGIN = MethodType.methodType(
        long.class, Object.class, String.class, float.class, float.class,
        int.class, boolean.class);
    private static final MethodType FONT_STRING_CACHED = MethodType.methodType(
        int.class, long.class, Object.class, String.class, float.class,
        float.class, int.class, boolean.class);
    private static final MethodType FONT_BEGIN = MethodType.methodType(void.class,
        int.class);
    private static final MethodType FONT_TEXCOORD = MethodType.methodType(void.class,
        float.class, float.class);
    private static final MethodType FONT_VERTEX = MethodType.methodType(void.class,
        float.class, float.class, float.class);
    private static final MethodType DIRECT_COLOR = MethodType.methodType(void.class,
        float.class, float.class, float.class, float.class);
    private static final MethodType TEXTURE_BARRIER = MethodType.methodType(void.class,
        int.class);
    private static volatile Delegate delegate;

    private HudRenderBootstrap() {
    }

    public static boolean install(Class<?> bridgeType) {
        if (bridgeType == null) return false;
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            delegate = new Delegate(
                lookup.findStatic(bridgeType, "begin", BEGIN),
                lookup.findStatic(bridgeType, "barrier", VOID),
                lookup.findStatic(bridgeType, "post", POST),
                lookup.findStatic(bridgeType, "tryTexturedRect", RECT_INT),
                lookup.findStatic(bridgeType, "tryTexturedRectFloat", RECT_FLOAT),
                lookup.findStatic(bridgeType, "tryTexturedSprite", RECT_SPRITE),
                lookup.findStatic(bridgeType, "tryCustomTexture", RECT_CUSTOM),
                lookup.findStatic(bridgeType, "tryScaledTexture", RECT_SCALED),
                lookup.findStatic(bridgeType, "end", END),
                lookup.findStatic(bridgeType, "abort", ABORT),
                lookup.findStatic(bridgeType, "fontStringBegin", FONT_STRING_BEGIN),
                lookup.findStatic(bridgeType, "tryCachedFontString",
                    FONT_STRING_CACHED),
                lookup.findStatic(bridgeType, "fontStringEnd", END),
                lookup.findStatic(bridgeType, "fontStringAbort", ABORT),
                lookup.findStatic(bridgeType, "fontBegin", FONT_BEGIN),
                lookup.findStatic(bridgeType, "fontTexCoord", FONT_TEXCOORD),
                lookup.findStatic(bridgeType, "fontVertex", FONT_VERTEX),
                lookup.findStatic(bridgeType, "fontEnd", VOID),
                lookup.findStatic(bridgeType, "directColor", DIRECT_COLOR),
                lookup.findStatic(bridgeType, "textureBarrier", TEXTURE_BARRIER));
            return true;
        } catch (Throwable incompatible) {
            HookFatalErrors.rethrowIfFatal(incompatible);
            delegate = null;
            return false;
        }
    }

    public static long begin(Object overlay, float partialTicks) {
        Delegate current = delegate;
        if (current == null) return 0L;
        try { return (long) current.begin.invokeExact(overlay, partialTicks); }
        catch (Throwable ignored) {
            HookFatalErrors.rethrowIfFatal(ignored);
            return 0L;
        }
    }

    public static void barrier() {
        Delegate current = delegate;
        if (current == null) return;
        try { current.barrier.invokeExact(); }
        catch (Throwable ignored) { HookFatalErrors.rethrowIfFatal(ignored); }
    }

    /** Event exceptions are propagated once; a failed handler is never reposted. */
    public static boolean post(Object bus, Object event) {
        Delegate current = delegate;
        if (current == null) return ((EventBus) bus).post((Event) event);
        try { return (boolean) current.post.invokeExact(bus, event); }
        catch (Throwable original) {
            HookFatalErrors.rethrowIfFatal(original);
            return HudRenderBootstrap.<RuntimeException, Boolean>raise(original);
        }
    }

    public static boolean tryTexturedRect(Object gui, int x, int y, int u, int v,
                                          int width, int height, float z) {
        Delegate current = delegate;
        if (current == null) return false;
        try {
            return (boolean) current.rectInt.invokeExact(gui, x, y, u, v,
                width, height, z);
        } catch (Throwable ignored) {
            HookFatalErrors.rethrowIfFatal(ignored);
            return false;
        }
    }

    public static boolean tryTexturedRectFloat(Object gui, float x, float y,
                                               int u, int v, int width,
                                               int height, float z) {
        Delegate current = delegate;
        if (current == null) return false;
        try {
            return (boolean) current.rectFloat.invokeExact(gui, x, y, u, v,
                width, height, z);
        } catch (Throwable ignored) {
            HookFatalErrors.rethrowIfFatal(ignored);
            return false;
        }
    }

    public static boolean tryTexturedSprite(Object gui, int x, int y,
                                            Object sprite, int width,
                                            int height, float z) {
        Delegate current = delegate;
        if (current == null) return false;
        try {
            return (boolean) current.rectSprite.invokeExact(gui, x, y, sprite,
                width, height, z);
        } catch (Throwable ignored) {
            HookFatalErrors.rethrowIfFatal(ignored);
            return false;
        }
    }

    public static boolean tryCustomTexture(int x, int y, float u, float v,
                                           int width, int height,
                                           float textureWidth,
                                           float textureHeight) {
        Delegate current = delegate;
        if (current == null) return false;
        try {
            return (boolean) current.rectCustom.invokeExact(x, y, u, v, width,
                height, textureWidth, textureHeight);
        } catch (Throwable ignored) {
            HookFatalErrors.rethrowIfFatal(ignored);
            return false;
        }
    }

    public static boolean tryScaledTexture(int x, int y, float u, float v,
                                           int sourceWidth, int sourceHeight,
                                           int width, int height,
                                           float textureWidth,
                                           float textureHeight) {
        Delegate current = delegate;
        if (current == null) return false;
        try {
            return (boolean) current.rectScaled.invokeExact(x, y, u, v,
                sourceWidth, sourceHeight, width, height, textureWidth,
                textureHeight);
        } catch (Throwable ignored) {
            HookFatalErrors.rethrowIfFatal(ignored);
            return false;
        }
    }

    public static void end(long token) {
        Delegate current = delegate;
        if (current == null || token == 0L) return;
        try { current.end.invokeExact(token); }
        catch (Throwable ignored) { HookFatalErrors.rethrowIfFatal(ignored); }
    }

    public static void abort(long token, Throwable error) {
        Delegate current = delegate;
        if (current == null || token == 0L) return;
        try { current.abort.invokeExact(token, error); }
        catch (Throwable ignored) { HookFatalErrors.rethrowIfFatal(ignored); }
    }

    public static long fontStringBegin(Object font, String text, float x,
                                       float y, int color, boolean shadow) {
        Delegate current = delegate;
        if (current == null) return 0L;
        try {
            return (long) current.fontStringBegin.invokeExact(font, text, x, y,
                color, shadow);
        }
        catch (Throwable ignored) {
            HookFatalErrors.rethrowIfFatal(ignored);
            return 0L;
        }
    }

    public static int tryCachedFontString(long token, Object font, String text,
                                          float x, float y, int color,
                                          boolean shadow) {
        Delegate current = delegate;
        if (current == null || token == 0L) return Integer.MIN_VALUE;
        try {
            return (int) current.cachedFontString.invokeExact(token, font, text,
                x, y, color, shadow);
        } catch (Throwable failure) {
            HookFatalErrors.rethrowIfFatal(failure);
            return HudRenderBootstrap.<RuntimeException, Integer>raise(failure);
        }
    }

    public static void fontStringEnd(long token) {
        Delegate current = delegate;
        if (current == null || token == 0L) return;
        try { current.fontStringEnd.invokeExact(token); }
        catch (Throwable ignored) { HookFatalErrors.rethrowIfFatal(ignored); }
    }

    public static void fontStringAbort(long token, Throwable error) {
        Delegate current = delegate;
        if (current == null || token == 0L) return;
        try { current.fontStringAbort.invokeExact(token, error); }
        catch (Throwable ignored) { HookFatalErrors.rethrowIfFatal(ignored); }
    }

    public static void fontBegin(int mode) {
        Delegate current = delegate;
        if (current == null) { GL11.glBegin(mode); return; }
        try { current.fontBegin.invokeExact(mode); }
        catch (Throwable failed) { rethrowCommittedDelegateFailure(failed); }
    }

    public static void fontTexCoord(float u, float v) {
        Delegate current = delegate;
        if (current == null) { GL11.glTexCoord2f(u, v); return; }
        try { current.fontTexCoord.invokeExact(u, v); }
        catch (Throwable failed) { rethrowCommittedDelegateFailure(failed); }
    }

    public static void fontVertex(float x, float y, float z) {
        Delegate current = delegate;
        if (current == null) { GL11.glVertex3f(x, y, z); return; }
        try { current.fontVertex.invokeExact(x, y, z); }
        catch (Throwable failed) { rethrowCommittedDelegateFailure(failed); }
    }

    public static void fontEnd() {
        Delegate current = delegate;
        if (current == null) { GL11.glEnd(); return; }
        try { current.fontEnd.invokeExact(); }
        catch (Throwable failed) { rethrowCommittedDelegateFailure(failed); }
    }

    public static void directColor(float red, float green, float blue, float alpha) {
        Delegate current = delegate;
        if (current == null) { GL11.glColor4f(red, green, blue, alpha); return; }
        try { current.directColor.invokeExact(red, green, blue, alpha); }
        catch (Throwable failed) { rethrowCommittedDelegateFailure(failed); }
    }

    public static void textureBarrier(int texture) {
        Delegate current = delegate;
        if (current == null) return;
        try { current.textureBarrier.invokeExact(texture); }
        catch (Throwable ignored) { HookFatalErrors.rethrowIfFatal(ignored); }
    }

    static void resetForTest() { delegate = null; }

    /**
     * A delegate may have reached native GL before it throws.  Its commit
     * state is therefore unknown and replaying the original fixed-function
     * call could submit the same transparent work twice.
     */
    private static void rethrowCommittedDelegateFailure(Throwable error) {
        HookFatalErrors.rethrowIfFatal(error);
        HudRenderBootstrap.<RuntimeException, Void>raise(error);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable, T> T raise(Throwable error) throws E {
        throw (E) error;
    }

    private static final class Delegate {
        private final MethodHandle begin;
        private final MethodHandle barrier;
        private final MethodHandle post;
        private final MethodHandle rectInt;
        private final MethodHandle rectFloat;
        private final MethodHandle rectSprite;
        private final MethodHandle rectCustom;
        private final MethodHandle rectScaled;
        private final MethodHandle end;
        private final MethodHandle abort;
        private final MethodHandle fontStringBegin;
        private final MethodHandle cachedFontString;
        private final MethodHandle fontStringEnd;
        private final MethodHandle fontStringAbort;
        private final MethodHandle fontBegin;
        private final MethodHandle fontTexCoord;
        private final MethodHandle fontVertex;
        private final MethodHandle fontEnd;
        private final MethodHandle directColor;
        private final MethodHandle textureBarrier;

        private Delegate(MethodHandle begin, MethodHandle barrier,
                         MethodHandle post, MethodHandle rectInt,
                         MethodHandle rectFloat, MethodHandle rectSprite,
                         MethodHandle rectCustom, MethodHandle rectScaled,
                          MethodHandle end, MethodHandle abort,
                          MethodHandle fontStringBegin,
                          MethodHandle cachedFontString,
                          MethodHandle fontStringEnd,
                         MethodHandle fontStringAbort, MethodHandle fontBegin,
                         MethodHandle fontTexCoord, MethodHandle fontVertex,
                         MethodHandle fontEnd, MethodHandle directColor,
                         MethodHandle textureBarrier) {
            this.begin = begin;
            this.barrier = barrier;
            this.post = post;
            this.rectInt = rectInt;
            this.rectFloat = rectFloat;
            this.rectSprite = rectSprite;
            this.rectCustom = rectCustom;
            this.rectScaled = rectScaled;
            this.end = end;
            this.abort = abort;
            this.fontStringBegin = fontStringBegin;
            this.cachedFontString = cachedFontString;
            this.fontStringEnd = fontStringEnd;
            this.fontStringAbort = fontStringAbort;
            this.fontBegin = fontBegin;
            this.fontTexCoord = fontTexCoord;
            this.fontVertex = fontVertex;
            this.fontEnd = fontEnd;
            this.directColor = directColor;
            this.textureBarrier = textureBarrier;
        }
    }
}
