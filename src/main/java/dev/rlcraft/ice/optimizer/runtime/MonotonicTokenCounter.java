package dev.rlcraft.ice.optimizer.runtime;

import java.util.concurrent.atomic.AtomicLong;

/** Allocates positive process-local tokens without ever wrapping or reusing one. */
public final class MonotonicTokenCounter {
    private MonotonicTokenCounter() {
    }

    public static long next(AtomicLong counter, String subject) {
        if (counter == null) throw new NullPointerException("counter");
        String name = subject == null || subject.isEmpty() ? "token" : subject;
        while (true) {
            long value = counter.get();
            if (value <= 0L || value == Long.MAX_VALUE) {
                throw new IllegalStateException(name + " exhausted");
            }
            if (counter.compareAndSet(value, value + 1L)) return value;
        }
    }

    /** Returns the reserved zero sentinel once a counter can no longer advance. */
    public static long nextOrZero(AtomicLong counter, String subject) {
        try { return next(counter, subject); }
        catch (IllegalStateException exhausted) { return 0L; }
    }
}
