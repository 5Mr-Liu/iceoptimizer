package dev.rlcraft.ice.optimizer.compat.chunk;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;

/**
 * One-entry, per-thread memo for the repeated side queries issued while one
 * Dynamic Trees block is baked. Inputs are compared by identity and radius.
 */
public final class DynamicTreesConnectionBridge {
    private static final String MODULE = "chunk-mesh-dynamic-trees";
    private static final ThreadLocal<Entry> LAST = new ThreadLocal<Entry>();
    private static volatile boolean activated;

    private DynamicTreesConnectionBridge() {
    }

    public static int[] lookup(Object model, int radius, Object extendedState) {
        try {
            if (!OptimizerBridge.isEnabled(MODULE)) return null;
            Entry entry = LAST.get();
            if (entry == null || entry.model != model || entry.extendedState != extendedState
                || entry.radius != radius || entry.connections == null
                || entry.connections.length != 6) return null;
            activateOnce();
            return entry.connections;
        } catch (Throwable error) {
            LAST.remove();
            fail(error);
            return null;
        }
    }

    public static int[] remember(Object model, int radius, Object extendedState, int[] connections) {
        if (connections == null || connections.length != 6) return connections;
        try {
            if (!OptimizerBridge.isEnabled(MODULE)) return connections;
            Entry entry = LAST.get();
            if (entry == null) {
                entry = new Entry();
                LAST.set(entry);
            }
            entry.model = model;
            entry.radius = radius;
            entry.extendedState = extendedState;
            entry.connections = connections;
        } catch (Throwable error) {
            LAST.remove();
            fail(error);
        }
        return connections;
    }

    private static void activateOnce() {
        if (activated) return;
        synchronized (DynamicTreesConnectionBridge.class) {
            if (activated) return;
            OptimizerBridge.activate(MODULE,
                "Dynamic Trees 同一扩展方块状态的六向连接数据已在各面查询间复用");
            activated = true;
        }
    }

    private static void fail(Throwable error) {
        try {
            OptimizerBridge.failure(MODULE, error);
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
        }
    }

    private static final class Entry {
        private Object model;
        private int radius;
        private Object extendedState;
        private int[] connections;
    }
}
