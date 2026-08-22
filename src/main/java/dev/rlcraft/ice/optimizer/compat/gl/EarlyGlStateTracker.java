package dev.rlcraft.ice.optimizer.compat.gl;

/**
 * Core-JAR resident mirror for the small set of bindings needed by GPU depth
 * reduction. Hooks publish only after the corresponding GL wrapper returns.
 */
public final class EarlyGlStateTracker {
    private static final int PROGRAM = 1;
    private static final int READ_FRAMEBUFFER = 1 << 1;
    private static final int DEPTH_FUNCTION = 1 << 2;
    private static final int PIXEL_PACK_BUFFER = 1 << 3;
    private static final int DRAW_FRAMEBUFFER = 1 << 4;
    private static final int ARRAY_BUFFER = 1 << 5;
    private static final int ACTIVE_TEXTURE = 1 << 6;
    private static final int TEXTURE_0 = 1 << 7;
    private static final int TEXTURE_1 = 1 << 8;
    private static final int BLEND_ENABLED = 1 << 9;
    private static final int BLEND_FUNCTION = 1 << 10;
    private static final int DEPTH_ENABLED = 1 << 11;
    private static final int DEPTH_MASK = 1 << 12;
    private static final int CULL_ENABLED = 1 << 13;
    private static final int COLOR_MASK = 1 << 14;
    private static final int CURRENT_COLOR = 1 << 15;
    private static final int CLIENT_ACTIVE_TEXTURE = 1 << 16;
    private static final int TEXTURE_0_ENABLED = 1 << 17;
    private static final int TEXTURE_1_ENABLED = 1 << 18;
    private static final int VIEWPORT = 1 << 19;
    private static final int PIXEL_UNPACK_BUFFER = 1 << 20;
    private static final int DRAW_INDIRECT_BUFFER = 1 << 21;
    private static final int LIGHTING_ENABLED = 1 << 22;
    private static final int REQUIRED = PROGRAM | READ_FRAMEBUFFER
        | DRAW_FRAMEBUFFER | DEPTH_FUNCTION | PIXEL_PACK_BUFFER;
    private static final int DRAW_REQUIRED = PROGRAM | ARRAY_BUFFER
        | ACTIVE_TEXTURE | TEXTURE_0 | TEXTURE_1 | BLEND_ENABLED
        | BLEND_FUNCTION | DEPTH_ENABLED | DEPTH_FUNCTION | DEPTH_MASK
        | CULL_ENABLED | COLOR_MASK | CURRENT_COLOR;
    private static final int COMPLETE_DRAW_REQUIRED = DRAW_REQUIRED
        | CLIENT_ACTIVE_TEXTURE;
    private static final int PARTICLE_REQUIRED = COMPLETE_DRAW_REQUIRED
        | TEXTURE_0_ENABLED | TEXTURE_1_ENABLED | LIGHTING_ENABLED;
    private static final int HUD_REQUIRED = PROGRAM | DRAW_FRAMEBUFFER
        | ARRAY_BUFFER | ACTIVE_TEXTURE | TEXTURE_0 | TEXTURE_0_ENABLED
        | BLEND_ENABLED | BLEND_FUNCTION | DEPTH_ENABLED | DEPTH_FUNCTION
        | DEPTH_MASK | CULL_ENABLED | COLOR_MASK | CURRENT_COLOR
        | CLIENT_ACTIVE_TEXTURE | VIEWPORT;
    private static final ThreadLocal<State> STATES = new ThreadLocal<State>() {
        @Override protected State initialValue() { return new State(); }
    };

    private EarlyGlStateTracker() {
    }

    public static void beginProbe() {
        State state = STATES.get();
        state.knownMask = 0;
        state.compatibilityKnown = false;
        state.depthRangeKnown = false;
        state.mutationSerial++;
    }

    public static void useProgram(int value) {
        if (value < 0) { invalidate(); return; }
        State state = STATES.get();
        state.program = value;
        state.knownMask |= PROGRAM;
        state.mutationSerial++;
    }

    public static void bindFramebuffer(int target, int value) {
        if (value < 0) { invalidate(); return; }
        // GL_FRAMEBUFFER updates both bindings; GL_READ_FRAMEBUFFER updates
        // the source used by glCopyTexSubImage2D/glReadPixels.
        State state = STATES.get();
        if (target == 36160 || target == 36008) {
            state.readFramebuffer = value;
            state.knownMask |= READ_FRAMEBUFFER;
        }
        if (target == 36160 || target == 36009) {
            state.drawFramebuffer = value;
            state.knownMask |= DRAW_FRAMEBUFFER;
        }
        state.mutationSerial++;
    }

