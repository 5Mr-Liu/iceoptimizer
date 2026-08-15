package dev.rlcraft.ice.hooks;

import java.lang.reflect.Method;

/** Optional late-bound link from the early CoreMod class loader to the main runtime. */
final class OptimizerRegistryLink {
    private static final String REGISTRY = "dev.rlcraft.ice.optimizer.OptimizerRegistry";

    private OptimizerRegistryLink() {
    }

    static boolean targetObserved(String moduleId, String className, String fingerprint, boolean supported) {
        return invoke("targetObserved", new Class<?>[] { String.class, String.class, String.class, Boolean.TYPE },
            new Object[] { moduleId, className, fingerprint, Boolean.valueOf(supported) });
    }

    static boolean patchInstalled(String moduleId, String className, String fingerprint) {
        return invoke("patchInstalled", new Class<?>[] { String.class, String.class, String.class },
            new Object[] { moduleId, className, fingerprint });
    }

    private static boolean invoke(String name, Class<?>[] parameterTypes, Object[] arguments) {
        try {
            ClassLoader loader = OptimizerRegistryLink.class.getClassLoader();
            Class<?> registry = Class.forName(REGISTRY, true, loader);
            Method method = registry.getMethod(name, parameterTypes);
            method.invoke(null, arguments);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
