package dev.rlcraft.ice.optimizer.render.optifine;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import java.util.LinkedHashMap;

/** Compile, state and image gates are all required for a native permutation. */
public final class ShaderCertificationRegistry implements AutoCloseable {
    private static final int MAX_LOG_CHARS = 16384;
    private static final long ENTRY_HEAP_BYTES = 4096L;
    private static final String OMITTED_LOG =
        "compiler log omitted: shared Heap budget exhausted";

    interface PublicationHook {
        void afterEntryPut();
    }

    private static final PublicationHook NO_PUBLICATION_HOOK =
        new PublicationHook() {
            @Override public void afterEntryPut() { }
        };

    private final int maximumPermutations;
    private final CacheBudget budget;
    private final PublicationHook publicationHook;
    private final LinkedHashMap<ShaderPermutationKey, Entry> entries =
        new LinkedHashMap<ShaderPermutationKey, Entry>(16, 0.75F, true);
    private boolean saturated;
    private boolean publicationPoisoned;
    private Entry uncertainEntry;

    public ShaderCertificationRegistry(int maximumPermutations) {
        this(maximumPermutations, null, NO_PUBLICATION_HOOK);
    }

    public ShaderCertificationRegistry(int maximumPermutations,
                                       CacheBudget budget) {
        this(maximumPermutations, budget, NO_PUBLICATION_HOOK);
    }

    ShaderCertificationRegistry(int maximumPermutations, CacheBudget budget,
                                PublicationHook publicationHook) {
        if (publicationHook == null) {
            throw new IllegalArgumentException(
                "shader certification publication hook");
        }
        this.maximumPermutations = Math.max(16, maximumPermutations);
        this.budget = budget;
        this.publicationHook = publicationHook;
    }

    public synchronized void recordCompile(ShaderPermutationKey key, boolean passed,
                                           String compilerLog) {
        Entry entry = entry(key);
        if (entry == null) return;
        entry.compiled = passed;
        entry.failed |= !passed;
        if (!replaceLog(entry, truncate(compilerLog))) saturated = true;
    }

    public synchronized void recordStateValidation(ShaderPermutationKey key,
                                                   boolean passed) {
        Entry entry = entry(key);
        if (entry == null) return;
        entry.stateValidated = passed;
        entry.failed |= !passed;
    }

    public synchronized void recordImageValidation(ShaderPermutationKey key,
                                                   boolean passed) {
        Entry entry = entry(key);
        if (entry == null) return;
        entry.imageValidated = passed;
        entry.failed |= !passed;
    }

    public synchronized boolean isCertified(ShaderPermutationKey key) {
        if (publicationPoisoned) return false;
        Entry entry = entries.get(key);
        return entry != null && !entry.failed && entry.compiled
            && entry.stateValidated && entry.imageValidated;
    }

    public synchronized boolean compilePassed(ShaderPermutationKey key) {
        if (publicationPoisoned) return false;
        Entry entry = entries.get(key);
        return entry != null && !entry.failed && entry.compiled;
    }

    public synchronized boolean statePassed(ShaderPermutationKey key) {
        if (publicationPoisoned) return false;
        Entry entry = entries.get(key);
        return entry != null && !entry.failed && entry.stateValidated;
    }

    public synchronized boolean imagePassed(ShaderPermutationKey key) {
        if (publicationPoisoned) return false;
        Entry entry = entries.get(key);
        return entry != null && !entry.failed && entry.imageValidated;
    }

    public synchronized boolean hasFailed(ShaderPermutationKey key) {
        if (publicationPoisoned) return true;
        Entry entry = entries.get(key);
        return entry != null && entry.failed;
    }

    public synchronized void invalidate() {
        publicationPoisoned = true;
        Entry[] removed = entries.values().toArray(
            new Entry[entries.size()]);
        entries.clear();
        Entry uncertain = uncertainEntry;
        uncertainEntry = null;
        Throwable failure = null;
        for (Entry entry : removed) try { entry.close(); }
        catch (Throwable releaseFailure) {
            failure = appendFailure(failure, releaseFailure);
        }
        if (uncertain != null) try { uncertain.close(); }
        catch (Throwable releaseFailure) {
            failure = appendFailure(failure, releaseFailure);
        }
        if (failure != null) rethrow(failure);
        publicationPoisoned = false;
        saturated = false;
    }

