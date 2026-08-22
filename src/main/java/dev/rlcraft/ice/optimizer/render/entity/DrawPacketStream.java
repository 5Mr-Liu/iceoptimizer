package dev.rlcraft.ice.optimizer.render.entity;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.memory.RetainedHeap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded sequence-preserving packet stream used independently by entity and TESR. */
public final class DrawPacketStream implements AutoCloseable {
    private final int capacity;
    private ArrayList<DrawPacket> packets;
    private CacheBudget.Reservation heapReservation;
    private long lastSequence = -1L;
    private long rejected;
    private long barriers;

    public DrawPacketStream(int capacity) {
        this(capacity, null);
    }

    /** Production constructor which charges the pre-sized backing array. */
    public DrawPacketStream(int capacity, CacheBudget budget) {
        this.capacity = Math.max(16, capacity);
        CacheBudget.Reservation reservation = RetainedHeap.reserve(budget,
            heapBytesForCapacity(this.capacity), "draw packet stream");
        try {
            packets = new ArrayList<DrawPacket>(this.capacity);
            heapReservation = reservation;
        } catch (RuntimeException | Error failure) {
            reservation.close();
            throw failure;
        }
    }

    public boolean record(DrawPacket packet) {
        checkOpen();
        if (packet == null || packets.size() >= capacity
            || packet.getSequence() < lastSequence) {
            rejected++;
            return false;
        }
        packets.add(packet);
        lastSequence = packet.getSequence();
        return true;
    }

    public List<DrawPacketBatch> flushAtBarrier() {
        checkOpen();
        if (packets.isEmpty()) {
            barriers++;
            lastSequence = -1L;
            return Collections.emptyList();
        }
        ArrayList<DrawPacketBatch> result = new ArrayList<DrawPacketBatch>();
        ArrayList<DrawPacket> current = new ArrayList<DrawPacket>();
        DrawPacket first = null;
        for (DrawPacket packet : packets) {
            if (first == null || canBatch(first, packet)) {
                if (first == null) first = packet;
                current.add(packet);
            } else {
                result.add(new DrawPacketBatch(first.getState(), first.getEventScope(), current));
                current = new ArrayList<DrawPacket>();
                first = packet;
                current.add(packet);
            }
        }
        result.add(new DrawPacketBatch(first.getState(), first.getEventScope(), current));
        packets.clear();
        lastSequence = -1L;
        barriers++;
        return Collections.unmodifiableList(result);
    }

    /** Rolls back only the most recently accepted packet before any draw. */
    public boolean rollbackLast(DrawPacket packet) {
        checkOpen();
        int index = packets.size() - 1;
        if (index < 0 || packets.get(index) != packet) return false;
        packets.remove(index);
        lastSequence = index == 0 ? -1L : packets.get(index - 1).getSequence();
        return true;
    }

    /** Immediate emitters use packets for validation but do not allocate batches. */
    public void discardAtBarrier() {
        checkOpen();
        packets.clear();
        lastSequence = -1L;
        barriers++;
    }

    public int size() { return packets.size(); }
    public long getRejected() { return rejected; }
    public long getBarriers() { return barriers; }

    public boolean isClosed() { return packets == null; }

    @Override public void close() {
        ArrayList<DrawPacket> owned = packets;
        if (owned == null) return;
        packets = null;
        owned.clear();
        lastSequence = -1L;
        CacheBudget.Reservation reservation = heapReservation;
        heapReservation = null;
        if (reservation != null) reservation.close();
    }

    public static long heapBytesForCapacity(int requestedCapacity) {
        return RetainedHeap.referenceArray(Math.max(16, requestedCapacity));
    }

    private static boolean canBatch(DrawPacket first, DrawPacket next) {
        // Transparent packets may batch only while exact order remains the
        // command order; this function never sorts or buckets them.
        return first.getEventScope() == next.getEventScope()
            && first.isTransparent() == next.isTransparent()
            && first.getState().equals(next.getState())
            && sameGeneration(first.getGeneration(), next.getGeneration());
    }

    private static boolean sameGeneration(dev.rlcraft.ice.optimizer.render.frame.FrameStamp left,
                                          dev.rlcraft.ice.optimizer.render.frame.FrameStamp right) {
        return left.getWorldGeneration() == right.getWorldGeneration()
            && left.getResourceGeneration() == right.getResourceGeneration()
            && left.getGlContextGeneration() == right.getGlContextGeneration()
            && left.getShaderPermutationGeneration() == right.getShaderPermutationGeneration();
    }

    private void checkOpen() {
        if (packets == null) throw new IllegalStateException(
            "draw packet stream is closed");
    }
}