    public static void depthFunction(int value) {
        State state = STATES.get();
        state.depthFunction = value;
        state.knownMask |= DEPTH_FUNCTION;
        state.mutationSerial++;
    }

    /** Publishes the independently queried compatibility depth mapping. */
    public static void seedDepthRange(double near, double far) {
        State state = STATES.get();
        state.depthRangeKnown = Double.isFinite(near) && Double.isFinite(far)
            && near >= 0.0D && near <= 1.0D
            && far >= 0.0D && far <= 1.0D;
        if (state.depthRangeKnown) {
            state.depthRangeNear = near;
            state.depthRangeFar = far;
        }
        state.mutationSerial++;
    }

    public static void bindBuffer(int target, int value) {
        if (value < 0) { invalidate(); return; }
        if (target == 35051) {
            State state = STATES.get();
            state.pixelPackBuffer = value;
            state.knownMask |= PIXEL_PACK_BUFFER;
        }
        if (target == 34962) {
            State state = STATES.get();
            state.arrayBuffer = value;
            state.knownMask |= ARRAY_BUFFER;
            state.mutationSerial++;
        } else if (target == 34963) {
            State state = STATES.get();
            state.elementBuffer = value;
            state.mutationSerial++;
        } else if (target == 35052) {
            State state = STATES.get();
            state.pixelUnpackBuffer = value;
            state.knownMask |= PIXEL_UNPACK_BUFFER;
            state.mutationSerial++;
        } else if (target == 36671) {
            State state = STATES.get();
            state.drawIndirectBuffer = value;
            state.knownMask |= DRAW_INDIRECT_BUFFER;
            state.mutationSerial++;
        } else if (target == 35051) {
            STATES.get().mutationSerial++;
        }
    }

    public static void bindVertexArray(int value) {
        if (value < 0) { invalidate(); return; }
        State state = STATES.get();
        state.vertexArray = value;
        state.mutationSerial++;
    }

    public static void activeTexture(int textureEnum) {
        int index = textureEnum - 33984;
        if (index < 0 || index >= 32) { invalidate(); return; }
        State state = STATES.get();
        state.activeTexture = index;
        state.knownMask |= ACTIVE_TEXTURE;
        state.mutationSerial++;
    }

    public static void clientActiveTexture(int textureEnum) {
        int index = textureEnum - 33984;
        if (index < 0 || index >= 32) { invalidate(); return; }
        State state = STATES.get();
        state.clientActiveTexture = index;
        state.knownMask |= CLIENT_ACTIVE_TEXTURE;
        state.mutationSerial++;
    }

    public static void bindTexture(int value) {
        if (value < 0) { invalidate(); return; }
        State state = STATES.get();
        if ((state.knownMask & ACTIVE_TEXTURE) == 0) return;
        state.textures2d[state.activeTexture] = value;
        if (state.activeTexture == 0) {
            state.texture0 = value;
            state.knownMask |= TEXTURE_0;
        } else if (state.activeTexture == 1) {
            state.texture1 = value;
            state.knownMask |= TEXTURE_1;
        }
        state.mutationSerial++;
    }

    public static void texture2dEnabled(boolean enabled) {
        State state = STATES.get();
        if ((state.knownMask & ACTIVE_TEXTURE) == 0) return;
        state.texture2dEnabled[state.activeTexture] = enabled;
        if (state.activeTexture == 0) {
            state.texture0Enabled = enabled;
            state.knownMask |= TEXTURE_0_ENABLED;
        } else if (state.activeTexture == 1) {
            state.texture1Enabled = enabled;
            state.knownMask |= TEXTURE_1_ENABLED;
        }
        state.mutationSerial++;
    }

    public static void blendEnabled(boolean enabled) {
        State state = STATES.get();
        state.blend = enabled;
        state.knownMask |= BLEND_ENABLED;
        state.mutationSerial++;
    }

    public static void blendFunction(int source, int destination) {
        blendFunction(source, destination, source, destination);
    }

