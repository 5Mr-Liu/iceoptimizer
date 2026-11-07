package dev.rlcraft.ice.optimizer.render.terrain;

/** One selection per context lifetime. No settings or extension combinations. */
final class NativeTerrainBackendChoice {
    static final int GL21 = 21, GL43 = 43;
    private int selected;

    int select(boolean gl43, boolean gl21) {
        if (selected != 0) return selected;
        if (!gl43 && !gl21) throw new IllegalStateException(
            "ICE native terrain requires at least OpenGL 2.1; the current context does not support it");
        return selected = gl43 ? GL43 : GL21;
    }

    int get() {
        if (selected == 0) throw new IllegalStateException("ICE terrain context was not initialized before terrain construction");
        return selected;
    }

    boolean initialized() { return selected != 0; }
}
