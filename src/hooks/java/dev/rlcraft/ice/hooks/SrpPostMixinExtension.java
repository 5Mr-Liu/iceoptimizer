package dev.rlcraft.ice.hooks;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;

/** Runs after all mixins for the target have merged, independently of LaunchWrapper proxy order. */
public final class SrpPostMixinExtension implements IExtension {
    private static final Logger LOGGER = LogManager.getLogger("ICE Optimizer");

    @Override public boolean checkActive(MixinEnvironment environment) { return true; }
    @Override public void preApply(ITargetClassContext context) { }
    @Override public void export(MixinEnvironment environment, String name, boolean force, ClassNode node) { }

    @Override public void postApply(ITargetClassContext context) {
        ClassNode node = context.getClassNode();
        if (!SrpInfestationCacheAdapter.TARGET.equals(node.name)) return;
        try {
            ClassWriter before = new ClassWriter(0);
            node.accept(before);
            String fingerprint = CoreClassFingerprint.sha256(before.toByteArray());
            int changes = SrpInfestationCacheAdapter.patch(node);
            if (changes == 0) return;
            String target = node.name.replace('/', '.');
            OptimizerPatchJournal.targetObserved("srp-infestation-cache", target, fingerprint, true);
            OptimizerPatchJournal.patchInstalled("srp-infestation-cache", target, fingerprint);
            LOGGER.info("ICE installed SRP infestation name caches after Mixin merge: {} helpers", changes);
        } catch (Throwable error) {
            HookFatalErrors.rethrowIfFatal(error);
            LOGGER.error("ICE could not install SRP infestation cache after Mixin merge", error);
        }
    }
}
