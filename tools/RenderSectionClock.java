package dev.rlcraft.ice.benchmark.timing;

/** Diagnostic inclusive wall timings; never part of an optimizer artifact. */
public final class RenderSectionClock {
    private static final String[] NAMES = {"frame", "client_tick", "camera_and_render",
        "terrain_visibility", "terrain_layer", "entities", "hud", "particles", "storm", "weather",
        "ice_begin_frame", "ice_end_frame", "ice_software_occlusion", "ice_terrain_sprites", "ice_terrain_draw", "entity_draw",
        "ice_hud_state_queries", "ice_software_state_queries", "ice_model_state_queries",
        "ice_terrain_direction_state", "ice_terrain_buffer_state", "ice_arena_fit",
        "ice_terrain_mapped_run", "ice_terrain_legacy_run", "ice_terrain_animated_run",
        "ice_terrain_upload", "ice_terrain_end_frame", "ice_terrain_array_layout", "ice_terrain_region_scope", "ice_terrain_index_encode",
        "otg_fog_color", "otg_fog_distance", "atlas_animations", "chunk_upload_queue",
        "native_submission_plan", "native_submission_gl", "native_frame_prepare", "native_visibility_bfs",
        "chunk_compile_now", "chunk_compile_later", "chunk_update_dispatch", "native_chunk_animation",
        "native_modelview_read", "native_command_pack", "native_command_bulk_copy", "native_camera_inverse", "native_index_refresh",
        "native_queue_rebuild", "sampled_visibility_append", "sampled_visibility_neighbor", "sampled_visibility_frustum",
        "sampled_visibility_mask", "sampled_visibility_info", "sampled_visibility_layer_queue", "sampled_visibility_frames",
        "sampled_visibility_empty_bracket"};
    private static final long[] CALLS = new long[NAMES.length], NANOS = new long[NAMES.length];
    private static volatile boolean enabled;
    private static int visibilitySequence;
    private static boolean visibilitySample;
    private static volatile boolean entityAdapter = true;
    private static final java.util.Map<Class<?>, long[]> TYPES = new java.util.LinkedHashMap<Class<?>, long[]>();
    private static final long[][] DECISIONS = new long[6][2];
    private static final String[] DECISION_NAMES = {"entity_gate", "model_candidate", "model_collect", "model_entry", "model_batch_begin", "model_node_ready"};
    public static void decision(boolean accepted, int kind) {
        if (enabled) DECISIONS[kind][accepted ? 1 : 0]++;
    }
    public static void objectDecision(Object value, int kind) { decision(value != null, kind); }
    public static void setEntityAdapter(boolean value) { entityAdapter = value; }
    public static boolean getEntityAdapter() { return entityAdapter; }
    public static boolean filterBackendObservation(boolean original, Object module) {
        boolean accepted = original && (entityAdapter || !"MODERN_ENTITY_BACKEND".equals(((Enum<?>) module).name()));
        if ("MODERN_ENTITY_BACKEND".equals(((Enum<?>) module).name())) decision(accepted, 0);
        return accepted;
    }
    public static void reset() {
        enabled = false;
        java.util.Arrays.fill(CALLS, 0); java.util.Arrays.fill(NANOS, 0);
        TYPES.clear();
        for (long[] counts : DECISIONS) java.util.Arrays.fill(counts, 0);
        visibilitySequence = 0;
        visibilitySample = false;
        enabled = true;
    }
    public static void visibilityDetailFrame() {
        visibilitySample = enabled && (++visibilitySequence & 127) == 0;
        if (visibilitySample) { CALLS[54]++; end(55, beginVisibilityDetail()); }
    }
    public static long beginVisibilityDetail() { return visibilitySample ? System.nanoTime() : 0; }
    public static long begin() { return enabled ? System.nanoTime() : 0; }
    public static void end(int section, long start) {
        if (start == 0 || !enabled || !"Client thread".equals(Thread.currentThread().getName())) return;
        NANOS[section] += System.nanoTime() - start;
        CALLS[section]++;
    }
    public static void endEntity(Object entity, long start) {
        if (start == 0 || !enabled || !"Client thread".equals(Thread.currentThread().getName())) return;
        long elapsed = System.nanoTime() - start;
        NANOS[15] += elapsed; CALLS[15]++;
        Class<?> type = entity == null ? Object.class : entity.getClass();
        long[] values = TYPES.get(type);
        if (values == null) { values = new long[2]; TYPES.put(type, values); }
        values[0]++; values[1] += elapsed;
    }
    public static String stop() {
        enabled = false;
        StringBuilder out = new StringBuilder("section,calls,inclusive_ns,ns_per_call,ns_per_frame\n");
        for (int i = 0; i < NAMES.length; i++) out.append(NAMES[i]).append(',').append(CALLS[i])
            .append(',').append(NANOS[i]).append(',').append(CALLS[i] == 0 ? 0 : NANOS[i] / CALLS[i])
            .append(',').append(CALLS[0] == 0 ? 0 : NANOS[i] / CALLS[0]).append('\n');
        for (java.util.Map.Entry<Class<?>, long[]> entry : TYPES.entrySet()) {
            long[] values = entry.getValue();
            out.append("entity_type:").append(entry.getKey().getName()).append(',').append(values[0])
                .append(',').append(values[1]).append(',').append(values[1]/values[0]).append(',')
                .append(CALLS[0] == 0 ? 0 : values[1]/CALLS[0]).append('\n');
        }
        for (int i = 0; i < DECISIONS.length; i++) for (int result = 0; result < 2; result++)
            out.append("decision:").append(DECISION_NAMES[i]).append(result == 1 ? ":accepted," : ":declined,")
                .append(DECISIONS[i][result]).append(",0,0,0\n");
        return out.toString();
    }
}