    public static void blendFunction(int sourceRgb, int destinationRgb,
                                     int sourceAlpha, int destinationAlpha) {
        State state = STATES.get();
        state.blendSourceRgb = sourceRgb;
        state.blendDestinationRgb = destinationRgb;
        state.blendSourceAlpha = sourceAlpha;
        state.blendDestinationAlpha = destinationAlpha;
        state.knownMask |= BLEND_FUNCTION;
        state.mutationSerial++;
    }

    public static void blendEquation(int equation) {
        blendEquationSeparate(equation, equation);
    }

    public static void blendEquationSeparate(int rgb, int alpha) {
        State state = STATES.get();
        state.blendEquationRgb = rgb;
        state.blendEquationAlpha = alpha;
        state.mutationSerial++;
    }

    public static void depthEnabled(boolean enabled) {
        State state = STATES.get();
        state.depthTest = enabled;
        state.knownMask |= DEPTH_ENABLED;
        state.mutationSerial++;
    }

    public static void depthMask(boolean enabled) {
        State state = STATES.get();
        state.depthMask = enabled;
        state.knownMask |= DEPTH_MASK;
        state.mutationSerial++;
    }

    public static void cullEnabled(boolean enabled) {
        State state = STATES.get();
        state.cull = enabled;
        state.knownMask |= CULL_ENABLED;
        state.mutationSerial++;
    }

    public static void lightingEnabled(boolean enabled) {
        State state = STATES.get();
        state.lighting = enabled;
        state.knownMask |= LIGHTING_ENABLED;
        state.mutationSerial++;
    }

    public static void cullFace(int face) {
        State state = STATES.get();
        state.cullFace = face;
        state.mutationSerial++;
    }

    public static void scissorEnabled(boolean enabled) {
        State state = STATES.get();
        state.scissor = enabled;
        state.mutationSerial++;
    }

    public static void stencilEnabled(boolean enabled) {
        State state = STATES.get();
        state.stencil = enabled;
        state.mutationSerial++;
    }

    public static void scissorBox(int x, int y, int width, int height) {
        if (width < 0 || height < 0) { invalidate(); return; }
        State state = STATES.get();
        state.scissorX = x;
        state.scissorY = y;
        state.scissorWidth = width;
        state.scissorHeight = height;
        state.mutationSerial++;
    }

    public static void colorMask(boolean red, boolean green, boolean blue,
                                 boolean alpha) {
        State state = STATES.get();
        state.colorMask = (red ? 1 : 0) | (green ? 2 : 0)
            | (blue ? 4 : 0) | (alpha ? 8 : 0);
        state.knownMask |= COLOR_MASK;
        state.mutationSerial++;
    }

    public static void viewport(int x, int y, int width, int height) {
        if (width < 0 || height < 0) { invalidate(); return; }
        State state = STATES.get();
        state.viewportX = x;
        state.viewportY = y;
        state.viewportWidth = width;
        state.viewportHeight = height;
        state.knownMask |= VIEWPORT;
        state.mutationSerial++;
    }

    public static void color(float red, float green, float blue, float alpha) {
        State state = STATES.get();
        state.red = red;
        state.green = green;
        state.blue = blue;
        state.alpha = alpha;
        state.knownMask |= CURRENT_COLOR;
        state.mutationSerial++;
    }

    public static void color(float red, float green, float blue) {
        color(red, green, blue, 1.0F);
    }

    public static void resetColor() { color(1.0F, 1.0F, 1.0F, 1.0F); }

    public static void seedDrawState(int activeTexture, int texture0, int texture1,
                                     boolean blend, int sourceRgb,
                                     int destinationRgb, int sourceAlpha,
                                     int destinationAlpha, boolean depthTest,
                                     boolean depthMask, boolean cull,
                                     int colorMask, float red, float green,
                                     float blue, float alpha) {
        State state = STATES.get();
        state.activeTexture = activeTexture;
        state.texture0 = texture0;
        state.texture1 = texture1;
        state.textures2d[0] = texture0;
        state.textures2d[1] = texture1;
        state.blend = blend;
        state.blendSourceRgb = sourceRgb;
        state.blendDestinationRgb = destinationRgb;
        state.blendSourceAlpha = sourceAlpha;
        state.blendDestinationAlpha = destinationAlpha;
        state.depthTest = depthTest;
        state.depthMask = depthMask;
        state.cull = cull;
        state.colorMask = colorMask & 15;
        state.red = red;
        state.green = green;
        state.blue = blue;
        state.alpha = alpha;
        state.knownMask |= ACTIVE_TEXTURE | TEXTURE_0 | TEXTURE_1
            | BLEND_ENABLED | BLEND_FUNCTION | DEPTH_ENABLED | DEPTH_MASK
            | CULL_ENABLED | COLOR_MASK | CURRENT_COLOR;
        state.mutationSerial++;
    }

