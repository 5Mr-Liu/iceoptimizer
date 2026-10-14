package dev.rlcraft.ice.optimizer.render.entity;

/** Values belong to one private linked program and survive binding another program. */
final class ModelPoseUniforms {
    interface Writer { void set(int location, int value); }

    private final Writer writer;
    private final int[] values = new int[19];
    private int known;

    ModelPoseUniforms(Writer writer) { this.writer = writer; }

    void set(int slot, int location, int value) {
        int bit = 1 << slot;
        if ((known & bit) != 0 && values[slot] == value) return;
        writer.set(location, value);
        // A throwing native submission must not become an accepted cached value.
        values[slot] = value;
        known |= bit;
    }

    void reset() { known = 0; }
}
