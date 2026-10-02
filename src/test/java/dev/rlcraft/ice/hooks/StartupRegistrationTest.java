package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.Map;
import org.junit.Test;

/** Loading the packaged classes must not impersonate Forge plugin registration. */
public class StartupRegistrationTest {
    @Test
    public void pluginLifecycleAndTransformerConstructionAreBothRequired() throws Exception {
        verifyRegistration(false);
        verifyRegistration(true);
    }

    private void verifyRegistration(boolean transformerFirst) throws Exception {
        URL hooks = OptimizerPatchJournal.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader loader = new URLClassLoader(new URL[] { hooks }, getClass().getClassLoader()) {
            @Override protected synchronized Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
                if (!name.startsWith("dev.rlcraft.ice.hooks.")) return super.loadClass(name, resolve);
                Class<?> type = findLoadedClass(name);
                if (type == null) type = findClass(name);
                if (resolve) resolveClass(type);
                return type;
            }
        }) {
            Class<?> journal = loader.loadClass("dev.rlcraft.ice.hooks.OptimizerPatchJournal");
            assertFalse(registered(journal));
            Object plugin = loader.loadClass("dev.rlcraft.ice.hooks.IceOptimizerLoadingPlugin").newInstance();
            plugin.getClass().getMethod("getASMTransformerClass").invoke(plugin);
            assertFalse("declaring a transformer does not register it", registered(journal));
            if (transformerFirst) {
                loader.loadClass("dev.rlcraft.ice.hooks.IceOptimizerTransformer").newInstance();
            } else {
                plugin.getClass().getMethod("injectData", Map.class).invoke(plugin, Collections.emptyMap());
            }
            assertFalse("a partial Forge startup must remain inactive", registered(journal));
            if (transformerFirst) {
                plugin.getClass().getMethod("injectData", Map.class).invoke(plugin, Collections.emptyMap());
            } else {
                loader.loadClass("dev.rlcraft.ice.hooks.IceOptimizerTransformer").newInstance();
            }
            assertTrue(registered(journal));
        }
    }

    private static boolean registered(Class<?> journal) throws Exception {
        return Boolean.TRUE.equals(journal.getMethod("isStartupRegistered").invoke(null));
    }
}
