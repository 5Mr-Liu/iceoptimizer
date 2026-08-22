package dev.rlcraft.ice.optimizer.render.hud;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.memory.RetainedHeap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Dynamic GUI stream which never crosses a caller-declared event barrier.
 * Recording uses fixed, budgeted reference arrays. Immutable batch objects
 * are materialized only by the diagnostic barrier() API; production barriers
 * use discardAtBarrier().
 */
public final class HudVertexStream implements AutoCloseable {
    private final int capacity;
    private HudState[] states;
    private HudQuad[] quads;
    private CacheBudget.Reservation heapReservation;
    private long lastSequence = -1L;
    private int size;
    private long rejected;
    private long barriers;

    public HudVertexStream(int capacity) {
        this(capacity, null);
    }

    /** Production constructor which charges both fixed backing arrays. */
    public HudVertexStream(int capacity, CacheBudget budget) {
        this.capacity = Math.max(64, capacity);
        CacheBudget.Reservation reservation = RetainedHeap.reserve(budget,
            heapBytesForCapacity(this.capacity), "HUD vertex stream");
        try {
            states = new HudState[this.capacity];
            quads = new HudQuad[this.capacity];
            heapReservation = reservation;
        } catch (RuntimeException | Error failure) {
            reservation.close();
            throw failure;
        }
    }

    public boolean record(HudState nextState, HudQuad quad) {
        checkOpen();
        if (nextState == null || quad == null || size >= capacity
            || quad.getSequence() < lastSequence) {
            rejected++;
            return false;
        }
        states[size] = nextState;
        quads[size++] = quad;
        lastSequence = quad.getSequence();
        return true;
    }

    /** Materializes immutable runs for tests and explicit diagnostics only. */
    public List<HudBatch> barrier() {
        checkOpen();
        if (size == 0) {
            barriers++;
            lastSequence = -1L;
            return Collections.emptyList();
        }
        ArrayList<HudBatch> result = new ArrayList<HudBatch>();
        int runStart = 0;
        while (runStart < size) {
            HudState state = states[runStart];
            int runEnd = runStart + 1;
            while (runEnd < size && state.equals(states[runEnd])) runEnd++;
            ArrayList<HudQuad> run = new ArrayList<HudQuad>(runEnd - runStart);
            for (int index = runStart; index < runEnd; index++) {
                run.add(quads[index]);
            }
            result.add(new HudBatch(state, run));
            runStart = runEnd;
        }
        discardAtBarrier();
        return Collections.unmodifiableList(result);
    }

    /** Allocation-free production barrier after the immediate HUD emitter. */
    public void discardAtBarrier() {
        checkOpen();
        for (int index = 0; index < size; index++) {
            states[index] = null;
            quads[index] = null;
        }
        size = 0;
        lastSequence = -1L;
        barriers++;
    }

    public int size() { return size; }
    public long getRejected() { return rejected; }
    public long getBarriers() { return barriers; }
    public boolean isClosed() { return states == null; }

    @Override public void close() {
        HudState[] ownedStates = states;
        HudQuad[] ownedQuads = quads;
        if (ownedStates == null) return;
        for (int index = 0; index < size; index++) {
            ownedStates[index] = null;
            ownedQuads[index] = null;
        }
        states = null;
        quads = null;
        size = 0;
        lastSequence = -1L;
        CacheBudget.Reservation reservation = heapReservation;
        heapReservation = null;
        if (reservation != null) reservation.close();
    }

    public static long heapBytesForCapacity(int requestedCapacity) {
        int actual = Math.max(64, requestedCapacity);
        return Math.multiplyExact(2L, RetainedHeap.referenceArray(actual));
    }

    private void checkOpen() {
        if (states == null) throw new IllegalStateException(
            "HUD vertex stream is closed");
    }
}
