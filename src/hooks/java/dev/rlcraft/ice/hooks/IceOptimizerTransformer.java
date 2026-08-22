package dev.rlcraft.ice.hooks;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
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
        List<TargetSpec> targets = OptimizerTargetCatalog.findAll(transformedName);
        if (targets.isEmpty()) return basicClass;
        String fingerprint = CoreClassFingerprint.sha256(basicClass);
        byte[] current = basicClass;
        for (TargetSpec target : targets) {
            OptimizerBytecodeAdapter adapter = OptimizerAdapterRegistry.find(target.adapterId);
            if (adapter == null) {
                for (String moduleId : target.moduleIds) {
                    OptimizerPatchJournal.targetObserved(moduleId, transformedName, fingerprint, false);
                }
                LOGGER.error("ICE 目标 {} 的适配器 {} 尚未编入当前构建；仅保留该能力的原字节码",
                    transformedName, target.adapterId);
                continue;
            }
            boolean reviewedFingerprint = target.hasReviewedFingerprint(fingerprint);
            try {
                byte[] transformed = adapter.transform(transformedName, current, target);
                if (transformed == null || transformed == current) {
                    throw new IllegalStateException("适配器未生成新字节码");
                }
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
                current = transformed;
            } catch (OptimizerAdapterSkippedException skipped) {
                for (String moduleId : target.moduleIds) {
                    OptimizerPatchJournal.targetObserved(moduleId, transformedName, fingerprint, false);
                }
                String key = transformedName + '@' + fingerprint + '#' + target.adapterId;
                if (REPORTED.add(key)) {
                    LOGGER.info("ICE 能力 {} 已按兼容策略交由外部实现；仅跳过 {}，继续尝试同类中的其它独立优化：{}",
                        target.adapterId, target.moduleId, skipped.getMessage());
                }
            } catch (Throwable error) {
                HookFatalErrors.rethrowIfFatal(error);
                for (String moduleId : target.moduleIds) {
                    OptimizerPatchJournal.targetObserved(moduleId, transformedName, fingerprint, false);
                }
                boolean dumped = !reviewedFingerprint
                    && OptimizerDiscovery.dump(target, fingerprint, current);
                String key = transformedName + '@' + fingerprint + '#' + target.adapterId;
                if (REPORTED.add(key)) {
                    String suffix = dumped ? "；开发发现样本已写出" : "";
                    LOGGER.error("ICE 无法安全安装能力 " + target.adapterId + "，仅回退 "
                        + target.moduleId + "；继续尝试同类中的其它独立优化（fail-open）" + suffix,
                        error);
                }
            }
        }
        return current;
    }
}
