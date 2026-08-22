package dev.rlcraft.ice.optimizer.render.optifine;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.IntBuffer;

/** Reflection is resolved once and invokes only getters OptiFine itself uses. */
public final class OptifineProgramIntrospector {
    private static final ClassValue<ProgramAccess> PROGRAMS =
        new ClassValue<ProgramAccess>() {
            @Override protected ProgramAccess computeValue(Class<?> type) {
                return new ProgramAccess(type);
            }
        };
    private static final ClassValue<ShadersAccess> SHADERS =
        new ClassValue<ShadersAccess>() {
            @Override protected ShadersAccess computeValue(Class<?> type) {
                return new ShadersAccess(type);
            }
        };
    private static final ClassValue<AlphaAccess> ALPHAS =
        new ClassValue<AlphaAccess>() {
            @Override protected AlphaAccess computeValue(Class<?> type) {
                return new AlphaAccess(type);
            }
        };
    private static final ClassValue<BlendAccess> BLENDS =
        new ClassValue<BlendAccess>() {
            @Override protected BlendAccess computeValue(Class<?> type) {
                return new BlendAccess(type);
            }
        };
    private static final ClassValue<ScaleAccess> SCALES =
        new ClassValue<ScaleAccess>() {
            @Override protected ScaleAccess computeValue(Class<?> type) {
                return new ScaleAccess(type);
            }
        };

    public boolean willChange(Object requestedProgram) {
        if (requestedProgram == null) return true;
        ShadersAccess shaders = SHADERS.get(programBaseType(requestedProgram.getClass()));
        Object effective = shaders.effective(requestedProgram);
        return effective == null || effective != shaders.active();
    }

    /** Reads the reviewed Program getter without consulting activeProgram. */
    public int programId(Object program) {
        if (program == null) return 0;
        return PROGRAMS.get(program.getClass()).integer(program,
            PROGRAMS.get(program.getClass()).id);
    }

    public OptifineProgramState capture(Object requestedProgram) {
        if (requestedProgram == null) throw new IllegalArgumentException("program");
        ShadersAccess shaders = SHADERS.get(programBaseType(requestedProgram.getClass()));
        Object actual = shaders.active();
        if (actual == null) actual = requestedProgram;
        ProgramAccess access = PROGRAMS.get(actual.getClass());
        String name = bounded(access.string(actual, access.name), 256, "program name");
        if (name.isEmpty()) name = "<unnamed>";
        Object stageObject = access.object(actual, access.stage);
        String stage;
        if (stageObject instanceof Enum<?>) stage = ((Enum<?>) stageObject).name();
        else if (stageObject == null) stage = "";
        else stage = bounded(String.valueOf(access.object(stageObject,
            method(stageObject.getClass(), "getName", String.class))), 64,
            "program stage");
        int id = access.integer(actual, access.id);
        int compositeMipmap = access.integer(actual, access.compositeMipmap);
        int instances = access.integer(actual, access.instances);
        int[] drawBuffers = drawBuffers(access.object(actual, access.drawBuffers));
        Object alphaObject = access.object(actual, access.alpha);
        OptifineProgramState.AlphaState alpha = alphaObject == null ? null
            : ALPHAS.get(alphaObject.getClass()).capture(alphaObject);
        Object blendObject = access.object(actual, access.blend);
        OptifineProgramState.BlendState blend = blendObject == null ? null
            : BLENDS.get(blendObject.getClass()).capture(blendObject);
        Object scaleObject = access.object(actual, access.renderScale);
        OptifineProgramState.RenderScaleState scale = scaleObject == null ? null
            : SCALES.get(scaleObject.getClass()).capture(scaleObject);
        int framebuffer = shaders.framebuffer();
        return new OptifineProgramState(name, stage, id, framebuffer,
            shaders.framebufferState(framebuffer), drawBuffers,
            compositeMipmap, Math.max(0, instances), alpha, blend, scale);
    }

    private static Class<?> programBaseType(Class<?> type) {
        Class<?> current = type;
        while (current != null) {
            if ("net.optifine.shaders.Program".equals(current.getName())) {
                return current;
            }
            current = current.getSuperclass();
        }
        // The exact static field check below remains fail-closed for changed
        // or synthetic OptiFine implementations.
        return type;
    }

