package dev.rlcraft.ice.hooks;

import java.util.HashMap;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Replaces four tiny GL forwarding methods with exact identity-aware helpers. */
final class LycanitesAnimatorAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "com/lycanitesmobs/client/model/Animator";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/lycanites/LycanitesAnimationBridge";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, 0);
        if (!TARGET.equals(node.name)) throw new IllegalStateException("Lycanites Animator 类名变化：" + node.name);
        Map<String, String> reviewed = new HashMap<String, String>();
        reviewed.put("doAngle(FFFF)V", "angle");
        reviewed.put("doRotate(FFF)V", "rotate");
        reviewed.put("doTranslate(FFF)V", "translate");
        reviewed.put("doScale(FFF)V", "scale");
        int patched = 0;
        for (MethodNode method : node.methods) {
            String bridgeMethod = reviewed.get(method.name + method.desc);
            if (bridgeMethod == null) continue;
            if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                throw new IllegalStateException("Lycanites Animator 方法访问标志变化：" + method.name);
            }
            method.instructions.clear();
            method.tryCatchBlocks.clear();
            if (method.localVariables != null) method.localVariables.clear();
            MethodVisitor code = method;
            code.visitCode();
            int arguments = "doAngle".equals(method.name) ? 4 : 3;
            for (int i = 1; i <= arguments; i++) code.visitVarInsn(Opcodes.FLOAD, i);
            code.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, bridgeMethod, method.desc, false);
            code.visitInsn(Opcodes.RETURN);
            code.visitMaxs(0, 0);
            code.visitEnd();
            patched++;
        }
        if (patched != reviewed.size()) {
            throw new IllegalStateException("Lycanites Animator 方法数量变化：" + patched);
        }
        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String left, String right) { return "java/lang/Object"; }
    }
}
