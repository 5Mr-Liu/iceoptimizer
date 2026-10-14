package dev.rlcraft.ice.optimizer.compat.srp;

/** Candidate-index ownership follows the actual mutable block-state container. */
public interface SrpLightSectionAccess {
    Object ice$lightCandidates();
    void ice$lightCandidates(Object value);
}
