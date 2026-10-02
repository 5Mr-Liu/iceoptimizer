package dev.rlcraft.ice.profiler.report;

import dev.rlcraft.ice.profiler.FatalErrors;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Optional, reflection-only bridge from the profiler JAR to the optimizer.
 * The two release JARs intentionally share no classes or compile-time ABI.
 */
final class OptimizerRendererDiagnosticsReader {
    private static final String DIAGNOSTICS_CLASS =
        "dev.rlcraft.ice.optimizer.client.ModernRendererDiagnostics";
    private static final Snapshot EMPTY = new Snapshot("", "");

    private OptimizerRendererDiagnosticsReader() {
    }

    static Snapshot read() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        Snapshot value = read(context, DIAGNOSTICS_CLASS);
        if (!value.isEmpty()) return value;
        ClassLoader own = OptimizerRendererDiagnosticsReader.class.getClassLoader();
        return own == context ? value : read(own, DIAGNOSTICS_CLASS);
    }

    static Snapshot read(ClassLoader loader, String className) {
        if (loader == null || className == null || className.isEmpty()) return EMPTY;
        try {
            Class<?> type = Class.forName(className, false, loader);
            Method report = stringStaticMethod(type, "report");
            Method summary = stringStaticMethod(type, "summary");
            if (report == null || summary == null) return EMPTY;
            String reportText = (String) report.invoke(null);
            String summaryText = (String) summary.invoke(null);
            return new Snapshot(bounded(reportText, 65536),
                bounded(summaryText, 512));
        } catch (Throwable error) {
            FatalErrors.rethrowIfFatal(error);
            return EMPTY;
        }
    }

    private static Method stringStaticMethod(Class<?> type, String name) {
        try {
            Method method = type.getMethod(name);
            int modifiers = method.getModifiers();
            return method.getParameterTypes().length == 0
                && method.getReturnType() == String.class
                && Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers)
                    ? method : null;
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static String bounded(String value, int maximum) {
        if (value == null || value.isEmpty()) return "";
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    static final class Snapshot {
        private final String report;
        private final String summary;

        private Snapshot(String report, String summary) {
            this.report = report == null ? "" : report;
            this.summary = summary == null ? "" : summary;
        }

        String getReport() { return report; }
        String getSummary() { return summary; }
        boolean isEmpty() { return report.isEmpty() && summary.isEmpty(); }
    }
}
