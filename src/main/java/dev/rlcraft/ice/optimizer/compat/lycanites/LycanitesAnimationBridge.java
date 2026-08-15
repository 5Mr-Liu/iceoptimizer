package dev.rlcraft.ice.optimizer.compat.lycanites;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.util.Locale;
import org.lwjgl.opengl.GL11;

/** Small exact fast paths shared by the reviewed Lycanites model adapters. */
public final class LycanitesAnimationBridge {
    private static final String MODULE = "lycanites-model-animation";
    private static final Cache<String, String> LOWERCASE =
        Caffeine.newBuilder().maximumSize(4096L).build();
    private static volatile Locale lowercaseLocale = Locale.getDefault();
    private static volatile boolean activated;
    private static volatile boolean recoveryPending;

    private LycanitesAnimationBridge() {
    }

    public static boolean useFastAnimation() {
        boolean enabled = OptimizerBridge.isEnabled(MODULE);
        if (enabled) activate("Lycanites 模型动画遍历与类型分派已启用");
        return enabled;
    }

    public static int classifyFrame(String type) {
        if ("angle".equals(type)) return 1;
        if ("rotate".equals(type)) return 2;
        if ("translate".equals(type)) return 3;
        if ("scale".equals(type)) return 4;
        return 0;
    }

    public static String lower(String value) {
        if (!OptimizerBridge.isEnabled(MODULE)) return value.toLowerCase();
        try {
            final Locale current = Locale.getDefault();
            if (!current.equals(lowercaseLocale)) {
                synchronized (LOWERCASE) {
                    if (!current.equals(lowercaseLocale)) {
                        LOWERCASE.invalidateAll();
                        lowercaseLocale = current;
                    }
                }
            }
            String result = LOWERCASE.get(value, key -> key.toLowerCase(current));
            activate("Lycanites 模型部件名称已进入有界 Locale 感知缓存");
            recoverIfNeeded();
            return result;
        } catch (Throwable error) {
            recoveryPending = true;
            OptimizerBridge.failure(MODULE, error);
            return value.toLowerCase();
        }
    }

    public static void angle(float angle, float x, float y, float z) {
        if (OptimizerBridge.isEnabled(MODULE) && zero(angle) && !allZero(x, y, z)) {
            activate("Lycanites 恒等旋转/平移/缩放提交已消除");
            return;
        }
        GL11.glRotatef(angle, x, y, z);
    }

    public static void rotate(float x, float y, float z) {
        boolean enabled = OptimizerBridge.isEnabled(MODULE);
        boolean skipped = false;
        if (!enabled || !zero(x)) GL11.glRotatef(x, 1.0F, 0.0F, 0.0F); else skipped = true;
        if (!enabled || !zero(y)) GL11.glRotatef(y, 0.0F, 1.0F, 0.0F); else skipped = true;
        if (!enabled || !zero(z)) GL11.glRotatef(z, 0.0F, 0.0F, 1.0F); else skipped = true;
        if (skipped) activate("Lycanites 恒等旋转/平移/缩放提交已消除");
    }

    public static void translate(float x, float y, float z) {
        if (OptimizerBridge.isEnabled(MODULE) && allZero(x, y, z)) {
            activate("Lycanites 恒等旋转/平移/缩放提交已消除");
            return;
        }
        GL11.glTranslatef(x, y, z);
    }

    public static void scale(float x, float y, float z) {
        if (OptimizerBridge.isEnabled(MODULE)
            && Float.floatToRawIntBits(x) == Float.floatToRawIntBits(1.0F)
            && Float.floatToRawIntBits(y) == Float.floatToRawIntBits(1.0F)
            && Float.floatToRawIntBits(z) == Float.floatToRawIntBits(1.0F)) {
            activate("Lycanites 恒等旋转/平移/缩放提交已消除");
            return;
        }
        GL11.glScalef(x, y, z);
    }

    private static boolean allZero(float x, float y, float z) {
        return zero(x) && zero(y) && zero(z);
    }

    private static boolean zero(float value) {
        return (Float.floatToRawIntBits(value) & 0x7fffffff) == 0;
    }

    private static void activate(String detail) {
        if (!activated) {
            activated = true;
            OptimizerBridge.activate(MODULE, detail);
        }
    }

    private static void recoverIfNeeded() {
        if (recoveryPending) {
            recoveryPending = false;
            OptimizerBridge.success(MODULE);
        }
    }
}
