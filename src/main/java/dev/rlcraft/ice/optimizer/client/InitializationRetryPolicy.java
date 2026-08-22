package dev.rlcraft.ice.optimizer.client;

/** Generation-aware bounded backoff for render-component initialization. */
final class InitializationRetryPolicy {
    static final long DEFAULT_BASE_DELAY_NANOS = 50_000_000L;
    static final long DEFAULT_MAX_DELAY_NANOS = 5_000_000_000L;

    private final long baseDelayNanos;
    private final long maximumDelayNanos;
    private long generation = Long.MIN_VALUE;
    private long retryAtNanos = Long.MIN_VALUE;
    private int failures;

    InitializationRetryPolicy() {
        this(DEFAULT_BASE_DELAY_NANOS, DEFAULT_MAX_DELAY_NANOS);
    }

    InitializationRetryPolicy(long baseDelayNanos, long maximumDelayNanos) {
        if (baseDelayNanos <= 0L || maximumDelayNanos < baseDelayNanos) {
            throw new IllegalArgumentException("initialization retry delays");
        }
        this.baseDelayNanos = baseDelayNanos;
        this.maximumDelayNanos = maximumDelayNanos;
    }

    boolean canAttempt(long currentGeneration, long nowNanos) {
        observeGeneration(currentGeneration);
        return failures == 0 || nowNanos - retryAtNanos >= 0L;
    }

    long recordFailure(long currentGeneration, long nowNanos) {
        observeGeneration(currentGeneration);
        if (failures < 31) failures++;
        long delay = baseDelayNanos;
        int shifts = Math.min(30, failures - 1);
        for (int i = 0; i < shifts && delay < maximumDelayNanos; i++) {
            delay = delay > maximumDelayNanos / 2L
                ? maximumDelayNanos : delay * 2L;
        }
        delay = Math.min(delay, maximumDelayNanos);
        retryAtNanos = nowNanos > Long.MAX_VALUE - delay
            ? Long.MAX_VALUE : nowNanos + delay;
        return delay;
    }

    void recordSuccess(long currentGeneration) {
        generation = currentGeneration;
        failures = 0;
        retryAtNanos = Long.MIN_VALUE;
    }

    int failures() { return failures; }

    long remainingNanos(long currentGeneration, long nowNanos) {
        observeGeneration(currentGeneration);
        if (failures == 0 || nowNanos - retryAtNanos >= 0L) return 0L;
        return retryAtNanos - nowNanos;
    }

    private void observeGeneration(long currentGeneration) {
        if (currentGeneration <= 0L) {
            throw new IllegalArgumentException("initialization generation");
        }
        if (generation == currentGeneration) return;
        generation = currentGeneration;
        failures = 0;
        retryAtNanos = Long.MIN_VALUE;
    }
}
