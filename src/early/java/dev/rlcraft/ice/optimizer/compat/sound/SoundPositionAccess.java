package dev.rlcraft.ice.optimizer.compat.sound;

/** Coordinates of SoundFilters' room-search positions, without reflective hot-path reads. */
public interface SoundPositionAccess {
    float ice$soundX();
    float ice$soundY();
    float ice$soundZ();
}
