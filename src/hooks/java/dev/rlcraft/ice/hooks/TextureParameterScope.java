package dev.rlcraft.ice.hooks;

import java.io.InputStream;
import java.net.URL;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Reuses parameters within and between contiguous reviewed FoamFix sprites. */
final class TextureParameterScope {
    static final String FOAMFIX_SPRITE = "pl.asie.foamfix.client.FastTextureAtlasSprite";
    static final String FOAMFIX_CLASS_SHA256 =
        "6ef144e981602abdd7a91ea903d44d9f7574f97e8c2bccf3d66fc37a1c5d4436";
    private static final ClassValue<Boolean> REVIEWED = new ClassValue<Boolean>() {
        @Override protected Boolean computeValue(Class<?> type) {
            return Boolean.valueOf(reviewedDefinition(type));
        }
    };
    private static final ClassValue<java.lang.invoke.MethodHandle> TEXTURE_ID = new ClassValue<java.lang.invoke.MethodHandle>() {
        @Override protected java.lang.invoke.MethodHandle computeValue(Class<?> type) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField("textureId");
                field.setAccessible(true);
                return java.lang.invoke.MethodHandles.lookup().unreflectGetter(field)
                    .asType(java.lang.invoke.MethodType.methodType(int.class, Object.class));
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }
    };
    private static final ThreadLocal<State> STATE = new ThreadLocal<State>() {
        @Override protected State initialValue() { return new State(); }
    };

    private TextureParameterScope() { }

    static void beforeSprite(Object sprite) {
        boolean reviewed = reviewedSprite(sprite) && TextureUploadBootstrap.allowParameterDeduplication();
        beginSprite(STATE.get(), reviewed);
    }

    static boolean streamSprite(Object sprite) {
        if (!reviewedSprite(sprite)) return false;
        try {
            // FoamFix's GPU-copy branch binds a private texture. Flush before
            // entering that branch; only its CPU mip helper joins the atlas stream.
            return (int) TEXTURE_ID.get(sprite.getClass()).invokeExact(sprite) == -1;
        } catch (Throwable failure) { HookFatalErrors.rethrowIfFatal(failure); return false; }
    }

    private static boolean reviewedSprite(Object sprite) {
        return sprite != null && FOAMFIX_SPRITE.equals(sprite.getClass().getName())
            && REVIEWED.get(sprite.getClass()).booleanValue();
    }

    private static void beginSprite(State state, boolean reviewed) {
        boolean contiguous = state.depth == 0 && state.valid;
        state.depth++;
        state.reuse = state.depth == 1 && reviewed;
        state.firstLevelReuse = state.reuse && contiguous;
        if (!state.reuse) state.valid = false;
        state.previousMip = -1;
    }

    static void afterSprite() {
        State state = STATE.get();
        state.valid &= state.depth == 1 && state.reuse;
        if (state.depth > 0) state.depth--;
        state.reuse = false;
        state.firstLevelReuse = false;
        state.previousMip = -1;
    }

    static void barrier() {
        State state = STATE.get();
        state.reuse = false;
        state.valid = false;
        state.firstLevelReuse = false;
        state.previousMip = -1;
    }

    static void endAtlas() {
        State state = STATE.get();
        state.depth = 0;
        state.reuse = false;
        state.valid = false;
        state.firstLevelReuse = false;
        state.previousMip = -1;
    }

    /** Only a new contiguous sprite may reuse its first helper's first level. */
    public static boolean skipParameters(int mip, boolean blur, boolean clamp,
                                          boolean mipFilter) {
        State state = STATE.get();
        if (!state.reuse) {
            state.valid = false;
            return false;
        }
        int parameters = (blur ? 1 : 0) | (clamp ? 2 : 0) | (mipFilter ? 4 : 0);
        boolean skip = parameters == state.parameters
            && (state.firstLevelReuse && mip == 0
                || mip > 0 && mip == state.previousMip + 1);
        state.firstLevelReuse = false;
        state.previousMip = mip;
        state.parameters = parameters;
        state.valid = true;
        return skip;
    }

    static boolean reviewedClassForTest(Class<?> type) {
        return REVIEWED.get(type).booleanValue();
    }

    static void beginVerifiedForTest() {
        beginSprite(STATE.get(), true);
    }

    private static boolean reviewedDefinition(Class<?> type) {
        if (!FOAMFIX_SPRITE.equals(type.getName())) return false;
        // FoamFix deliberately excludes this whole package from Launch's
        // transformer chain. Read the defining JAR, not an ambiguous parent
        // classpath resource, once for each class definition.
        try {
            CodeSource source = type.getProtectionDomain().getCodeSource();
            URL location = source == null ? null : source.getLocation();
            if (location == null || !"file".equals(location.getProtocol())) return false;
            JarFile jar = new JarFile(new java.io.File(location.toURI()));
            try {
                JarEntry entry = jar.getJarEntry(type.getName().replace('.', '/') + ".class");
                if (entry == null) return false;
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                InputStream input = jar.getInputStream(entry);
                try {
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
                } finally { input.close(); }
                StringBuilder hexadecimal = new StringBuilder(64);
                for (byte value : digest.digest()) {
                    int unsigned = value & 255;
                    hexadecimal.append(Character.forDigit(unsigned >>> 4, 16));
                    hexadecimal.append(Character.forDigit(unsigned & 15, 16));
                }
                return FOAMFIX_CLASS_SHA256.contentEquals(hexadecimal);
            } finally { jar.close(); }
        } catch (Throwable unavailable) {
            HookFatalErrors.rethrowIfFatal(unavailable);
            return false;
        }
    }

    private static final class State {
        int depth;
        int previousMip = -1;
        int parameters;
        boolean reuse;
        boolean valid;
        boolean firstLevelReuse;
    }
}