    public static void seedHudState(boolean texture0Enabled,
                                    boolean texture1Enabled, int viewportX,
                                    int viewportY, int viewportWidth,
                                    int viewportHeight) {
        if (viewportWidth < 0 || viewportHeight < 0) { invalidate(); return; }
        State state = STATES.get();
        state.texture0Enabled = texture0Enabled;
        state.texture1Enabled = texture1Enabled;
        state.texture2dEnabled[0] = texture0Enabled;
        state.texture2dEnabled[1] = texture1Enabled;
        state.viewportX = viewportX;
        state.viewportY = viewportY;
        state.viewportWidth = viewportWidth;
        state.viewportHeight = viewportHeight;
        state.knownMask |= TEXTURE_0_ENABLED | TEXTURE_1_ENABLED | VIEWPORT;
        state.mutationSerial++;
    }

    /** Seeds state that has no complete GlStateManager wrapper coverage. */
    public static void seedCompatibilityState(int vertexArray,
                                              boolean vertexArraySupported,
                                              int elementBuffer,
                                              int pixelUnpackBuffer,
                                              int textureUnitCount,
                                              int[] textures2d,
                                              boolean[] texture2dEnabled,
                                              int blendEquationRgb,
                                              int blendEquationAlpha,
                                              int cullFace, boolean scissor,
                                              boolean stencil,
                                              int scissorX, int scissorY,
                                              int scissorWidth,
                                              int scissorHeight) {
        if (vertexArray < 0 || elementBuffer < 0 || pixelUnpackBuffer < 0
            || textures2d == null || texture2dEnabled == null
            || textures2d.length != 32 || texture2dEnabled.length != 32
            || textureUnitCount < 1 || textureUnitCount > 32
            || scissorWidth < 0 || scissorHeight < 0) {
            invalidate();
            return;
        }
        State state = STATES.get();
        for (int texture : textures2d) {
            if (texture < 0) { invalidate(); return; }
        }
        state.vertexArray = vertexArray;
        state.vertexArraySupported = vertexArraySupported;
        state.elementBuffer = elementBuffer;
        state.pixelUnpackBuffer = pixelUnpackBuffer;
        state.knownMask |= PIXEL_UNPACK_BUFFER;
        state.textureUnitCount = textureUnitCount;
        System.arraycopy(textures2d, 0, state.textures2d, 0, 32);
        System.arraycopy(texture2dEnabled, 0, state.texture2dEnabled, 0, 32);
        state.texture0 = textures2d[0];
        state.texture1 = textures2d[1];
        state.texture0Enabled = texture2dEnabled[0];
        state.texture1Enabled = texture2dEnabled[1];
        state.blendEquationRgb = blendEquationRgb;
        state.blendEquationAlpha = blendEquationAlpha;
        state.cullFace = cullFace;
        state.scissor = scissor;
        state.stencil = stencil;
        state.scissorX = scissorX;
        state.scissorY = scissorY;
        state.scissorWidth = scissorWidth;
        state.scissorHeight = scissorHeight;
        state.compatibilityKnown = true;
        state.mutationSerial++;
    }

    public static Snapshot snapshot() {
        State state = STATES.get();
        if ((state.knownMask & REQUIRED) != REQUIRED) return null;
        return new Snapshot(state.program, state.readFramebuffer,
            state.drawFramebuffer, state.depthFunction, state.pixelPackBuffer,
            state.arrayBuffer, (state.knownMask & ARRAY_BUFFER) != 0,
            (state.knownMask & COMPLETE_DRAW_REQUIRED) == COMPLETE_DRAW_REQUIRED,
            state.activeTexture, state.texture0, state.texture1,
            state.clientActiveTexture,
            state.texture0Enabled, state.texture1Enabled,
            state.blend, state.blendSourceRgb, state.blendDestinationRgb,
            state.blendSourceAlpha, state.blendDestinationAlpha,
            state.depthTest, state.depthMask, state.cull, state.lighting,
            state.colorMask,
            state.red, state.green, state.blue, state.alpha,
            state.viewportX, state.viewportY, state.viewportWidth,
            state.viewportHeight,
            (state.knownMask & HUD_REQUIRED) == HUD_REQUIRED,
            (state.knownMask & PARTICLE_REQUIRED) == PARTICLE_REQUIRED,
            state.invalidations);
    }

