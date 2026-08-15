package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Hoists five repeated Tabula getCube calls into two iteration-local values. */
final class IceAndFirePoseAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET =
        "com/github/alexthe666/iceandfire/client/model/animator/IceAndFireTabulaModelAnimator";
    static final String MODEL =
        "com/github/alexthe666/iceandfire/client/model/util/IceAndFireTabulaModel";
    static final String PART = "net/ilexiconn/llibrary/client/model/tools/AdvancedModelRenderer";
    static final String METHOD = "moveToPose";
    static final String ORIGINAL = "rlcraftIce$moveToPoseOriginal";
    static final String DESCRIPTOR = "(L" + MODEL + ";L" + MODEL + ";)V";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/iceandfire/IceAndFireOptimizationBridge";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(node.name)) throw new IllegalStateException("Ice and Fire Animator 类名变化：" + node.name);
        MethodNode original = null;
        int matches = 0;
        for (MethodNode method : node.methods) {
            if (METHOD.equals(method.name) && DESCRIPTOR.equals(method.desc)) {
                original = method;
                matches++;
            }
            if (ORIGINAL.equals(method.name)) throw new IllegalStateException("Ice and Fire Animator 已存在 ICE 方法");
        }
        if (matches != 1 || original == null) {
            throw new IllegalStateException("Ice and Fire moveToPose 调用图变化：" + matches);
        }
        int access = original.access;
        original.name = ORIGINAL;
        original.access |= Opcodes.ACC_SYNTHETIC;
        MethodNode wrapper = new MethodNode(Opcodes.ASM5, access, METHOD, DESCRIPTOR,
            original.signature, original.exceptions == null ? null
                : original.exceptions.toArray(new String[original.exceptions.size()]));
        MethodVisitor code = wrapper;
        code.visitCode();
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "usePoseLookup", "()Z", false);
        Label fast = new Label();
        code.visitJumpInsn(Opcodes.IFNE, fast);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitVarInsn(Opcodes.ALOAD, 2);
        code.visitMethodInsn(Opcodes.INVOKESPECIAL, TARGET, ORIGINAL, DESCRIPTOR, false);
        code.visitInsn(Opcodes.RETURN);
        code.visitLabel(fast);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MODEL, "getCubes", "()Ljava/util/Map;", false);
        code.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "values", "()Ljava/util/Collection;", true);
        code.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Collection", "iterator", "()Ljava/util/Iterator;", true);
        code.visitVarInsn(Opcodes.ASTORE, 3);
        Label loop = new Label();
        Label done = new Label();
        code.visitLabel(loop);
        code.visitVarInsn(Opcodes.ALOAD, 3);
        code.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        code.visitJumpInsn(Opcodes.IFEQ, done);
        code.visitVarInsn(Opcodes.ALOAD, 3);
        code.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        code.visitTypeInsn(Opcodes.CHECKCAST, PART);
        code.visitVarInsn(Opcodes.ASTORE, 4);
        code.visitVarInsn(Opcodes.ALOAD, 4);
        code.visitFieldInsn(Opcodes.GETFIELD, PART, "field_78802_n", "Ljava/lang/String;");
        code.visitVarInsn(Opcodes.ASTORE, 7);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitFieldInsn(Opcodes.GETFIELD, TARGET, "baseModel", "L" + MODEL + ";");
        code.visitVarInsn(Opcodes.ALOAD, 7);
        code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MODEL, "getCube", "(Ljava/lang/String;)L" + PART + ";", false);
        code.visitVarInsn(Opcodes.ASTORE, 5);
        code.visitVarInsn(Opcodes.ALOAD, 2);
        code.visitVarInsn(Opcodes.ALOAD, 7);
        code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MODEL, "getCube", "(Ljava/lang/String;)L" + PART + ";", false);
        code.visitVarInsn(Opcodes.ASTORE, 6);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitVarInsn(Opcodes.ALOAD, 5);
        code.visitVarInsn(Opcodes.ALOAD, 6);
        code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TARGET, "isPartEqual", "(L" + PART + ";L" + PART + ";)Z", false);
        code.visitJumpInsn(Opcodes.IFNE, loop);
        code.visitVarInsn(Opcodes.ALOAD, 6);
        code.visitFieldInsn(Opcodes.GETFIELD, PART, "field_78795_f", "F");
        code.visitVarInsn(Opcodes.FSTORE, 8);
        code.visitVarInsn(Opcodes.ALOAD, 6);
        code.visitFieldInsn(Opcodes.GETFIELD, PART, "field_78796_g", "F");
        code.visitVarInsn(Opcodes.FSTORE, 9);
        code.visitVarInsn(Opcodes.ALOAD, 6);
        code.visitFieldInsn(Opcodes.GETFIELD, PART, "field_78808_h", "F");
        code.visitVarInsn(Opcodes.FSTORE, 10);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitFieldInsn(Opcodes.GETFIELD, MODEL, "llibAnimator",
            "Lnet/ilexiconn/llibrary/client/model/ModelAnimator;");
        code.visitVarInsn(Opcodes.ALOAD, 4);
        distance(code, "field_78795_f", 8);
        distance(code, "field_78796_g", 9);
        distance(code, "field_78808_h", 10);
        code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/ilexiconn/llibrary/client/model/ModelAnimator",
            "rotate", "(Lnet/minecraft/client/model/ModelRenderer;FFF)V", false);
        code.visitJumpInsn(Opcodes.GOTO, loop);
        code.visitLabel(done);
        code.visitInsn(Opcodes.RETURN);
        code.visitMaxs(0, 0);
        code.visitEnd();
        node.methods.add(wrapper);
        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void distance(MethodVisitor code, String field, int targetLocal) {
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitVarInsn(Opcodes.ALOAD, 4);
        code.visitFieldInsn(Opcodes.GETFIELD, PART, field, "F");
        code.visitVarInsn(Opcodes.FLOAD, targetLocal);
        code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TARGET, "distance", "(FF)F", false);
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String left, String right) { return "java/lang/Object"; }
    }
}
