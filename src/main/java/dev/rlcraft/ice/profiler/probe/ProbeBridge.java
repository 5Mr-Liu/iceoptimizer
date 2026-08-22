package dev.rlcraft.ice.profiler.probe;

import dev.rlcraft.ice.config.IceConfig;
import dev.rlcraft.ice.profiler.FatalErrors;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

public final class ProbeBridge {
    private static final int MAX_NESTING = 32;
    private static final int MAX_PROBE_ID = 64;
    private static final ConcurrentHashMap<ProbeKey, Accumulator> VALUES = new ConcurrentHashMap<ProbeKey, Accumulator>();
    private static final AtomicIntegerArray SUBJECT_COUNTS = new AtomicIntegerArray(MAX_PROBE_ID);
    private static final ThreadLocal<SpanState> STATE = new ThreadLocal<SpanState>() {
        @Override protected SpanState initialValue() { return new SpanState(); }
    };
    private static volatile boolean enabled;

    private ProbeBridge() {
    }

    public static void setEnabled(boolean value) { enabled = value; }
    public static boolean isEnabled() { return enabled && IceConfig.probes.deepProfiling; }

    /** Called by optional bytecode hooks. It never mutates the supplied subject. */
    public static long enter(int probeId, Object subject) {
        try {
            if (!isEnabled() || probeId <= 0 || probeId >= MAX_PROBE_ID) return 0L;
            SpanState state = STATE.get();
            return enterAccepted(state, probeId, subject == null
                ? "<none>" : subject.getClass().getName(), false);
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            return 0L;
        }
    }

    /** Named variant for generated Forge event wrappers, whose readable name already identifies the listener method. */
    public static long enterNamed(int probeId, String subjectName) {
        try {
            if (probeId <= 0 || probeId >= MAX_PROBE_ID || !enabled) return 0L;
            SpanState state = STATE.get();
            if (!IceConfig.probes.deepProfiling
                && !(state.rareDepth > 0 && isRareScopedProbe(probeId))) {
                return 0L;
            }
            return enterAccepted(state, probeId,
                subjectName == null || subjectName.isEmpty()
                    ? "<unnamed>" : subjectName, false);
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            return 0L;
        }
    }

    /**
     * Opens an always-low-frequency diagnostic scope. Unlike deep probes this
     * is allowed while passive monitoring is active because item completion
     * occurs at human interaction frequency, not once per entity or event.
     */
    public static long enterRareNamed(int probeId, String subjectName) {
        try {
            if (!enabled || probeId <= 0 || probeId >= MAX_PROBE_ID) return 0L;
            return enterAccepted(STATE.get(), probeId,
                subjectName == null || subjectName.isEmpty()
                    ? "<unnamed>" : subjectName, true);
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            return 0L;
        }
    }

    /** Entry point used by the exact EntityLivingBase completion hook. */
    public static long enterItemFinish(Object entity) {
        if (!enabled) return 0L;
        return enterRareNamed(ProbeIds.ITEM_USE_FINISH,
            itemFinishSubject(entity));
    }

    /** Called by optional bytecode hooks from a finally path. Invalid tokens are ignored (fail-open). */
    public static void exit(long token) {
        if (token == 0L) return;
        try {
            SpanState state = STATE.get();
            int slot = (int) ((token >>> 32) - 1L);
            if (slot < 0 || slot >= state.depth || slot >= MAX_NESTING) return;
            long elapsed = Math.max(0L, System.nanoTime() - state.started[slot]);
            int probeId = state.probeIds[slot];
            String subject = state.subjects[slot];
            int oldDepth = state.depth;
            state.depth = slot;
            for (int index = slot; index < oldDepth; index++) {
                state.subjects[index] = null;
                state.rareScopes[index] = false;
            }
            state.rareDepth = 0;
            for (int index = 0; index < slot; index++) {
                if (state.rareScopes[index]) state.rareDepth++;
            }
            record(probeId, subject, elapsed);
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            // Exact hooks are observational and must never affect the instrumented call.
        }
    }