    @Override public synchronized void close() { invalidate(); }

    synchronized int entryCount() { return entries.size(); }
    synchronized boolean isSaturated() { return saturated; }

    private Entry entry(ShaderPermutationKey key) {
        if (key == null) throw new IllegalArgumentException("shader key");
        if (publicationPoisoned) {
            saturated = true;
            return null;
        }
        Entry value = entries.get(key);
        if (value == null) {
            if (entries.size() >= maximumPermutations) {
                saturated = true;
                return null;
            }
            CacheBudget.Reservation reservation = tryReserve(
                ENTRY_HEAP_BYTES);
            if (reservation == null) {
                saturated = true;
                return null;
            }
            value = new Entry(reservation);
            try {
                Entry previous = entries.put(key, value);
                if (previous != null) {
                    throw new IllegalStateException(
                        "shader certification publication replaced an entry");
                }
                publicationHook.afterEntryPut();
            } catch (Throwable publicationFailure) {
                Throwable failure = publicationFailure;
                boolean rolledBack = false;
                try {
                    Entry mapped = entries.get(key);
                    if (mapped == value) {
                        Entry removed = entries.remove(key);
                        if (removed != value || entries.get(key) != null) {
                            throw new IllegalStateException(
                                "shader certification rollback failed");
                        }
                    } else if (mapped != null) {
                        throw new IllegalStateException(
                            "shader certification rollback found a foreign entry");
                    }
                    rolledBack = true;
                } catch (Throwable rollbackFailure) {
                    failure = appendFailure(failure, rollbackFailure);
                }
                if (rolledBack) {
                    try { value.close(); }
                    catch (Throwable releaseFailure) {
                        failure = appendFailure(failure, releaseFailure);
                    }
                } else {
                    publicationPoisoned = true;
                    saturated = true;
                    uncertainEntry = value;
                }
                rethrow(failure);
            }
        }
        return value;
    }

    private boolean replaceLog(Entry entry, String value) {
        long bytes = value == null || value.isEmpty() ? 0L
            : Math.addExact(48L, Math.multiplyExact(2L,
                (long) value.length()));
        CacheBudget.Reservation replacement = tryReserve(bytes);
        if (replacement == null) {
            entry.replaceLog(OMITTED_LOG, CacheBudget.Reservation.empty());
            return false;
        }
        entry.replaceLog(value, replacement);
        return true;
    }

    private CacheBudget.Reservation tryReserve(long bytes) {
        if (budget == null || bytes == 0L) {
            return CacheBudget.Reservation.empty();
        }
        return budget.tryReserve(BudgetKind.HEAP, bytes);
    }

    private static String truncate(String value) {
        if (value == null) return "";
        return value.length() <= MAX_LOG_CHARS ? value
            : value.substring(0, MAX_LOG_CHARS);
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
        throw new IllegalStateException(
            "shader certification publication failed", failure);
    }

    private static final class Entry {
        private boolean compiled;
        private boolean stateValidated;
        private boolean imageValidated;
        private boolean failed;
        private String log = "";
        private CacheBudget.Reservation entryReservation;
        private CacheBudget.Reservation logReservation =
            CacheBudget.Reservation.empty();

        private Entry(CacheBudget.Reservation entryReservation) {
            this.entryReservation = entryReservation;
        }

        private void replaceLog(String value,
                                CacheBudget.Reservation replacement) {
            CacheBudget.Reservation previous = logReservation;
            log = value == null ? "" : value;
            logReservation = replacement;
            if (previous != null) previous.close();
        }

        private void close() {
            CacheBudget.Reservation logOwned = logReservation;
            logReservation = null;
            CacheBudget.Reservation entryOwned = entryReservation;
            entryReservation = null;
            log = "";
            Throwable failure = null;
            if (logOwned != null) try { logOwned.close(); }
            catch (Throwable releaseFailure) {
                failure = appendFailure(failure, releaseFailure);
            }
            if (entryOwned != null) try { entryOwned.close(); }
            catch (Throwable releaseFailure) {
                failure = appendFailure(failure, releaseFailure);
            }
            if (failure != null) rethrow(failure);
        }
    }
}
