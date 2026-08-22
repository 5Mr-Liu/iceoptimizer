package dev.rlcraft.ice.optimizer.render.entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DrawPacketBatch {
    private final RenderStateKey state;
    private final long eventScope;
    private final List<DrawPacket> packets;

    DrawPacketBatch(RenderStateKey state, long eventScope, List<DrawPacket> packets) {
        this.state = state;
        this.eventScope = eventScope;
        this.packets = Collections.unmodifiableList(new ArrayList<DrawPacket>(packets));
    }

    public RenderStateKey getState() { return state; }
    public long getEventScope() { return eventScope; }
    public List<DrawPacket> getPackets() { return packets; }
}
