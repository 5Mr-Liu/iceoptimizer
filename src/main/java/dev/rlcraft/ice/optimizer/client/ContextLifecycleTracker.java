package dev.rlcraft.ice.optimizer.client;

/** Identity-only GL context transition detector with null-loss de-duplication. */
final class ContextLifecycleTracker {
    private Object context;
    private boolean observed;
    private boolean present;

    /** Returns true exactly once for each observed loss/replacement. */
    boolean observe(Object current) {
        if (!observed) {
            observed = true;
            present = current != null;
            context = current;
            return false;
        }
        if (current == null) {
            if (!present) return false;
            present = false;
            context = null;
            return true;
        }
        if (!present) {
            // The preceding non-null -> null transition already advanced the
            // generation.  Reappearance publishes the new identity without a
            // second reset, allowing initialization immediately.
            present = true;
            context = current;
            return false;
        }
        if (context == current) return false;
        context = current;
        return true;
    }

    boolean isPresent() { return present; }
}