    public static void invalidate() {
        State state = STATES.get();
        state.knownMask = 0;
        state.compatibilityKnown = false;
        state.depthRangeKnown = false;
        state.invalidations++;
        state.mutationSerial++;
    }

    public static boolean isKnown() {
        return (STATES.get().knownMask & REQUIRED) == REQUIRED;
    }
    /** Non-allocating readiness check for the fixed-function model VBO path. */
    public static boolean hasModelDrawState() {
        State state = STATES.get();
        return (state.knownMask & (COMPLETE_DRAW_REQUIRED | ARRAY_BUFFER))
            == (COMPLETE_DRAW_REQUIRED | ARRAY_BUFFER);
    }
    /**
     * Returns the restorable array-buffer binding only when an immediate
     * fixed-function model draw is safe, otherwise {@link Integer#MIN_VALUE}.
     */
    public static int fixedFunctionModelArrayBufferBinding() {
        State state = STATES.get();
        return (state.knownMask & (COMPLETE_DRAW_REQUIRED | ARRAY_BUFFER))
                == (COMPLETE_DRAW_REQUIRED | ARRAY_BUFFER)
            && state.program == 0 && state.clientActiveTexture == 0
                ? state.arrayBuffer : Integer.MIN_VALUE;
    }
    /** Non-allocating change token used to cache a Snapshot within one run. */
    public static long drawStateSerial() { return STATES.get().mutationSerial; }
    /** Returns the tracked binding for the active unit, or {@link Integer#MIN_VALUE}. */
    public static int boundTextureForActiveUnit() {
        State state = STATES.get();
        if ((state.knownMask & ACTIVE_TEXTURE) == 0) return Integer.MIN_VALUE;
        return boundTextureForUnit(state.activeTexture);
    }
    /** Returns a known 2D binding without changing the active texture unit. */
    public static int boundTextureForUnit(int unit) {
        State state = STATES.get();
        if (unit == 0) {
            return (state.knownMask & TEXTURE_0) != 0
                ? state.texture0 : Integer.MIN_VALUE;
        }
        if (unit == 1) {
            return (state.knownMask & TEXTURE_1) != 0
                ? state.texture1 : Integer.MIN_VALUE;
        }
        if (unit < 0 || unit >= state.textureUnitCount
            || !state.compatibilityKnown) return Integer.MIN_VALUE;
        return state.textures2d[unit];
    }
    /** Returns the tracked zero-based active texture unit, or MIN_VALUE. */
    public static int activeTextureUnit() {
        State state = STATES.get();
        return (state.knownMask & ACTIVE_TEXTURE) != 0
            ? state.activeTexture : Integer.MIN_VALUE;
    }
    /** Returns the tracked unpack PBO binding, or {@link Integer#MIN_VALUE}. */
    public static int pixelUnpackBufferBinding() {
        State state = STATES.get();
        return (state.knownMask & PIXEL_UNPACK_BUFFER) != 0
            ? state.pixelUnpackBuffer : Integer.MIN_VALUE;
    }
    /** Returns the tracked vertex-buffer binding, or {@link Integer#MIN_VALUE}. */
    public static int arrayBufferBinding() {
        State state = STATES.get();
        return (state.knownMask & ARRAY_BUFFER) != 0
            ? state.arrayBuffer : Integer.MIN_VALUE;
    }
    /** Returns the tracked indirect-command binding, or {@link Integer#MIN_VALUE}. */
    public static int drawIndirectBufferBinding() {
        State state = STATES.get();
        return (state.knownMask & DRAW_INDIRECT_BUFFER) != 0
            ? state.drawIndirectBuffer : Integer.MIN_VALUE;
    }
    public static void seedDrawIndirectBuffer(int nativeId) {
        bindBuffer(36671, nativeId);
    }
    public static long invalidations() { return STATES.get().invalidations; }

    /** HZB projection is valid only for the exact default OpenGL depth map. */
    public static boolean hasStandardDepthRange() {
        State state = STATES.get();
        return state.depthRangeKnown && state.depthRangeNear == 0.0D
            && state.depthRangeFar == 1.0D;
    }
    public static boolean hasKnownDepthRange() {
        return STATES.get().depthRangeKnown;
    }

