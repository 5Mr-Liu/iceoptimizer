package dev.rlcraft.ice.optimizer.render.particle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ParticleBatch {
    private final ParticleState state;
    private final List<ParticleInstance> instances;

    ParticleBatch(ParticleState state, List<ParticleInstance> instances) {
        this.state = state;
        this.instances = Collections.unmodifiableList(
            new ArrayList<ParticleInstance>(instances));
    }

    public ParticleState getState() { return state; }
    public List<ParticleInstance> getInstances() { return instances; }
}
