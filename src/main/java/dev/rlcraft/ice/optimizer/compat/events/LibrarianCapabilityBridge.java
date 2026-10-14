package dev.rlcraft.ice.optimizer.compat.events;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.util.Map;

/** Class names are immutable; provider registrations and field flags remain live. */
public final class LibrarianCapabilityBridge {
    private static final ClassValue<String> NAMES = new ClassValue<String>() {
        @Override protected String computeValue(Class<?> type) { return type.getName().intern(); }
    };
    private LibrarianCapabilityBridge() { }
    public static boolean isEnabled() {
        return OptimizerBridge.isEnabled(OptimizationModule.LIBRARIAN_CAPABILITY_QUERY.ordinal());
    }
    public static boolean matches(Class<?>[] interfaces, Map<?, ?> providers, Object capability) {
        for (Class<?> type : interfaces) {
            Object provider = providers.get(NAMES.get(type));
            if (provider == capability || provider != null && provider.equals(capability)) return true;
        }
        return false;
    }
}
