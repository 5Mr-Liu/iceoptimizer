package dev.rlcraft.ice.hooks;

import java.lang.reflect.Method;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Optional startup-only link to Mixin; normal Forge-only startup does not resolve its API classes. */
final class SrpMixinExtensionRegistrar {
    private static final Logger LOGGER = LogManager.getLogger("ICE Optimizer");
    private static boolean registered;

    private SrpMixinExtensionRegistrar() { }

    static void register() {
        if (registered) return;
        ClassLoader loader = SrpMixinExtensionRegistrar.class.getClassLoader();
        if (loader.getResource("srpmixins/mixin/deterrenttweaks/InfestationOverhaul.class") == null) return;
        try {
            Class<?> environmentType = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment", false, loader);
            Object environment = environmentType.getMethod("getCurrentEnvironment").invoke(null);
            Object transformer = environmentType.getMethod("getActiveTransformer").invoke(environment);
            if (transformer == null) {
                LOGGER.warn("ICE SRP post-mixin optimization could not register: Mixin has no active transformer");
                return;
            }
            Method extensionsMethod = transformer.getClass().getMethod("getExtensions");
            extensionsMethod.setAccessible(true); // MixinTransformer itself is package-private.
            Object extensions = extensionsMethod.invoke(transformer);
            Class<?> extensionType = Class.forName("org.spongepowered.asm.mixin.transformer.ext.IExtension", false, loader);
            Object extension = Class.forName("dev.rlcraft.ice.hooks.SrpPostMixinExtension", true, loader)
                .getConstructor().newInstance();
            extensions.getClass().getMethod("add", extensionType).invoke(extensions, extension);
            // Some early mixins may already have selected their extensions for this phase.
            extensions.getClass().getMethod("select", environmentType).invoke(extensions, environment);
            registered = true;
            LOGGER.info("ICE registered SRP optimization in Mixin postApply");
        } catch (ClassNotFoundException absent) {
            // Mixin remains an optional integration, never a hard startup dependency.
        } catch (Throwable error) {
            HookFatalErrors.rethrowIfFatal(error);
            LOGGER.error("ICE could not register SRP post-mixin optimization", error);
        }
    }
}
