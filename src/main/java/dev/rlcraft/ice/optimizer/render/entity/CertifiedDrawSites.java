package dev.rlcraft.ice.optimizer.render.entity;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded generation-scoped certification table; unknown renderers stay legacy. */
public final class CertifiedDrawSites implements AutoCloseable {
    private static final int MAX_RENDERER_CHARS = 1024;
    private static final int MAX_FINGERPRINT_CHARS = 4096;
    private static final long KEY_ENVELOPE_BYTES = 192L;

    interface PublicationHook {
        void afterEntryPut();
    }

    private static final PublicationHook NO_PUBLICATION_HOOK =
        new PublicationHook() {
            @Override public void afterEntryPut() { }
        };

    private final int maximumEntries;
    private final CacheBudget budget;
    private final PublicationHook publicationHook;
    private final LinkedHashMap<Key, Boolean> entries;
    private boolean trusted = true;
    private Key uncertainKey;

    public CertifiedDrawSites(int maximumEntries) {
        this(maximumEntries, null, NO_PUBLICATION_HOOK);
    }

    public CertifiedDrawSites(int maximumEntries, CacheBudget budget) {
        this(maximumEntries, budget, NO_PUBLICATION_HOOK);
    }

    CertifiedDrawSites(int maximumEntries, CacheBudget budget,
                       PublicationHook publicationHook) {
        if (publicationHook == null) {
            throw new IllegalArgumentException("draw site publication hook");
        }
        this.maximumEntries = Math.max(16, maximumEntries);
        this.budget = budget;
        this.publicationHook = publicationHook;
        entries = new LinkedHashMap<Key, Boolean>(16, 0.75F, true);
    }

    public synchronized void certify(String rendererClass, String structureFingerprint,
                                     long resourceGeneration, long shaderGeneration,
                                     boolean outputEquivalent) {
        Key key = new Key(rendererClass, structureFingerprint, resourceGeneration,
            shaderGeneration);
        if (!trusted) return;
        Boolean current = entries.get(key);
        if (current != null) {
            // HashMap updates the value of the resident entry without replacing
            // its reservation-owning key object.
            publishUpdate(key, current, Boolean.valueOf(outputEquivalent));
            return;
        }
        CacheBudget.Reservation reservation = tryReserve(key.heapBytes());
        if (reservation == null) return;
        key.own(reservation);
        try {
            Boolean previous = entries.put(key,
                Boolean.valueOf(outputEquivalent));
            if (previous != null) {
                throw new IllegalStateException(
                    "draw site publication replaced an existing entry");
            }
            publicationHook.afterEntryPut();
        } catch (Throwable publicationFailure) {
            Throwable failure = publicationFailure;
            boolean rolledBack = false;
            try {
                Boolean mapped = entries.get(key);
                if (mapped != null) {
                    Boolean removed = entries.remove(key);
                    if (removed == null || entries.containsKey(key)) {
                        throw new IllegalStateException(
                            "draw site publication rollback failed");
                    }
                }
                rolledBack = true;
            } catch (Throwable rollbackFailure) {
                failure = appendFailure(failure, rollbackFailure);
            }
            if (rolledBack) {
                try { key.close(); }
                catch (Throwable releaseFailure) {
                    failure = appendFailure(failure, releaseFailure);
                }
            } else {
                trusted = false;
                uncertainKey = key;
            }
            rethrow(failure);
        }
        while (entries.size() > maximumEntries) {
            Map.Entry<Key, Boolean> eldest = entries.entrySet().iterator().next();
            entries.remove(eldest.getKey());
            eldest.getKey().close();
        }
    }

    public synchronized boolean isCertified(String rendererClass,
                                            String structureFingerprint,
                                            long resourceGeneration,
                                            long shaderGeneration) {
        return trusted && Boolean.TRUE.equals(entries.get(new Key(rendererClass,
            structureFingerprint, resourceGeneration, shaderGeneration)));
    }