    static ShaderFramebufferState framebufferState(boolean deferred,
                                                    int width, int height,
                                                    int colorCount,
                                                    int depthCount,
                                                    int[] deferredFormats) {
        if (width <= 0 || height <= 0) return null;
        if (colorCount < 0 || colorCount > 16
            || depthCount < 0 || depthCount > 3) {
            throw new IllegalStateException("OptiFine FBO attachment count changed");
        }
        int[] colors = new int[colorCount];
        if (deferred) {
            if (deferredFormats == null || deferredFormats.length < colorCount) {
                throw new IllegalStateException("OptiFine gbuffer formats changed");
            }
            System.arraycopy(deferredFormats, 0, colors, 0, colorCount);
            for (int format : colors) {
                if (format <= 0) {
                    throw new IllegalStateException("invalid OptiFine gbuffer format");
                }
            }
        } else {
            // G5 allocates shadow color textures with the unsized RGBA format.
            java.util.Arrays.fill(colors, 6408);
        }
        // Both G5 DFB and SFB use GL_DEPTH_COMPONENT 2D textures and no
        // multisample attachment. Supersampling is represented by dimensions.
        return new ShaderFramebufferState(width, height, 0,
            depthCount == 0 ? 0 : 6402, colors);
    }

    private static int[] drawBuffers(Object value) {
        if (value == null) return new int[0];
        if (!(value instanceof IntBuffer)) {
            throw new IllegalStateException("OptiFine drawbuffers type changed");
        }
        IntBuffer source = ((IntBuffer) value).duplicate();
        if (source.remaining() > 16) {
            throw new IllegalStateException("OptiFine drawbuffer limit exceeded");
        }
        int[] result = new int[source.remaining()];
        source.get(result);
        return result;
    }

    private static String bounded(String value, int maximum, String detail) {
        if (value == null || value.length() > maximum || value.indexOf('\0') >= 0) {
            throw new IllegalStateException(detail + " changed");
        }
        return value;
    }

    private static Method method(Class<?> type, String name, Class<?> result) {
        try {
            Method method = type.getMethod(name);
            if (method.getReturnType() != result || method.getParameterTypes().length != 0) {
                throw new IllegalStateException("OptiFine getter changed " + name);
            }
            return method;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("missing OptiFine getter " + name, error);
        }
    }