    public static CompatibilitySnapshot compatibilitySnapshot() {
        State state = STATES.get();
        if (!state.compatibilityKnown
            || (state.knownMask & COMPLETE_DRAW_REQUIRED)
                != COMPLETE_DRAW_REQUIRED
            || (state.knownMask & DRAW_FRAMEBUFFER) == 0
            || (state.knownMask & READ_FRAMEBUFFER) == 0
            || (state.knownMask & VIEWPORT) == 0) return null;
        return new CompatibilitySnapshot(state);
    }

    private static final class State {
        private int knownMask;
        private int program;
        private int readFramebuffer;
        private int drawFramebuffer;
        private int depthFunction;
        private double depthRangeNear;
        private double depthRangeFar = 1.0D;
        private boolean depthRangeKnown;
        private int pixelPackBuffer;
        private int pixelUnpackBuffer;
        private int drawIndirectBuffer;
        private int arrayBuffer;
        private int elementBuffer;
        private int vertexArray;
        private boolean vertexArraySupported;
        private int activeTexture;
        private int texture0;
        private int texture1;
        private int clientActiveTexture;
        private boolean texture0Enabled;
        private boolean texture1Enabled;
        private final int[] textures2d = new int[32];
        private final boolean[] texture2dEnabled = new boolean[32];
        private int textureUnitCount = 2;
        private boolean blend;
        private int blendSourceRgb = 1;
        private int blendDestinationRgb;
        private int blendSourceAlpha = 1;
        private int blendDestinationAlpha;
        private int blendEquationRgb = 32774;
        private int blendEquationAlpha = 32774;
        private boolean depthTest;
        private boolean depthMask = true;
        private boolean cull;
        private boolean lighting;
        private int cullFace = 1029;
        private boolean scissor;
        private boolean stencil;
        private int scissorX;
        private int scissorY;
        private int scissorWidth;
        private int scissorHeight;
        private int colorMask = 15;
        private float red = 1.0F;
        private float green = 1.0F;
        private float blue = 1.0F;
        private float alpha = 1.0F;
        private int viewportX;
        private int viewportY;
        private int viewportWidth;
        private int viewportHeight;
        private long invalidations;
        private long mutationSerial;
        private boolean compatibilityKnown;
    }

    /** Immutable, allocation-bounded call-site state for a Legacy GL island. */
    public static final class CompatibilitySnapshot {
        private final int program;
        private final int readFramebuffer;
        private final int drawFramebuffer;
        private final int vertexArray;
        private final boolean vertexArraySupported;
        private final int arrayBuffer;
        private final int elementBuffer;
        private final int pixelPackBuffer;
        private final int pixelUnpackBuffer;
        private final int drawIndirectBuffer;
        private final int activeTexture;
        private final int clientActiveTexture;
        private final int[] textures2d;
        private final boolean[] texture2dEnabled;
        private final int textureUnitCount;
        private final boolean blend;
        private final int blendSourceRgb;
        private final int blendDestinationRgb;
        private final int blendSourceAlpha;
        private final int blendDestinationAlpha;
        private final int blendEquationRgb;
        private final int blendEquationAlpha;
        private final boolean depthTest;
        private final int depthFunction;
        private final boolean depthMask;
        private final boolean cull;
        private final int cullFace;
        private final boolean scissor;
        private final boolean stencil;
        private final int viewportX;
        private final int viewportY;
        private final int viewportWidth;
        private final int viewportHeight;
        private final int scissorX;
        private final int scissorY;
        private final int scissorWidth;
        private final int scissorHeight;
        private final int colorMask;
        private final float red;
        private final float green;
        private final float blue;
        private final float alpha;