    public static List<ProbeMetric> drain() {
        if (VALUES.isEmpty()) return Collections.emptyList();
        List<ProbeMetric> result = new ArrayList<ProbeMetric>();
        for (Map.Entry<ProbeKey, Accumulator> entry : VALUES.entrySet()) {
            Accumulator accumulator = entry.getValue();
            long calls = accumulator.calls.getAndSet(0L);
            long total = accumulator.total.getAndSet(0L);
            long maximum = accumulator.maximum.getAndSet(0L);
            if (calls > 0L) result.add(new ProbeMetric(entry.getKey().probeId, entry.getKey().subject, calls, total, maximum));
        }
        Collections.sort(result, new Comparator<ProbeMetric>() {
            @Override public int compare(ProbeMetric left, ProbeMetric right) {
                return Long.compare(right.getTotalNanos(), left.getTotalNanos());
            }
        });
        return result;
    }

    private static void record(int probeId, String subject, long nanos) {
        ProbeKey key = new ProbeKey(probeId, subject);
        Accumulator accumulator = VALUES.get(key);
        if (accumulator == null) {
            int count = SUBJECT_COUNTS.incrementAndGet(probeId);
            if (count > IceConfig.probes.maxSubjectsPerProbe) {
                SUBJECT_COUNTS.decrementAndGet(probeId);
                key = new ProbeKey(probeId, "<other>");
            }
            Accumulator created = new Accumulator();
            Accumulator raced = VALUES.putIfAbsent(key, created);
            accumulator = raced == null ? created : raced;
        }
        accumulator.calls.incrementAndGet();
        accumulator.total.addAndGet(nanos);
        long previous = accumulator.maximum.get();
        while (nanos > previous && !accumulator.maximum.compareAndSet(previous, nanos)) previous = accumulator.maximum.get();
    }

    private static long enterAccepted(SpanState state, int probeId,
                                      String subject, boolean rare) {
        if (state == null || state.depth >= MAX_NESTING) return 0L;
        int slot = state.depth++;
        state.started[slot] = System.nanoTime();
        state.probeIds[slot] = probeId;
        state.subjects[slot] = subject;
        state.rareScopes[slot] = rare;
        if (rare) state.rareDepth++;
        return (((long) slot + 1L) << 32)
            | (Thread.currentThread().getId() & 0xffffffffL);
    }

    private static boolean isRareScopedProbe(int probeId) {
        return probeId == ProbeIds.EVENT_HANDLER
            || probeId == ProbeIds.POTION_ITEM_FINISH;
    }

    private static String itemFinishSubject(Object entity) {
        String fallback = entity == null ? "<none>" : entity.getClass().getName();
        if (!(entity instanceof EntityLivingBase)) return fallback;
        try {
            ItemStack stack = ((EntityLivingBase) entity).getActiveItemStack();
            if (stack == null || stack.isEmpty()) return fallback + "|<empty>";
            Item item = stack.getItem();
            if (item == null) return fallback + "|<null-item>";
            ResourceLocation registryName = item.getRegistryName();
            return (registryName == null ? item.getClass().getName()
                : registryName.toString()) + "|" + item.getClass().getName();
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            return fallback + "|<unresolved-item>";
        }
    }

    private static final class SpanState {
        private final long[] started = new long[MAX_NESTING];
        private final int[] probeIds = new int[MAX_NESTING];
        private final String[] subjects = new String[MAX_NESTING];
        private final boolean[] rareScopes = new boolean[MAX_NESTING];
        private int depth;
        private int rareDepth;
    }

    private static final class Accumulator {
        private final AtomicLong calls = new AtomicLong();
        private final AtomicLong total = new AtomicLong();
        private final AtomicLong maximum = new AtomicLong();
    }

    private static final class ProbeKey {
        private final int probeId;
        private final String subject;
        private final int hash;

        private ProbeKey(int probeId, String subject) {
            this.probeId = probeId;
            this.subject = subject == null ? "<none>" : subject;
            this.hash = 31 * probeId + this.subject.hashCode();
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof ProbeKey)) return false;
            ProbeKey key = (ProbeKey) other;
            return probeId == key.probeId && subject.equals(key.subject);
        }
    }
}
