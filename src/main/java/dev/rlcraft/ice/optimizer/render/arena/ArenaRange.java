package dev.rlcraft.ice.optimizer.render.arena;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;

/** Generation and serial qualified sub-allocation. */
public final class ArenaRange {
    private final long id;
    private final long serial;
    private final long offset;
    private final long length;
    private final long generation;
    private CacheBudget.Reservation heapReservation;

    ArenaRange(long id, long serial, long offset, long length, long generation) {
        this(id, serial, offset, length, generation,
            CacheBudget.Reservation.empty());
    }

    ArenaRange(long id, long serial, long offset, long length, long generation,
               CacheBudget.Reservation heapReservation) {
        if (heapReservation == null) throw new IllegalArgumentException(
            "arena range Heap reservation");
        this.id = id;
        this.serial = serial;
        this.offset = offset;
        this.length = length;
        this.generation = generation;
        this.heapReservation = heapReservation;
    }

    public long getId() { return id; }
    public long getSerial() { return serial; }
    public long getOffset() { return offset; }
    public long getLength() { return length; }
    public long getGeneration() { return generation; }

    void releaseHeapReservation() {
        CacheBudget.Reservation owned = heapReservation;
        heapReservation = null;
        if (owned != null) owned.close();
    }

    public long endExclusive() {
        if (length > Long.MAX_VALUE - offset) throw new ArithmeticException("arena range overflow");
        return offset + length;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof ArenaRange)) return false;
        ArenaRange other = (ArenaRange) value;
        return id == other.id && serial == other.serial && generation == other.generation;
    }

    @Override public int hashCode() {
        long value = id ^ (id >>> 32) ^ serial ^ (serial >>> 32) ^ generation;
        return (int) (value ^ (value >>> 32));
    }
}
