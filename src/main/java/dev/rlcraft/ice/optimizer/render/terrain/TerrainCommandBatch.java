package dev.rlcraft.ice.optimizer.render.terrain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TerrainCommandBatch {
    private final TerrainLayer layer;
    private final List<TerrainDrawCommand> commands;

    TerrainCommandBatch(TerrainLayer layer, List<TerrainDrawCommand> commands) {
        this.layer = layer;
        this.commands = Collections.unmodifiableList(
            new ArrayList<TerrainDrawCommand>(commands));
    }

    public TerrainLayer getLayer() { return layer; }
    public List<TerrainDrawCommand> getCommands() { return commands; }
}
