package dev.rlcraft.ice.optimizer.compat.orelib;

import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;

/**
 * Reads reviewed OpenGL state from Minecraft's Java-side cache while retaining
 * native sentinels, periodic full validation, and a permanent fail-open path.
 */
public final class OreLibGlStateBridge {
    private static final String MODULE = "orelib-gl-state";
    private static final int FULL_VALIDATION_INTERVAL = 32;

    private static final int GL_CULL_FACE = 2884;
    private static final int GL_CULL_FACE_MODE = 2885;
    private static final int GL_LIGHTING = 2896;
    private static final int GL_DEPTH_TEST = 2929;
    private static final int GL_DEPTH_WRITEMASK = 2930;
    private static final int GL_DEPTH_FUNC = 2932;
    private static final int GL_NORMALIZE = 2977;
    private static final int GL_ALPHA_TEST = 3008;
    private static final int GL_ALPHA_TEST_FUNC = 3009;
    private static final int GL_ALPHA_TEST_REF = 3010;
    private static final int GL_BLEND_DST = 3040;
    private static final int GL_BLEND_SRC = 3041;
    private static final int GL_BLEND = 3042;
    private static final int GL_TEXTURE_2D = 3553;
    private static final int GL_RESCALE_NORMAL = 32826;
    private static final int GL_BLEND_EQUATION_RGB = 32777;

    private static final AtomicLong SNAPSHOTS = new AtomicLong();
    private static final ThreadLocal<ValidationState> VALIDATION =
        new ThreadLocal<ValidationState>() {
            @Override protected ValidationState initialValue() { return new ValidationState(); }
        };
    private static final NativeQueries LWJGL_QUERIES = new NativeQueries() {
        @Override public int getInteger(int pname) { return GL11.glGetInteger(pname); }
        @Override public float getFloat(int pname) { return GL11.glGetFloat(pname); }
    };

    private static volatile NativeQueries nativeQueries = LWJGL_QUERIES;
    private static volatile Access access;
    private static volatile boolean cacheTrusted;
    private static volatile boolean cacheRejected;
    private static volatile boolean activated;

    private OreLibGlStateBridge() {
    }

    public static int getInteger(int pname) {
        NativeQueries nativeAccess = nativeQueries;
        ValidationState validation = VALIDATION.get();
        if (!OptimizerBridge.isEnabled(MODULE) || cacheRejected) {
            int result = nativeAccess.getInteger(pname);
            if (pname == GL_TEXTURE_2D) validation.reset();
            return result;
        }

        if (pname == GL_BLEND) {
            validation.reset();
            long snapshot = SNAPSHOTS.incrementAndGet();
            validation.full = !cacheTrusted || snapshot % FULL_VALIDATION_INTERVAL == 0L;
            if (validation.full) return validateInteger(pname, nativeAccess);

            int actual = nativeAccess.getInteger(pname);
            try {
                int cached = state().readInteger(pname);
                if (actual != cached) rejectMismatch(pname, actual, cached);
            } catch (Throwable error) {
                rejectAccess(error);
            }
            return actual;
        }

        if (pname == GL_BLEND_EQUATION_RGB) return nativeAccess.getInteger(pname);

        if (pname == GL_TEXTURE_2D) {
            int actual = nativeAccess.getInteger(pname);
            try {
                if (!cacheRejected && (validation.full || cacheTrusted)) {
                    int cached = state().readInteger(pname);
                    if (actual != cached) rejectMismatch(pname, actual, cached);
                }
                if (!cacheRejected && validation.full) {
                    cacheTrusted = true;
                    activateOnce();
                }
            } catch (Throwable error) {
                rejectAccess(error);
            } finally {
                validation.reset();
            }
            return actual;
        }

        if (!Access.supportsInteger(pname)) return nativeAccess.getInteger(pname);
        if (validation.full) return validateInteger(pname, nativeAccess);
        if (!cacheTrusted) return nativeAccess.getInteger(pname);
        try {
            return state().readInteger(pname);
        } catch (Throwable error) {
            rejectAccess(error);
            return nativeAccess.getInteger(pname);
        }
    }