        private CompatibilitySnapshot(State state) {
            program = state.program;
            readFramebuffer = state.readFramebuffer;
            drawFramebuffer = state.drawFramebuffer;
            vertexArray = state.vertexArray;
            vertexArraySupported = state.vertexArraySupported;
            arrayBuffer = state.arrayBuffer;
            elementBuffer = state.elementBuffer;
            pixelPackBuffer = state.pixelPackBuffer;
            pixelUnpackBuffer = state.pixelUnpackBuffer;
            drawIndirectBuffer = state.drawIndirectBuffer;
            activeTexture = state.activeTexture;
            clientActiveTexture = state.clientActiveTexture;
            textures2d = state.textures2d.clone();
            texture2dEnabled = state.texture2dEnabled.clone();
            textureUnitCount = state.textureUnitCount;
            blend = state.blend;
            blendSourceRgb = state.blendSourceRgb;
            blendDestinationRgb = state.blendDestinationRgb;
            blendSourceAlpha = state.blendSourceAlpha;
            blendDestinationAlpha = state.blendDestinationAlpha;
            blendEquationRgb = state.blendEquationRgb;
            blendEquationAlpha = state.blendEquationAlpha;
            depthTest = state.depthTest;
            depthFunction = state.depthFunction;
            depthMask = state.depthMask;
            cull = state.cull;
            cullFace = state.cullFace;
            scissor = state.scissor;
            stencil = state.stencil;
            viewportX = state.viewportX;
            viewportY = state.viewportY;
            viewportWidth = state.viewportWidth;
            viewportHeight = state.viewportHeight;
            scissorX = state.scissorX;
            scissorY = state.scissorY;
            scissorWidth = state.scissorWidth;
            scissorHeight = state.scissorHeight;
            colorMask = state.colorMask;
            red = state.red;
            green = state.green;
            blue = state.blue;
            alpha = state.alpha;
        }

        public int getProgram() { return program; }
        public int getReadFramebuffer() { return readFramebuffer; }
        public int getDrawFramebuffer() { return drawFramebuffer; }
        public boolean isVertexArraySupported() { return vertexArraySupported; }
        public int getVertexArray() { return vertexArray; }
        public int getArrayBuffer() { return arrayBuffer; }
        public int getElementBuffer() { return elementBuffer; }
        public int getPixelPackBuffer() { return pixelPackBuffer; }
        public int getPixelUnpackBuffer() { return pixelUnpackBuffer; }
        public int getDrawIndirectBuffer() { return drawIndirectBuffer; }
        public int getActiveTexture() { return activeTexture; }
        public int getClientActiveTexture() { return clientActiveTexture; }
        public int getTextureUnitCount() { return textureUnitCount; }
        public int getTexture2d(int unit) { return textures2d[unit]; }
        public boolean isTexture2dEnabled(int unit) {
            return texture2dEnabled[unit];
        }
        public boolean isBlend() { return blend; }
        public int getBlendSourceRgb() { return blendSourceRgb; }
        public int getBlendDestinationRgb() { return blendDestinationRgb; }
        public int getBlendSourceAlpha() { return blendSourceAlpha; }
        public int getBlendDestinationAlpha() { return blendDestinationAlpha; }
        public int getBlendEquationRgb() { return blendEquationRgb; }
        public int getBlendEquationAlpha() { return blendEquationAlpha; }
        public boolean isDepthTest() { return depthTest; }
        public int getDepthFunction() { return depthFunction; }
        public boolean isDepthMask() { return depthMask; }
        public boolean isCull() { return cull; }
        public int getCullFace() { return cullFace; }
        public boolean isScissor() { return scissor; }
        public boolean isStencil() { return stencil; }
        public int getViewportX() { return viewportX; }
        public int getViewportY() { return viewportY; }
        public int getViewportWidth() { return viewportWidth; }
        public int getViewportHeight() { return viewportHeight; }
        public int getScissorX() { return scissorX; }
        public int getScissorY() { return scissorY; }
        public int getScissorWidth() { return scissorWidth; }
        public int getScissorHeight() { return scissorHeight; }
        public int getColorMask() { return colorMask; }
        public float getRed() { return red; }
        public float getGreen() { return green; }
        public float getBlue() { return blue; }
        public float getAlpha() { return alpha; }
    }

    public static final class Snapshot {
        private final int program;
        private final int readFramebuffer;
        private final int drawFramebuffer;
        private final int depthFunction;
        private final int pixelPackBuffer;
        private final int arrayBuffer;
        private final boolean arrayBufferKnown;
        private final boolean drawStateKnown;
        private final int activeTexture;
        private final int texture0;
        private final int texture1;
        private final int clientActiveTexture;
        private final boolean texture0Enabled;
        private final boolean texture1Enabled;
        private final boolean blend;
        private final int blendSourceRgb;
        private final int blendDestinationRgb;
        private final int blendSourceAlpha;
        private final int blendDestinationAlpha;
        private final boolean depthTest;
        private final boolean depthMask;
        private final boolean cull;
        private final boolean lighting;
        private final int colorMask;
        private final float red;
        private final float green;
        private final float blue;
        private final float alpha;
        private final int viewportX;
        private final int viewportY;
        private final int viewportWidth;
        private final int viewportHeight;
        private final boolean hudStateKnown;
        private final boolean particleStateKnown;
        private final long invalidationSerial;

