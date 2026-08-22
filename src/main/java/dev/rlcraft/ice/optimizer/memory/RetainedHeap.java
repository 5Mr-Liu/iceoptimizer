package dev.rlcraft.ice.optimizer.memory;

/**
 * Deterministic, overflow-safe charges for optimizer-owned retained arrays.
 * Reference slots are deliberately charged at eight bytes so the hard budget
 * remains conservative both with and without compressed ordinary object
 * pointers.  The sixteen-byte header and eight-byte alignment match the
 * supported 64-bit HotSpot layout and overcharge, rather than undercharge,
 * smaller layouts.
 */
public final class RetainedHeap {
    private static final long ARRAY_HEADER_BYTES = 16L;
    private static final long ALIGNMENT = 8L;

    private RetainedHeap() {}

    public static long byteArray(int length) { return array(length, 1); }
    public static long booleanArray(int length) { return array(length, 1); }
    public static long intArray(int length) { return array(length, 4); }
    public static long floatArray(int length) { return array(length, 4); }
    public static long longArray(int length) { return array(length, 8); }
    public static long doubleArray(int length) { return array(length, 8); }
    public static long referenceArray(int length) { return array(length, 8); }

    public static long add(long left, long right) {
        if (left < 0L || right < 0L) {
            throw new IllegalArgumentException("negative retained heap charge");
        }
        return Math.addExact(left, right);
    }

    public static CacheBudget.Reservation reserve(CacheBudget budget,
                                                   long bytes,
                                                   String component) {
        if (bytes < 0L) throw new IllegalArgumentException(
            "negative retained heap charge");
        if (budget == null || bytes == 0L) {
            return CacheBudget.Reservation.empty();
        }
        CacheBudget.Reservation reservation = budget.tryReserve(
            BudgetKind.HEAP, bytes);
        if (reservation == null) {
            throw new IllegalStateException((component == null
                ? "retained component" : component)
                + " Heap budget exhausted");
        }
        return reservation;
    }

    private static long array(int length, int elementBytes) {
        if (length < 0) throw new IllegalArgumentException(
            "negative retained array length");
        long payload = Math.multiplyExact((long) length,
            (long) elementBytes);
        long raw = Math.addExact(ARRAY_HEADER_BYTES, payload);
        return Math.addExact(raw, ALIGNMENT - 1L) & -ALIGNMENT;
    }
}
