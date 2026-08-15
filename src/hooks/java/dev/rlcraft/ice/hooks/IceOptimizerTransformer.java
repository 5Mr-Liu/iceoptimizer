package dev.rlcraft.ice.hooks;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.launchwrapper.IClassTransformer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;

/**
 * RLCraft optimizer transformer shared by physical clients and dedicated
 * servers. Whole-class hashes are audit metadata; the adapter's exact method
 * descriptors and instruction-graph checks are the execution gate.
 */
public final class IceOptimizerTransformer implements IClassTransformer {
    private static final Logger LOGGER = LogManager.getLogger("ICE Optimizer");
    private static final Set<String> REPORTED = Collections.synchronizedSet(new HashSet<String>());

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || transformedName == null) return basicClass;
        TargetSpec target = OptimizerTargetCatalog.find(transformedName);
        if (target == null) return basicClass;
        String fingerprint = CoreClassFingerprint.sha256(basicClass);
        OptimizerBytecodeAdapter adapter = OptimizerAdapterRegistry.find(target.adapterId);
        if (adapter == null) {
            for (String moduleId : target.moduleIds) {
                OptimizerPatchJournal.targetObserved(moduleId, transformedName, fingerprint, false);
            }
            LOGGER.error("ICE 目标 {} 的适配器 {} 尚未编入当前构建；保留原字节码",
                transformedName, target.adapterId);
            return basicClass;
        }
        boolean reviewedFingerprint = target.hasReviewedFingerprint(fingerprint);
        try {
            byte[] transformed = adapter.transform(transformedName, basicClass, target);
            if (transformed == null || transformed == basicClass) throw new IllegalStateException("适配器未生成新字节码");
            new ClassReader(transformed);
            for (String moduleId : target.moduleIds) {
                OptimizerPatchJournal.targetObserved(moduleId, transformedName, fingerprint, true);
                OptimizerPatchJournal.patchInstalled(moduleId, transformedName, fingerprint);
            }
            if (reviewedFingerprint) {
                LOGGER.info("ICE 已安装 {}：{} @ {}（已审查指纹）",
                    target.adapterId, transformedName, fingerprint);
            } else {
                LOGGER.info("ICE 已安装 {}：{} @ {}（兼容结构验证通过）",
                    target.adapterId, transformedName, fingerprint);
            }
            return transformed;
        } catch (Throwable error) {
            for (String moduleId : target.moduleIds) {
                OptimizerPatchJournal.targetObserved(moduleId, transformedName, fingerprint, false);
            }
            boolean dumped = !reviewedFingerprint && OptimizerDiscovery.dump(target, fingerprint, basicClass);
            String key = transformedName + '@' + fingerprint;
            if (REPORTED.add(key)) {
                String suffix = dumped ? "；开发发现样本已写出" : "";
                LOGGER.error("ICE 无法按调用图安全安装 " + target.adapterId + "，已保留 "
                    + transformedName + " 原字节码（fail-open）" + suffix, error);
            }
            return basicClass;
        }
    }
}
