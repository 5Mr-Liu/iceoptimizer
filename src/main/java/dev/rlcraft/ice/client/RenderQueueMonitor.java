package dev.rlcraft.ice.client;

import dev.rlcraft.ice.IceProfilerMod;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;

/** Fail-open queue metrics compatible with transformed RenderGlobal classes. */
final class RenderQueueMonitor {
    private static final Pattern COMPILE_QUEUE = Pattern.compile("(?:^|[,\\s])pC\\s*:\\s*(\\d+)");
    private static final Pattern UPLOAD_QUEUE = Pattern.compile("(?:^|[,\\s])pU\\s*:\\s*(\\d+)");
    private static final long RETRY_MILLIS = 10_000L;
    private Field dispatcherField;
    private Field updatesField;
    private Field uploadsField;
    private long retryAfterMillis;
    private boolean failureLogged;

    int[] read(Minecraft minecraft) {
        if (minecraft.renderGlobal == null || System.currentTimeMillis() < retryAfterMillis) {
            return new int[] { -1, -1 };
        }
        try {
            Object dispatcher = dispatcher(minecraft.renderGlobal);
            if (dispatcher == null) return new int[] { -1, -1 };

            int[] result = dispatcher instanceof ChunkRenderDispatcher
                ? parseDebugInfo(((ChunkRenderDispatcher) dispatcher).getDebugInfo())
                : new int[] { -1, -1 };
            if (result[0] < 0 || result[1] < 0) {
                initializeQueueFields(dispatcher.getClass());
                if (result[0] < 0 && updatesField != null) result[0] = size(updatesField.get(dispatcher));
                if (result[1] < 0 && uploadsField != null) result[1] = size(uploadsField.get(dispatcher));
            }
            if (result[0] >= 0 || result[1] >= 0) failureLogged = false;
            return result;
        } catch (Throwable error) {
            retryAfterMillis = System.currentTimeMillis() + RETRY_MILLIS;
            if (!failureLogged) {
                failureLogged = true;
                IceProfilerMod.LOGGER.debug("区块渲染队列指标暂时不可用，10 秒后重试", error);
            }
            return new int[] { -1, -1 };
        }
    }

    private Object dispatcher(Object renderGlobal) throws IllegalAccessException, NoSuchFieldException {
        if (dispatcherField == null || !dispatcherField.getDeclaringClass().isInstance(renderGlobal)) {
            dispatcherField = findNamedField(renderGlobal.getClass(), "renderDispatcher", "field_174995_M");
            if (dispatcherField == null) dispatcherField = findFieldByType(renderGlobal.getClass(), ChunkRenderDispatcher.class);
            if (dispatcherField == null) throw new NoSuchFieldException("RenderGlobal ChunkRenderDispatcher");
            dispatcherField.setAccessible(true);
        }
        return dispatcherField.get(renderGlobal);
    }

    private void initializeQueueFields(Class<?> dispatcherType) {
        if (updatesField == null) {
            updatesField = findNamedField(dispatcherType, "queueChunkUpdates", "field_178001_a");
            if (updatesField != null) updatesField.setAccessible(true);
        }
        if (uploadsField == null) {
            uploadsField = findNamedField(dispatcherType, "queueChunkUploads", "field_178000_b");
            if (uploadsField != null) uploadsField.setAccessible(true);
        }
    }

    static int[] parseDebugInfo(String value) {
        if (value == null) return new int[] { -1, -1 };
        return new int[] { parse(COMPILE_QUEUE, value), parse(UPLOAD_QUEUE, value) };
    }

    static int size(Object value) {
        if (value == null) return -1;
        if (value instanceof Collection) {
            synchronized (value) { return ((Collection<?>) value).size(); }
        }
        if (value instanceof Map) {
            synchronized (value) { return ((Map<?, ?>) value).size(); }
        }
        try {
            Method method = value.getClass().getMethod("size");
            method.setAccessible(true);
            Object result = method.invoke(value);
            return result instanceof Number ? ((Number) result).intValue() : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int parse(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        if (!matcher.find()) return -1;
        try { return Integer.parseInt(matcher.group(1)); }
        catch (NumberFormatException ignored) { return -1; }
    }

    private static Field findNamedField(Class<?> type, String... names) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (String name : names) {
                try { return current.getDeclaredField(name); }
                catch (NoSuchFieldException ignored) { }
            }
        }
        return null;
    }

    private static Field findFieldByType(Class<?> type, Class<?> wantedType) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (wantedType.isAssignableFrom(field.getType())) return field;
            }
        }
        return null;
    }
}
