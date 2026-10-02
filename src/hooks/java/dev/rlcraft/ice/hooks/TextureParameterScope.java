package dev.rlcraft.ice.hooks;

import java.io.InputStream;
import java.net.URL;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Parameter reuse is limited to one reviewed FoamFix sprite update. */
final class TextureParameterScope {
    static final String FOAMFIX_SPRITE = "pl.asie.foamfix.client.FastTextureAtlasSprite";
    static final String FOAMFIX_CLASS_SHA256 =
        "6ef144e981602abdd7a91ea903d44d9f7574f97e8c2bccf3d66fc37a1c5d4436";
    private static final ClassValue<Boolean> REVIEWED = new ClassValue<Boolean>() {
        @Override protected Boolean computeValue(Class<?> type) {
            return Boolean.valueOf(reviewedDefinition(type));
        }
    };
    private static final ThreadLocal<State> STATE = new ThreadLocal<State>() {
        @Override protected State initialValue() { return new State(); }
    };

    private TextureParameterScope() { }

    static void beforeSprite(Object sprite) {
        State state = STATE.get();
        state.depth++;
        state.reuse = state.depth == 1 && sprite != null
            && FOAMFIX_SPRITE.equals(sprite.getClass().getName())
            && TextureUploadBootstrap.allowParameterDeduplication()
            && REVIEWED.get(sprite.getClass()).booleanValue();
        state.previousMip = -1;
    }

    static void afterSprite() {
        State state = STATE.get();
        if (state.depth > 0) state.depth--;
        state.reuse = false;
        state.previousMip = -1;
    }

    static void barrier() {
        State state = STATE.get();
        state.reuse = false;
        state.previousMip = -1;
    }

    static void endAtlas() {
        State state = STATE.get();
        state.depth = 0;
        state.reuse = false;
        state.previousMip = -1;
    }

    /** The first level of every helper still writes all four parameters. */
    public static boolean skipParameters(int mip, boolean blur, boolean clamp,
                                          boolean mipFilter) {
        State state = STATE.get();
        if (!state.reuse) return false;
        int parameters = (blur ? 1 : 0) | (clamp ? 2 : 0) | (mipFilter ? 4 : 0);
        boolean skip = mip > 0 && mip == state.previousMip + 1
            && parameters == state.parameters;
        state.previousMip = mip;
        state.parameters = parameters;
        return skip;
    }

    static boolean reviewedClassForTest(Class<?> type) {
        return REVIEWED.get(type).booleanValue();
    }

    static void beginVerifiedForTest() {
        State state = STATE.get();
        state.depth++;
        state.reuse = state.depth == 1;
        state.previousMip = -1;
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
    }
}