    public static float getFloat(int pname) {
        NativeQueries nativeAccess = nativeQueries;
        if (!OptimizerBridge.isEnabled(MODULE) || cacheRejected || pname != GL_ALPHA_TEST_REF) {
            return nativeAccess.getFloat(pname);
        }
        ValidationState validation = VALIDATION.get();
        if (validation.full) {
            float actual = nativeAccess.getFloat(pname);
            try {
                float cached = state().readFloat(pname);
                if (Float.floatToRawIntBits(actual) != Float.floatToRawIntBits(cached)) {
                    rejectMismatch(pname, Float.floatToRawIntBits(actual), Float.floatToRawIntBits(cached));
                }
            } catch (Throwable error) {
                rejectAccess(error);
            }
            return actual;
        }
        if (!cacheTrusted) return nativeAccess.getFloat(pname);
        try {
            return state().readFloat(pname);
        } catch (Throwable error) {
            rejectAccess(error);
            return nativeAccess.getFloat(pname);
        }
    }

    private static int validateInteger(int pname, NativeQueries nativeAccess) {
        int actual = nativeAccess.getInteger(pname);
        try {
            int cached = state().readInteger(pname);
            if (actual != cached) rejectMismatch(pname, actual, cached);
        } catch (Throwable error) {
            rejectAccess(error);
        }
        return actual;
    }

    private static Access state() throws Exception {
        Access current = access;
        if (current != null) return current;
        synchronized (OreLibGlStateBridge.class) {
            current = access;
            if (current == null) {
                current = Access.resolve();
                access = current;
            }
        }
        return current;
    }

    private static void activateOnce() {
        if (activated) return;
        synchronized (OreLibGlStateBridge.class) {
            if (activated) return;
            activated = true;
            OptimizerBridge.activate(MODULE,
                "OreLib GL 快照已通过驱动真值校验；常规路径保留 3 个同步查询");
        }
    }

    private static void rejectMismatch(int pname, int actual, int cached) {
        reject("GL 状态缓存不一致 pname=" + pname + "，驱动=" + actual + "，缓存=" + cached);
    }

    private static void rejectAccess(Throwable error) {
        String message = error == null ? "未知反射错误" : error.getClass().getSimpleName()
            + (error.getMessage() == null ? "" : ": " + error.getMessage());
        reject("无法安全读取 GlStateManager 缓存：" + message);
    }

    private static void reject(String detail) {
        if (cacheRejected) return;
        synchronized (OreLibGlStateBridge.class) {
            if (cacheRejected) return;
            cacheTrusted = false;
            cacheRejected = true;
            OptimizerBridge.incompatible(MODULE, detail + "；本次启动永久回退原 GL 查询");
        }
    }

    interface NativeQueries {
        int getInteger(int pname);
        float getFloat(int pname);
    }

    static void installNativeQueriesForTests(NativeQueries replacement) {
        nativeQueries = replacement == null ? LWJGL_QUERIES : replacement;
    }

    static void resetForTests() {
        nativeQueries = LWJGL_QUERIES;
        access = null;
        cacheTrusted = false;
        cacheRejected = false;
        activated = false;
        SNAPSHOTS.set(0L);
        VALIDATION.remove();
    }

    private static final class ValidationState {
        private boolean full;

        private void reset() {
            full = false;
        }
    }

    private static final class Access {
        private final Object alphaState;
        private final Object alphaTest;
        private final Field alphaFunc;
        private final Field alphaRef;
        private final Object blendState;
        private final Object blend;
        private final Field blendSource;
        private final Field blendDest;
        private final Object depthState;
        private final Object depthTest;
        private final Field depthMask;
        private final Field depthFunc;
        private final Object cullState;
        private final Object cullFace;
        private final Field cullMode;
        private final Object lighting;
        private final Object normal;
        private final Object rescaleNormal;
        private final Field activeTextureUnit;
        private final Object[] textureStates;
        private final Field texture2DState;
        private final Field booleanState;

