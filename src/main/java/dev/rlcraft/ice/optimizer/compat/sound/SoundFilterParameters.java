package dev.rlcraft.ice.optimizer.compat.sound;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import org.lwjgl.openal.EFX10;

/** Remembers only values actually submitted for this filter's own OpenAL objects. */
public final class SoundFilterParameters {
    private SoundFilterParameters() { }
    public static boolean begin(Object filter) {
        boolean enabled = SoundSearchBridge.isEnabled();
        if (!enabled) reset(filter);
        return enabled;
    }
    public static void reset(Object filter) { ((SoundFilterAccess) filter).ice$soundParameters(null); }
    private static boolean unchanged(Object filter, boolean enabled, int kind, int id, int parameter, int value) {
        if (!enabled || id <= 0) return false;
        SoundFilterAccess owner = (SoundFilterAccess) filter;
        State state = (State) owner.ice$soundParameters();
        if (state == null) { state = new State(); owner.ice$soundParameters(state); }
        int key = kind << 16 | parameter;
        long pair = (long) id << 32 | value & 0xffffffffL;
        return state.values.containsKey(key) && state.values.get(key) == pair;
    }
    private static void submitted(Object filter, boolean enabled, int kind, int id, int parameter, int value) {
        if (!enabled || id <= 0) return;
        SoundFilterAccess owner = (SoundFilterAccess) filter;
        State state = (State) owner.ice$soundParameters();
        if (state == null) { state = new State(); owner.ice$soundParameters(state); }
        state.values.put(kind << 16 | parameter, (long) id << 32 | value & 0xffffffffL);
    }
    public static void alFilterf(int id, int parameter, float value, Object filter, boolean enabled) {
        int bits = Float.floatToRawIntBits(value);
        if (!Float.isNaN(value) && unchanged(filter, enabled, 1, id, parameter, bits)) return;
        EFX10.alFilterf(id, parameter, value);
        submitted(filter, enabled, 1, id, parameter, bits);
    }
    public static void alEffectf(int id, int parameter, float value, Object filter, boolean enabled) {
        int bits = Float.floatToRawIntBits(value);
        if (!Float.isNaN(value) && unchanged(filter, enabled, 2, id, parameter, bits)) return;
        EFX10.alEffectf(id, parameter, value);
        submitted(filter, enabled, 2, id, parameter, bits);
    }
    public static void alAuxiliaryEffectSlotf(int id, int parameter, float value, Object filter, boolean enabled) {
        int bits = Float.floatToRawIntBits(value);
        if (!Float.isNaN(value) && unchanged(filter, enabled, 3, id, parameter, bits)) return;
        EFX10.alAuxiliaryEffectSlotf(id, parameter, value);
        submitted(filter, enabled, 3, id, parameter, bits);
    }
    public static void alEffecti(int id, int parameter, int value, Object filter, boolean enabled) {
        if (unchanged(filter, enabled, 4, id, parameter, value)) return;
        EFX10.alEffecti(id, parameter, value);
        submitted(filter, enabled, 4, id, parameter, value);
    }
    public static void alAuxiliaryEffectSloti(int id, int parameter, int value, Object filter, boolean enabled) {
        // Attaching the effect to its slot commits the current effect parameters.
        // Keep this original call even if id/value are unchanged after an effect update.
        EFX10.alAuxiliaryEffectSloti(id, parameter, value);
    }
    private static final class State { final Int2LongOpenHashMap values = new Int2LongOpenHashMap(16); }
}