    private static Field field(Class<?> type, String name, Class<?> fieldType) {
        try {
            Field field = type.getDeclaredField(name);
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != fieldType) {
                throw new IllegalStateException("OptiFine field changed " + name);
            }
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("missing OptiFine field " + name, error);
        }
    }

    private static final class ProgramAccess {
        private final Method name;
        private final Method stage;
        private final Method id;
        private final Method drawBuffers;
        private final Method compositeMipmap;
        private final Method instances;
        private final Method alpha;
        private final Method blend;
        private final Method renderScale;

        private ProgramAccess(Class<?> type) {
            name = method(type, "getName", String.class);
            stage = noArg(type, "getProgramStage");
            id = method(type, "getId", Integer.TYPE);
            drawBuffers = method(type, "getDrawBuffers", IntBuffer.class);
            compositeMipmap = method(type, "getCompositeMipmapSetting", Integer.TYPE);
            instances = method(type, "getCountInstances", Integer.TYPE);
            alpha = noArg(type, "getAlphaState");
            blend = noArg(type, "getBlendState");
            renderScale = noArg(type, "getRenderScale");
        }

        private String string(Object target, Method method) {
            return (String) object(target, method);
        }
        private int integer(Object target, Method method) {
            return ((Integer) object(target, method)).intValue();
        }
        private Object object(Object target, Method method) {
            try { return method.invoke(target); }
            catch (ReflectiveOperationException error) {
                throw new IllegalStateException("OptiFine getter failed", error);
            }
        }
    }

    private static final class ShadersAccess {
        private final Field active;
        private final Field shadowPass;
        private final Field shadowProgram;
        private final Field entitiesGlowing;
        private final Field glowingProgram;
        private final Field renderingDfb;
        private final Field dfb;
        private final Field sfb;
        private final Field renderWidth;
        private final Field renderHeight;
        private final Field shadowWidth;
        private final Field shadowHeight;
        private final Field usedColors;
        private final Field usedDepths;
        private final Field usedShadowColors;
        private final Field usedShadowDepths;
        private final Field gbufferFormats;

        private ShadersAccess(Class<?> programType) {
            try {
                Class<?> shaders = Class.forName("net.optifine.shaders.Shaders",
                    false, programType.getClassLoader());
                active = field(shaders, "activeProgram", programType);
                shadowPass = field(shaders, "isShadowPass", Boolean.TYPE);
                shadowProgram = field(shaders, "ProgramShadow", programType);
                entitiesGlowing = field(shaders, "isEntitiesGlowing", Boolean.TYPE);
                glowingProgram = field(shaders, "ProgramEntitiesGlowing", programType);
                renderingDfb = field(shaders, "isRenderingDfb", Boolean.TYPE);
                dfb = field(shaders, "dfb", Integer.TYPE);
                sfb = field(shaders, "sfb", Integer.TYPE);
                renderWidth = field(shaders, "renderWidth", Integer.TYPE);
                renderHeight = field(shaders, "renderHeight", Integer.TYPE);
                shadowWidth = field(shaders, "shadowMapWidth", Integer.TYPE);
                shadowHeight = field(shaders, "shadowMapHeight", Integer.TYPE);
                usedColors = field(shaders, "usedColorBuffers", Integer.TYPE);
                usedDepths = field(shaders, "usedDepthBuffers", Integer.TYPE);
                usedShadowColors = field(shaders, "usedShadowColorBuffers", Integer.TYPE);
                usedShadowDepths = field(shaders, "usedShadowDepthBuffers", Integer.TYPE);
                gbufferFormats = field(shaders, "gbuffersFormat", int[].class);
            } catch (ClassNotFoundException error) {
                throw new IllegalStateException("missing OptiFine Shaders", error);
            }
        }

        private Object active() { return get(active); }
        private Object effective(Object requested) {
            if (bool(shadowPass)) return get(shadowProgram);
            if (bool(entitiesGlowing)) return get(glowingProgram);
            return requested;
        }
        private int framebuffer() {
            return integer(bool(renderingDfb) ? dfb : sfb);
        }
        private ShaderFramebufferState framebufferState(int framebuffer) {
            if (framebuffer <= 0) return null;
            boolean deferred = bool(renderingDfb);
            return OptifineProgramIntrospector.framebufferState(deferred,
                integer(deferred ? renderWidth : shadowWidth),
                integer(deferred ? renderHeight : shadowHeight),
                integer(deferred ? usedColors : usedShadowColors),
                integer(deferred ? usedDepths : usedShadowDepths),
                deferred ? integers(gbufferFormats) : null);
        }
        private static Object get(Field field) {
            try { return field.get(null); }
            catch (IllegalAccessException error) { throw new IllegalStateException(error); }
        }
        private static boolean bool(Field field) {
            try { return field.getBoolean(null); }
            catch (IllegalAccessException error) { throw new IllegalStateException(error); }
        }
        private static int integer(Field field) {
            try { return field.getInt(null); }
            catch (IllegalAccessException error) { throw new IllegalStateException(error); }
        }
        private static int[] integers(Field field) {
            try {
                Object value = field.get(null);
                return value instanceof int[] ? (int[]) value : null;
            } catch (IllegalAccessException error) {
                throw new IllegalStateException(error);
            }
        }
    }

    private static final class AlphaAccess {
        private final Method enabled;
        private final Method function;
        private final Method reference;
        private AlphaAccess(Class<?> type) {
            enabled = method(type, "isEnabled", Boolean.TYPE);
            function = method(type, "getFunc", Integer.TYPE);
            reference = method(type, "getRef", Float.TYPE);
        }
        private OptifineProgramState.AlphaState capture(Object value) {
            return new OptifineProgramState.AlphaState(bool(value, enabled),
                integer(value, function), floating(value, reference));
        }
    }

    private static final class BlendAccess {
        private final Method enabled;
        private final Method source;
        private final Method destination;
        private final Method sourceAlpha;
        private final Method destinationAlpha;
        private BlendAccess(Class<?> type) {
            enabled = method(type, "isEnabled", Boolean.TYPE);
            source = method(type, "getSrcFactor", Integer.TYPE);
            destination = method(type, "getDstFactor", Integer.TYPE);
            sourceAlpha = method(type, "getSrcFactorAlpha", Integer.TYPE);
            destinationAlpha = method(type, "getDstFactorAlpha", Integer.TYPE);
        }
        private OptifineProgramState.BlendState capture(Object value) {
            return new OptifineProgramState.BlendState(bool(value, enabled),
                integer(value, source), integer(value, destination),
                integer(value, sourceAlpha), integer(value, destinationAlpha));
        }
    }

    private static final class ScaleAccess {
        private final Method scale;
        private final Method offsetX;
        private final Method offsetY;
        private ScaleAccess(Class<?> type) {
            scale = method(type, "getScale", Float.TYPE);
            offsetX = method(type, "getOffsetX", Float.TYPE);
            offsetY = method(type, "getOffsetY", Float.TYPE);
        }
        private OptifineProgramState.RenderScaleState capture(Object value) {
            return new OptifineProgramState.RenderScaleState(floating(value, scale),
                floating(value, offsetX), floating(value, offsetY));
        }
    }

    private static Method noArg(Class<?> type, String name) {
        try {
            Method method = type.getMethod(name);
            if (method.getParameterTypes().length != 0) {
                throw new IllegalStateException("OptiFine getter changed " + name);
            }
            return method;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("missing OptiFine getter " + name, error);
        }
    }

    private static Object invoke(Object target, Method method) {
        try { return method.invoke(target); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private static boolean bool(Object target, Method method) {
        return ((Boolean) invoke(target, method)).booleanValue();
    }
    private static int integer(Object target, Method method) {
        return ((Integer) invoke(target, method)).intValue();
    }
    private static float floating(Object target, Method method) {
        return ((Float) invoke(target, method)).floatValue();
    }
}
