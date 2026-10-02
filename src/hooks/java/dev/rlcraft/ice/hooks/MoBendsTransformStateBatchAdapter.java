package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/** Proves the transform-only parent used by ordinary Mo' Bends model parts. */
final class MoBendsTransformStateBatchAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "goblinbob/mobends/core/client/model/ModelPartTransform";

    @Override public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(node.name)
            || !ModelSubclassStateBatchAdapter.reviewed(node, "applyPreTransform", "(F)V",
                "3844161033606ca790281baa6666c8391a151ccb63eba16ea60c6804a46a9567")
            || !ModelSubclassStateBatchAdapter.reviewed(node, "applyLocalTransform", "(F)V",
                "827c978a3c7ade9386831f73b6b825141013da4cbc052118a035b133122e9e2f")
            || !ModelSubclassStateBatchAdapter.reviewed(node, "getParent",
                "()L" + MoBendsModelPartAdapter.PART + ";",
                "6dd0d829443cf13aa0bda69e437fab5ce0905e6cd44d3d26383d8102068d33c1")
            || !ModelSubclassStateBatchAdapter.reviewed(node, "getOffsetScale", "()F",
                "df04fbb3154b4659807d6338f7e84a3180456539b31ddf557bb2ca23765b0668")) {
            throw new OptimizerAdapterSkippedException("MoBends transform-only parent graph changed");
        }
        node.interfaces.add(MoBendsModelPartAdapter.TRANSFORM_ACCESS);
        ModelSubclassStateBatchAdapter.addExactClassReady(node, "ice$stateBatchTransformClassReady");
        ClassWriter writer = new ClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override protected String getCommonSuperClass(String left, String right) {
                return "java/lang/Object";
            }
        };
        node.accept(writer);
        return writer.toByteArray();
    }
}
