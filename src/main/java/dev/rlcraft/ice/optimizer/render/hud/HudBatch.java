package dev.rlcraft.ice.optimizer.render.hud;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class HudBatch {
    private final HudState state;
    private final List<HudQuad> quads;

    HudBatch(HudState state, List<HudQuad> quads) {
        this.state = state;
        this.quads = Collections.unmodifiableList(new ArrayList<HudQuad>(quads));
    }

    public HudState getState() { return state; }
    public List<HudQuad> getQuads() { return quads; }
}
