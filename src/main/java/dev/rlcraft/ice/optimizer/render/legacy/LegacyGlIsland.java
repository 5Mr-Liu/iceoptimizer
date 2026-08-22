package dev.rlcraft.ice.optimizer.render.legacy;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import java.util.concurrent.Callable;

/** Flush/restore/execute/invalidate boundary for unknown or observing GL code. */
public final class LegacyGlIsland {
    private static final int MAXIMUM_DEPTH = 32;
    public interface ModernBatchFlusher {
        void flush();
    }

    public interface LegacyStateRestorer {
        void restoreLegacyCallSiteState();
    }

    private final RenderThreadGuard threadGuard;
    private final GlStateMirror mirror;
    private final ModernBatchFlusher flusher;
    private final LegacyStateRestorer restorer;
    private final long[] tokens = new long[MAXIMUM_DEPTH];
    private int depth;
    private int overflowDepth;
    private long nextToken;
    private long entries;
    private long failures;
    private long restorationFailures;

    public LegacyGlIsland(RenderThreadGuard threadGuard, GlStateMirror mirror,
                          ModernBatchFlusher flusher, LegacyStateRestorer restorer) {
        if (threadGuard == null || mirror == null || flusher == null || restorer == null) {
            throw new IllegalArgumentException("legacy island dependencies");
        }
        this.threadGuard = threadGuard;
        this.mirror = mirror;
        this.flusher = flusher;
        this.restorer = restorer;
    }

    public <T> T call(Callable<T> legacyCode) throws Exception {
        if (legacyCode == null) throw new IllegalArgumentException("legacyCode");
        long token = enter();
        Throwable failure = null;
        try {
            return legacyCode.call();
        } catch (Exception error) {
            failure = error;
            FatalErrors.rethrowIfFatal(error);
            throw error;
        } catch (Error error) {
            failure = error;
            throw error;
        } finally {
            Throwable cleanupFailure = null;
            try { exit(token, failure); }
            catch (Throwable error) { cleanupFailure = error; }
            if (cleanupFailure != null) {
                Throwable combined = appendFailure(failure, cleanupFailure);
                if (failure == null || combined != failure) rethrow(combined);
            }
        }
    }

    /** Split-phase entry for bytecode wrappers that must invoke original code. */
    public long enter() {
        threadGuard.check();
        long token = nextToken();
        if (depth >= tokens.length) {
            overflowDepth++;
            restorationFailures++;
            mirror.invalidateAll();
            return -token;
        }
        boolean outer = depth == 0;
        tokens[depth++] = token;
        if (outer) prepareOuterEntry();
        return token;
    }

    /** Split-phase exit; the original callback error is observed, never replaced. */
    public void exit(long token, Throwable originalError) {
        threadGuard.check();
        if (originalError != null) failures++;
        if (token < 0L) {
            if (overflowDepth > 0) overflowDepth--;
            else recoverMismatch();
            return;
        }
        if (depth == 0 || tokens[depth - 1] != token) {
            recoverMismatch();
            return;
        }
        tokens[--depth] = 0L;
        if (depth == 0) mirror.invalidateAll();
    }

    public void run(final Runnable legacyCode) {
        if (legacyCode == null) throw new IllegalArgumentException("legacyCode");
        try {
            call(new Callable<Void>() {
                @Override public Void call() {
                    legacyCode.run();
                    return null;
                }
            });
        } catch (RuntimeException error) {
            throw error;
        } catch (Error error) {
            throw error;
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public boolean isInside() { return depth > 0 || overflowDepth > 0; }
    public long getEntries() { return entries; }
    public long getFailures() { return failures; }
    public long getRestorationFailures() { return restorationFailures; }

    private void prepareOuterEntry() {
        try { flusher.flush(); }
        catch (Throwable internalFailure) {
            FatalErrors.rethrowIfFatal(internalFailure);
            restorationFailures++;
            mirror.invalidateAll();
        }
        try { restorer.restoreLegacyCallSiteState(); }
        catch (Throwable internalFailure) {
            FatalErrors.rethrowIfFatal(internalFailure);
            // ICE's inability to reconstruct a call-site state must never
            // suppress or replace the original mod/Forge callback.  The
            // caller remains on the untouched legacy path and the mirror
            // is unknown until a certified modern state is rebound.
            restorationFailures++;
            mirror.invalidateAll();
        }
        entries++;
    }

    private void recoverMismatch() {
        restorationFailures++;
        while (depth > 0) tokens[--depth] = 0L;
        overflowDepth = 0;
        mirror.invalidateAll();
    }

    private long nextToken() {
        nextToken = checkedNextToken(nextToken);
        return nextToken;
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

    private static void rethrow(Throwable failure) throws Exception {
        FatalErrors.rethrowIfFatal(failure);
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure instanceof Error) throw (Error) failure;
        throw new IllegalStateException("Legacy GL island cleanup failed",
            failure);
    }

    static long checkedNextToken(long current) {
        if (current < 0L || current == Long.MAX_VALUE) {
            throw new IllegalStateException("Legacy GL island token exhausted");
        }
        return current + 1L;
    }
}