    public synchronized void invalidate() {
        trusted = false;
        Key[] removed = entries.keySet().toArray(new Key[entries.size()]);
        entries.clear();
        Key uncertain = uncertainKey;
        uncertainKey = null;
        Throwable failure = null;
        for (Key key : removed) try { key.close(); }
        catch (Throwable releaseFailure) {
            failure = appendFailure(failure, releaseFailure);
        }
        if (uncertain != null) try { uncertain.close(); }
        catch (Throwable releaseFailure) {
            failure = appendFailure(failure, releaseFailure);
        }
        if (failure != null) rethrow(failure);
        trusted = true;
    }

    @Override public synchronized void close() { invalidate(); }

    synchronized int entryCount() { return entries.size(); }

    private CacheBudget.Reservation tryReserve(long bytes) {
        if (budget == null) return CacheBudget.Reservation.empty();
        return budget.tryReserve(BudgetKind.HEAP, bytes);
    }

    private void publishUpdate(Key key, Boolean current, Boolean replacement) {
        try {
            Boolean previous = entries.put(key, replacement);
            if (previous != current) {
                throw new IllegalStateException(
                    "draw site update replaced an unexpected value");
            }
            publicationHook.afterEntryPut();
        } catch (Throwable publicationFailure) {
            Throwable failure = publicationFailure;
            try {
                Boolean mapped = entries.get(key);
                if (mapped != current) {
                    Boolean displaced = entries.put(key, current);
                    if (displaced == null || entries.get(key) != current) {
                        throw new IllegalStateException(
                            "draw site update rollback failed");
                    }
                }
            } catch (Throwable rollbackFailure) {
                trusted = false;
                failure = appendFailure(failure, rollbackFailure);
            }
            rethrow(failure);
        }
    }

    private static Throwable appendFailure(Throwable first, Throwable next) {
        if (first == null) return next;
        Throwable nextFatal = FatalErrors.findFatal(next);
        if (nextFatal != null && FatalErrors.findFatal(first) == null) {
            if (nextFatal != first) nextFatal.addSuppressed(first);
            return nextFatal;
        }
        if (next != null && first != next) first.addSuppressed(next);
        return first;
    }

    private static void rethrow(Throwable failure) {
        FatalErrors.rethrowIfFatal(failure);
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) throw (Error) failure;
        throw new IllegalStateException("draw site certification failed", failure);
    }

    private static final class Key {
        private final String renderer;
        private final String fingerprint;
        private final long resources;
        private final long shaders;
        private CacheBudget.Reservation reservation;

        private Key(String renderer, String fingerprint, long resources, long shaders) {
            if (renderer == null || renderer.isEmpty()
                || renderer.length() > MAX_RENDERER_CHARS
                || fingerprint == null || fingerprint.isEmpty()
                || fingerprint.length() > MAX_FINGERPRINT_CHARS
                || resources <= 0L || shaders <= 0L) {
                throw new IllegalArgumentException("draw site key");
            }
            this.renderer = renderer;
            this.fingerprint = fingerprint;
            this.resources = resources;
            this.shaders = shaders;
        }

        private long heapBytes() {
            long chars = Math.addExact((long) renderer.length(),
                (long) fingerprint.length());
            return Math.addExact(KEY_ENVELOPE_BYTES,
                Math.multiplyExact(2L, chars));
        }

        private void own(CacheBudget.Reservation value) {
            if (value == null || reservation != null) {
                throw new IllegalStateException("draw site reservation");
            }
            reservation = value;
        }

        private void close() {
            CacheBudget.Reservation owned = reservation;
            reservation = null;
            if (owned != null) owned.close();
        }

        @Override public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Key)) return false;
            Key other = (Key) value;
            return resources == other.resources && shaders == other.shaders
                && renderer.equals(other.renderer) && fingerprint.equals(other.fingerprint);
        }

        @Override public int hashCode() {
            int result = renderer.hashCode();
            result = 31 * result + fingerprint.hashCode();
            result = 31 * result + (int) (resources ^ (resources >>> 32));
            return 31 * result + (int) (shaders ^ (shaders >>> 32));
        }
    }
}