        private Access(Object alphaState, Object alphaTest, Field alphaFunc, Field alphaRef,
                       Object blendState, Object blend, Field blendSource, Field blendDest,
                       Object depthState, Object depthTest, Field depthMask, Field depthFunc,
                       Object cullState, Object cullFace, Field cullMode, Object lighting,
                       Object normal, Object rescaleNormal, Field activeTextureUnit,
                       Object[] textureStates, Field texture2DState, Field booleanState) {
            this.alphaState = alphaState;
            this.alphaTest = alphaTest;
            this.alphaFunc = alphaFunc;
            this.alphaRef = alphaRef;
            this.blendState = blendState;
            this.blend = blend;
            this.blendSource = blendSource;
            this.blendDest = blendDest;
            this.depthState = depthState;
            this.depthTest = depthTest;
            this.depthMask = depthMask;
            this.depthFunc = depthFunc;
            this.cullState = cullState;
            this.cullFace = cullFace;
            this.cullMode = cullMode;
            this.lighting = lighting;
            this.normal = normal;
            this.rescaleNormal = rescaleNormal;
            this.activeTextureUnit = activeTextureUnit;
            this.textureStates = textureStates;
            this.texture2DState = texture2DState;
            this.booleanState = booleanState;
        }

        private static Access resolve() throws Exception {
            Class<?> owner = GlStateManager.class;
            Object alphaState = staticValue(owner, "alphaState", "field_179160_a");
            Object blendState = staticValue(owner, "blendState", "field_179157_e");
            Object depthState = staticValue(owner, "depthState", "field_179154_f");
            Object cullState = staticValue(owner, "cullState", "field_179167_h");
            Object lighting = staticValue(owner, "lightingState", "field_179158_b");
            Object normal = staticValue(owner, "normalizeState", "field_179161_n");
            Object rescale = staticValue(owner, "rescaleNormalState", "field_179172_r");
            Object[] textures = (Object[]) staticValue(owner, "textureState", "field_179174_p");
            if (textures.length == 0 || textures[0] == null) {
                throw new IllegalStateException("GlStateManager textureState 为空");
            }

            Object alphaTest = value(alphaState, "alphaTest", "field_179208_a");
            Object blend = value(blendState, "blend", "field_179213_a");
            Object depthTest = value(depthState, "depthTest", "field_179052_a");
            Object cullFace = value(cullState, "cullFace", "field_179054_a");
            Field texture2D = field(textures[0].getClass(), "texture2DState", "field_179060_a");
            Object firstTexture = texture2D.get(textures[0]);
            Field currentState = field(firstTexture.getClass(), "currentState", "field_179201_b");
            Field capability = field(firstTexture.getClass(), "capability", "field_179202_a");

            requireCapability(capability, alphaTest, GL_ALPHA_TEST);
            requireCapability(capability, blend, GL_BLEND);
            requireCapability(capability, depthTest, GL_DEPTH_TEST);
            requireCapability(capability, cullFace, GL_CULL_FACE);
            requireCapability(capability, lighting, GL_LIGHTING);
            requireCapability(capability, normal, GL_NORMALIZE);
            requireCapability(capability, rescale, GL_RESCALE_NORMAL);
            for (Object texture : textures) {
                if (texture == null) throw new IllegalStateException("GlStateManager textureState 含空槽");
                requireCapability(capability, texture2D.get(texture), GL_TEXTURE_2D);
            }

            return new Access(alphaState, alphaTest,
                field(alphaState.getClass(), "func", "field_179206_b"),
                field(alphaState.getClass(), "ref", "field_179207_c"),
                blendState, blend,
                field(blendState.getClass(), "srcFactor", "field_179211_b"),
                field(blendState.getClass(), "dstFactor", "field_179212_c"),
                depthState, depthTest,
                field(depthState.getClass(), "maskEnabled", "field_179050_b"),
                field(depthState.getClass(), "depthFunc", "field_179051_c"),
                cullState, cullFace,
                field(cullState.getClass(), "mode", "field_179053_b"),
                lighting, normal, rescale,
                field(owner, "activeTextureUnit", "field_179162_o"),
                textures, texture2D, currentState);
        }

