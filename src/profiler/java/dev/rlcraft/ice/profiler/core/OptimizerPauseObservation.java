package dev.rlcraft.ice.profiler.core;

import dev.rlcraft.ice.profiler.FatalErrors;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/** One startup lookup keeps both installation JARs independent. */
final class OptimizerPauseObservation {
    private Method subscription;

    void connect(Consumer<Boolean> observer) {
        try {
            Class<?> registry = Class.forName(
                "dev.rlcraft.ice.optimizer.OptimizerRegistry", false,
                OptimizerPauseObservation.class.getClassLoader());
            subscription = registry.getMethod("observeRuntimePause", Consumer.class);
            subscription.invoke(null, observer);
        } catch (ClassNotFoundException absent) {
            // Profiler-only installation.
        } catch (NoSuchMethodException olderOptimizer) {
            // A recorder can also be installed alongside an earlier optimizer.
        } catch (Throwable error) {
            FatalErrors.rethrowIfFatal(error);
            throw new IllegalStateException("optimizer pause subscription", error);
        }
    }

    void disconnect() {
        if (subscription == null) return;
        try {
            subscription.invoke(null, new Object[] { null });
        } catch (Throwable error) {
            FatalErrors.rethrowIfFatal(error);
            throw new IllegalStateException("optimizer pause unsubscribe", error);
        } finally {
            subscription = null;
        }
    }
}
