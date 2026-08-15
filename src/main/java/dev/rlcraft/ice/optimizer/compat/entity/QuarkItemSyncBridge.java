package dev.rlcraft.ice.optimizer.compat.entity;

import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.entity.item.EntityItem;

/** Mutable, unboxed server-side state for Quark's dropped-item update detector. */
public final class QuarkItemSyncBridge {
    public static final int USE_ORIGINAL = -1;
    public static final int NO_SYNC = 0;
    public static final int SYNC = 1;
    private static final String MODULE = "quark-item-sync";
    private static final Map<EntityItem, State> STATES = new WeakHashMap<EntityItem, State>();
    private static volatile boolean activated;

    private QuarkItemSyncBridge() {
    }

    public static int decision(EntityItem item, int age, int lifespan) {
        if (item == null) return USE_ORIGINAL;
        try {
            if (!OptimizerBridge.isEnabled(MODULE)) return USE_ORIGINAL;
            synchronized (STATES) {
                State previous = STATES.get(item);
                if (previous == null) {
                    STATES.put(item, new State(age, lifespan));
                    activateOnce();
                    return NO_SYNC;
                }
                boolean ageChanged = age != previous.age && age != previous.age + 1;
                boolean lifespanChanged = lifespan != previous.lifespan;
                if (!ageChanged) previous.age = age;
                activateOnce();
                return ageChanged || lifespanChanged ? SYNC : NO_SYNC;
            }
        } catch (Throwable error) {
            fail(error);
            return USE_ORIGINAL;
        }
    }

    static void clearForTests() {
        synchronized (STATES) {
            STATES.clear();
        }
    }

    private static void activateOnce() {
        if (activated) return;
        synchronized (QuarkItemSyncBridge.class) {
            if (activated) return;
            OptimizerBridge.activate(MODULE,
                "Quark 掉落物年龄与寿命同步检测已合并为可变弱状态，无每 Tick Integer 装箱");
            activated = true;
        }
    }

    private static void fail(Throwable error) {
        try {
            OptimizerBridge.failure(MODULE, error);
        } catch (Throwable ignored) {
        }
    }

    private static final class State {
        private int age;
        private final int lifespan;

        private State(int age, int lifespan) {
            this.age = age;
            this.lifespan = lifespan;
        }
    }
}
