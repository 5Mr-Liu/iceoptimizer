package dev.rlcraft.ice.optimizer.client;

import dev.rlcraft.ice.optimizer.FatalErrors;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GLContext;

/**
 * Detects render generations from observable runtime state only. It never
 * reads GPU names, vendors, CPU counts, or integrated/discrete labels.
 */
final class RenderLifecycleMonitor {
    private final ContextLifecycleTracker contexts = new ContextLifecycleTracker();
    private int displayWidth = -1;
    private int displayHeight = -1;
    private int viewDistance = -1;
    private int vertexStride = -1;
    private boolean fullscreen;
    private boolean sawDisplay;
    private final OptifineShaderProbe shaders = new OptifineShaderProbe();

    void beforeFrame(Minecraft minecraft, ClientOptimizerRuntime runtime) {
        if (minecraft == null || runtime == null) return;
        ContextCapabilities current = null;
        try { current = GLContext.getCapabilities(); }
        catch (Throwable failure) { FatalErrors.rethrowIfFatal(failure); }
        if (contexts.observe(current)) runtime.glContextReset();

        int width = Math.max(0, minecraft.displayWidth);
        int height = Math.max(0, minecraft.displayHeight);
        int distance = minecraft.gameSettings == null ? 0
            : minecraft.gameSettings.renderDistanceChunks;
        boolean nowFullscreen = false;
        try { nowFullscreen = Display.isFullscreen(); }
        catch (Throwable failure) { FatalErrors.rethrowIfFatal(failure); }
        if (sawDisplay && (width != displayWidth || height != displayHeight
            || distance != viewDistance || nowFullscreen != fullscreen)) {
            runtime.viewFrustumChanged();
        }
        displayWidth = width;
        displayHeight = height;
        viewDistance = distance;
        fullscreen = nowFullscreen;
        sawDisplay = true;

        int stride = 0;
        try { stride = DefaultVertexFormats.BLOCK.getSize(); }
        catch (Throwable failure) { FatalErrors.rethrowIfFatal(failure); }
        if (stride > 0) {
            if (vertexStride > 0 && stride != vertexStride) runtime.vertexFormatChanged();
            vertexStride = stride;
        }
    }

    void pollShaderPack(ClientOptimizerRuntime runtime) {
        if (runtime == null) return;
        boolean changed = shaders.changed();
        runtime.shaderPackStateObserved(shaders.isKnown(), shaders.isActive());
        if (changed) runtime.shaderPackChanged();
    }

    private static final class OptifineShaderProbe {
        private boolean resolved;
        private boolean unavailable;
        private Field shaderPackField;
        private Field loadedField;
        private Method nameMethod;
        private Object lastPack;
        private String lastName = "";
        private boolean lastLoaded;
        private boolean initialized;
        private boolean known;
        private boolean active;
        private boolean absent;

        private boolean changed() {
            resolve();
            if (unavailable) {
                known = absent;
                active = false;
                return false;
            }
            try {
                Object pack = shaderPackField == null ? null : shaderPackField.get(null);
                boolean loaded = loadedField != null && loadedField.getBoolean(null);
                String name = shaderName(pack);
                boolean changed = initialized && (pack != lastPack || loaded != lastLoaded
                    || !name.equals(lastName));
                initialized = true;
                lastPack = pack;
                lastLoaded = loaded;
                lastName = name;
                known = true;
                active = loaded;
                return changed;
            } catch (Throwable error) {
                FatalErrors.rethrowIfFatal(error);
                unavailable = true;
                known = false;
                active = false;
                return false;
            }
        }

        private boolean isKnown() { return known; }
        private boolean isActive() { return active; }

        private void resolve() {
            if (resolved) return;
            resolved = true;
            try {
                Class<?> type = Class.forName("net.optifine.shaders.Shaders", false,
                    RenderLifecycleMonitor.class.getClassLoader());
                shaderPackField = findStaticField(type, "shaderPack");
                loadedField = findStaticBoolean(type, "shaderPackLoaded");
                if (loadedField == null) unavailable = true;
            } catch (ClassNotFoundException absentClass) {
                absent = true;
                unavailable = true;
            } catch (Throwable absent) {
                FatalErrors.rethrowIfFatal(absent);
                unavailable = true;
            }
        }

        private String shaderName(Object pack) {
            if (pack == null) return "";
            try {
                Method method = nameMethod;
                if (method == null || !method.getDeclaringClass().isInstance(pack)) {
                    method = pack.getClass().getMethod("getName");
                    method.setAccessible(true);
                    nameMethod = method;
                }
                Object value = method.invoke(pack);
                return value == null ? "" : String.valueOf(value);
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                // Class plus identity is represented by the separately compared
                // object reference; avoid invoking arbitrary toString methods.
                return pack.getClass().getName();
            }
        }

        private static Field findStaticField(Class<?> type, String name) {
            try {
                Field field = type.getDeclaredField(name);
                if (!Modifier.isStatic(field.getModifiers())) return null;
                field.setAccessible(true);
                return field;
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                return null;
            }
        }

        private static Field findStaticBoolean(Class<?> type, String name) {
            Field field = findStaticField(type, name);
            return field != null && field.getType() == Boolean.TYPE ? field : null;
        }
    }
}