        private Snapshot(int program, int readFramebuffer, int drawFramebuffer,
                         int depthFunction, int pixelPackBuffer, int arrayBuffer,
                         boolean arrayBufferKnown, boolean drawStateKnown,
                         int activeTexture, int texture0, int texture1,
                         int clientActiveTexture,
                         boolean texture0Enabled, boolean texture1Enabled,
                         boolean blend, int blendSourceRgb,
                         int blendDestinationRgb, int blendSourceAlpha,
                         int blendDestinationAlpha, boolean depthTest,
                         boolean depthMask, boolean cull, boolean lighting,
                         int colorMask,
                         float red, float green, float blue, float alpha,
                         int viewportX, int viewportY, int viewportWidth,
                         int viewportHeight, boolean hudStateKnown,
                         boolean particleStateKnown,
                         long invalidationSerial) {
            this.program = program;
            this.readFramebuffer = readFramebuffer;
            this.drawFramebuffer = drawFramebuffer;
            this.depthFunction = depthFunction;
            this.pixelPackBuffer = pixelPackBuffer;
            this.arrayBuffer = arrayBuffer;
            this.arrayBufferKnown = arrayBufferKnown;
            this.drawStateKnown = drawStateKnown;
            this.activeTexture = activeTexture;
            this.texture0 = texture0;
            this.texture1 = texture1;
            this.clientActiveTexture = clientActiveTexture;
            this.texture0Enabled = texture0Enabled;
            this.texture1Enabled = texture1Enabled;
            this.blend = blend;
            this.blendSourceRgb = blendSourceRgb;
            this.blendDestinationRgb = blendDestinationRgb;
            this.blendSourceAlpha = blendSourceAlpha;
            this.blendDestinationAlpha = blendDestinationAlpha;
            this.depthTest = depthTest;
            this.depthMask = depthMask;
            this.cull = cull;
            this.lighting = lighting;
            this.colorMask = colorMask;
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.alpha = alpha;
            this.viewportX = viewportX;
            this.viewportY = viewportY;
            this.viewportWidth = viewportWidth;
            this.viewportHeight = viewportHeight;
            this.hudStateKnown = hudStateKnown;
            this.particleStateKnown = particleStateKnown;
            this.invalidationSerial = invalidationSerial;
        }

        public int getProgram() { return program; }
        public int getReadFramebuffer() { return readFramebuffer; }
        public int getDrawFramebuffer() { return drawFramebuffer; }
        public int getDepthFunction() { return depthFunction; }
        public int getPixelPackBuffer() { return pixelPackBuffer; }
        public boolean hasArrayBufferBinding() { return arrayBufferKnown; }
        public int getArrayBuffer() {
            if (!arrayBufferKnown) throw new IllegalStateException("array buffer unknown");
            return arrayBuffer;
        }
        public boolean hasDrawState() { return drawStateKnown; }
        public boolean hasParticleState() {
            return particleStateKnown;
        }
        public int getActiveTexture() { return activeTexture; }
        public int getTexture0() { return texture0; }
        public int getTexture1() { return texture1; }
        public int getClientActiveTexture() { return clientActiveTexture; }
        public boolean isTexture0Enabled() { return texture0Enabled; }
        public boolean isTexture1Enabled() { return texture1Enabled; }
        public boolean isBlend() { return blend; }
        public int getBlendSourceRgb() { return blendSourceRgb; }
        public int getBlendDestinationRgb() { return blendDestinationRgb; }
        public int getBlendSourceAlpha() { return blendSourceAlpha; }
        public int getBlendDestinationAlpha() { return blendDestinationAlpha; }
        public boolean isDepthTest() { return depthTest; }
        public boolean isDepthMask() { return depthMask; }
        public boolean isCull() { return cull; }
        public boolean isLighting() { return lighting; }
        public int getColorMask() { return colorMask; }
        public float getRed() { return red; }
        public float getGreen() { return green; }
        public float getBlue() { return blue; }
        public float getAlpha() { return alpha; }
        public boolean hasHudState() { return hudStateKnown; }
        public int getViewportX() { return viewportX; }
        public int getViewportY() { return viewportY; }
        public int getViewportWidth() { return viewportWidth; }
        public int getViewportHeight() { return viewportHeight; }
        public long getInvalidationSerial() { return invalidationSerial; }
    }
}