        private int readInteger(int pname) throws Exception {
            switch (pname) {
                case GL_BLEND: return bool(blend);
                case GL_BLEND_SRC: return blendSource.getInt(blendState);
                case GL_BLEND_DST: return blendDest.getInt(blendState);
                case GL_ALPHA_TEST: return bool(alphaTest);
                case GL_ALPHA_TEST_FUNC: return alphaFunc.getInt(alphaState);
                case GL_DEPTH_TEST: return bool(depthTest);
                case GL_DEPTH_FUNC: return depthFunc.getInt(depthState);
                case GL_CULL_FACE: return bool(cullFace);
                case GL_CULL_FACE_MODE: return cullMode.getInt(cullState);
                case GL_LIGHTING: return bool(lighting);
                case GL_DEPTH_WRITEMASK: return depthMask.getBoolean(depthState) ? 1 : 0;
                case GL_NORMALIZE: return bool(normal);
                case GL_RESCALE_NORMAL: return bool(rescaleNormal);
                case GL_TEXTURE_2D:
                    int active = activeTextureUnit.getInt(null);
                    if (active < 0 || active >= textureStates.length) {
                        throw new IllegalStateException("活动纹理单元越界：" + active);
                    }
                    return bool(texture2DState.get(textureStates[active]));
                default: throw new IllegalArgumentException("未缓存 GL 整数状态 " + pname);
            }
        }

        private float readFloat(int pname) throws Exception {
            if (pname != GL_ALPHA_TEST_REF) throw new IllegalArgumentException("未缓存 GL 浮点状态 " + pname);
            return alphaRef.getFloat(alphaState);
        }

        private int bool(Object state) throws IllegalAccessException {
            return booleanState.getBoolean(state) ? 1 : 0;
        }

        private static boolean supportsInteger(int pname) {
            switch (pname) {
                case GL_BLEND:
                case GL_BLEND_SRC:
                case GL_BLEND_DST:
                case GL_ALPHA_TEST:
                case GL_ALPHA_TEST_FUNC:
                case GL_DEPTH_TEST:
                case GL_DEPTH_FUNC:
                case GL_CULL_FACE:
                case GL_CULL_FACE_MODE:
                case GL_LIGHTING:
                case GL_DEPTH_WRITEMASK:
                case GL_NORMALIZE:
                case GL_RESCALE_NORMAL:
                case GL_TEXTURE_2D:
                    return true;
                default:
                    return false;
            }
        }

        private static Object staticValue(Class<?> owner, String... names) throws Exception {
            Object value = field(owner, names).get(null);
            if (value == null) throw new IllegalStateException(owner.getName() + " " + Arrays.toString(names) + " 为空");
            return value;
        }

        private static Object value(Object owner, String... names) throws Exception {
            Object result = field(owner.getClass(), names).get(owner);
            if (result == null) throw new IllegalStateException(owner.getClass().getName() + " " + Arrays.toString(names) + " 为空");
            return result;
        }

        private static Field field(Class<?> owner, String... names) throws NoSuchFieldException {
            for (String name : names) {
                try {
                    Field result = owner.getDeclaredField(name);
                    result.setAccessible(true);
                    return result;
                } catch (NoSuchFieldException ignored) {
                }
            }
            throw new NoSuchFieldException(owner.getName() + " " + Arrays.toString(names));
        }

        private static void requireCapability(Field capability, Object state, int expected) throws Exception {
            int actual = capability.getInt(state);
            if (actual != expected) {
                throw new IllegalStateException("BooleanState capability 期望 " + expected + "，实际 " + actual);
            }
        }
    }
}
