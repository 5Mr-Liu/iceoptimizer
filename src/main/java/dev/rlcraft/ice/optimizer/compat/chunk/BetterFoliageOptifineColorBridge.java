package dev.rlcraft.ice.optimizer.compat.chunk;

import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.lang.reflect.Field;

/** Resolves OptiFine's injected GameSettings color flag once per runtime class. */
public final class BetterFoliageOptifineColorBridge {
    private static final String MODULE = "betterfoliage-optifine-colors";
    private static final String FIELD_NAME = "ofCustomColors";
    private static final ClassValue<Accessor> FIELDS = new ClassValue<Accessor>() {
        @Override
        protected Accessor computeValue(Class<?> type) {
            return new Accessor(find(type));
        }
    };
    private static volatile boolean activated;

    private BetterFoliageOptifineColorBridge() {
    }

    public static boolean isCustomColorsEnabled(Object gameSettings) {
        if (gameSettings == null) return false;
        try {
            if (!OptimizerBridge.isEnabled(MODULE)) return readUncached(gameSettings);
            Field field = FIELDS.get(gameSettings.getClass()).field;
            if (field == null) return false;
            boolean enabled = Boolean.TRUE.equals(field.get(gameSettings));
            activateOnce();
            return enabled;
        } catch (Throwable error) {
            fail(error);
            return false;
        }
    }

    private static boolean readUncached(Object settings) {
        try {
            Field field = find(settings.getClass());
            return field != null && Boolean.TRUE.equals(field.get(settings));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Field find(Class<?> type) {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(FIELD_NAME);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static void activateOnce() {
        if (activated) return;
        synchronized (BetterFoliageOptifineColorBridge.class) {
            if (activated) return;
            OptimizerBridge.activate(MODULE,
                "Better Foliage 已缓存 OptiFine 自定义颜色字段，Chunk Worker 不再逐方块查找反射字段");
            activated = true;
        }
    }

    private static void fail(Throwable error) {
        try {
            OptimizerBridge.failure(MODULE, error);
        } catch (Throwable ignored) {
        }
    }

    private static final class Accessor {
        private final Field field;

        private Accessor(Field field) {
            this.field = field;
        }
    }
}
