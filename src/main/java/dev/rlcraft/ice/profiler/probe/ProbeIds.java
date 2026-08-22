package dev.rlcraft.ice.profiler.probe;

public final class ProbeIds {
    public static final int ENTITY_TICK = 1;
    public static final int TILE_ENTITY_TICK = 2;
    public static final int EVENT_HANDLER = 3;
    public static final int CHUNK_GENERATION = 4;
    public static final int CHUNK_LOAD = 5;
    public static final int CHUNK_SAVE = 6;
    public static final int PACKET_INBOUND = 7;
    public static final int PACKET_OUTBOUND = 8;
    public static final int CHUNK_RENDER = 9;
    public static final int WORLD_TICK = 10;
    /** Rare outer scope covering vanilla item completion and Forge Finish. */
    public static final int ITEM_USE_FINISH = 11;
    /** Vanilla ItemPotion body nested inside ITEM_USE_FINISH. */
    public static final int POTION_ITEM_FINISH = 12;

    private ProbeIds() {
    }

    public static String name(int id) {
        switch (id) {
            case ENTITY_TICK: return "entity_tick";
            case TILE_ENTITY_TICK: return "tile_entity_tick";
            case EVENT_HANDLER: return "event_handler";
            case CHUNK_GENERATION: return "chunk_generation";
            case CHUNK_LOAD: return "chunk_load";
            case CHUNK_SAVE: return "chunk_save";
            case PACKET_INBOUND: return "packet_inbound";
            case PACKET_OUTBOUND: return "packet_outbound";
            case CHUNK_RENDER: return "chunk_render";
            case WORLD_TICK: return "world_tick";
            case ITEM_USE_FINISH: return "item_use_finish";
            case POTION_ITEM_FINISH: return "potion_item_finish";
            default: return "probe_" + id;
        }
    }
}
