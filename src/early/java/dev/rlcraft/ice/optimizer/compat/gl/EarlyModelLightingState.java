package dev.rlcraft.ice.optimizer.compat.gl;

import java.nio.FloatBuffer;

/** Small wrapper-published mirror of the fixed vertex state used by model palettes. */
public final class EarlyModelLightingState {
    public static final int SIZE = 20;
    public static final int LIGHTING = 0, NORMALIZE = 1, RESCALE = 2, TWO_SIDE = 3,
        LOCAL_VIEWER = 4, SEPARATE = 5, COLOR_MATERIAL = 6, COLOR_FACE = 7,
        COLOR_MODE = 8, VERTEX_TWO_SIDE = 9, FOG_SOURCE = 10, TEXGEN = 11, LIGHTS = 12;
    private static final ThreadLocal<State> STATES = new ThreadLocal<State>() {
        @Override protected State initialValue() { return new State(); }
    };
    private EarlyModelLightingState() { }

    public static void seed(int[] values, int[] texgen, int activeTexture) {
        State state = STATES.get();
        if (values == null || values.length < SIZE || texgen == null || texgen.length != 8
            || activeTexture < 0 || activeTexture >= 32) { state.known = false; return; }
        System.arraycopy(values, 0, state.values, 0, SIZE);
        System.arraycopy(texgen, 0, state.texgen, 0, 8);
        state.activeTexture = activeTexture;
        state.known = true;
    }

    public static void invalidate() { STATES.get().known = false; }
    public static boolean isKnown() { return STATES.get().known; }
    public static boolean copyTo(int[] target) {
        State state = STATES.get();
        if (!state.known || target == null || target.length < SIZE) return false;
        System.arraycopy(state.values, 0, target, 0, SIZE);
        return true;
    }

    public static void capability(int cap, boolean enabled) {
        State state = STATES.get();
        int index;
        if (cap == 2896) index = LIGHTING;
        else if (cap == 2977) index = NORMALIZE;
        else if (cap == 32826) index = RESCALE;
        else if (cap == 2903) index = COLOR_MATERIAL;
        else if (cap == 0x8643) index = VERTEX_TWO_SIDE;
        else if (cap >= 16384 && cap < 16392) index = LIGHTS + cap - 16384;
        else if (cap >= 3168 && cap < 3172) { texGenCoord(cap - 3168, enabled); return; }
        else return;
        state.values[index] = enabled ? 1 : 0;
    }

    public static void light(int index, boolean enabled) {
        if (index >= 0 && index < 8) capability(16384 + index, enabled);
    }

    public static void activeTexture(int texture) { STATES.get().activeTexture = texture - 33984; }
    public static void texGenCoord(int coordinate, boolean enabled) {
        State state = STATES.get();
        if (coordinate < 0 || coordinate > 3 || state.activeTexture < 0
            || state.activeTexture >= 8) { state.known = false; return; }
        int bit = 1 << coordinate;
        if (enabled) state.texgen[state.activeTexture] |= bit;
        else state.texgen[state.activeTexture] &= ~bit;
        int all = 0;
        for (int value : state.texgen) all |= value;
        state.values[TEXGEN] = all;
    }

    public static void colorMaterial(int face, int mode) {
        State state = STATES.get(); state.values[COLOR_FACE] = face; state.values[COLOR_MODE] = mode;
    }

    public static void lightModel(int name, int value) {
        State state = STATES.get();
        if (name == 2898) state.values[TWO_SIDE] = value != 0 ? 1 : 0;
        else if (name == 2897) state.values[LOCAL_VIEWER] = value != 0 ? 1 : 0;
        else if (name == 33272) state.values[SEPARATE] = value == 33274 ? 1 : 0;
    }
    public static void lightModel(int name, float value) { lightModel(name, (int) value); }
    public static void lightModel(int name, FloatBuffer value) {
        if (value != null && value.hasRemaining()) lightModel(name, value.get(value.position()));
    }
    public static void fogi(int name, int value) {
        if (name == 0x8450) STATES.get().values[FOG_SOURCE] = value;
    }

    private static final class State {
        final int[] values = new int[SIZE];
        final int[] texgen = new int[8];
        int activeTexture;
        boolean known;
    }
}
