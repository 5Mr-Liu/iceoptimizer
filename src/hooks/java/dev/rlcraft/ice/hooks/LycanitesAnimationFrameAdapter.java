package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/** Caches the immutable animation-frame kind while validating public type mutations. */
final class LycanitesAnimationFrameAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "com/lycanitesmobs/client/model/ModelObjAnimationFrame";
    static final String ANIMATOR = "com/lycanitesmobs/client/model/Animator";
    static final String METHOD = "apply";
    static final String ORIGINAL = "rlcraftIce$applyOriginal";
    static final String DESCRIPTOR = "(L" + ANIMATOR + ";)V";
    static final String SNAPSHOT_FIELD = "rlcraftIce$typeSnapshot";
    static final String KIND_FIELD = "rlcraftIce$typeKind";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/lycanites/LycanitesAnimationBridge";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(node.name)) throw new IllegalStateException("Lycanites AnimationFrame 类名变化：" + node.name);
        rejectField(node, SNAPSHOT_FIELD);
        rejectField(node, KIND_FIELD);
        MethodNode original = null;
        int matches = 0;
        for (MethodNode method : node.methods) {
            if (METHOD.equals(method.name) && DESCRIPTOR.equals(method.desc)) {
                original = method;
                matches++;
            }
            if (ORIGINAL.equals(method.name) && DESCRIPTOR.equals(method.desc)) {
                throw new IllegalStateException("Lycanites AnimationFrame 已存在 ICE 原始方法");
            }
        }
        if (matches != 1 || original == null) {
            throw new IllegalStateException("Lycanites AnimationFrame 调用图变化：methods=" + matches);
        }
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC,
            SNAPSHOT_FIELD, "Ljava/lang/String;", null, null));
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC,
            KIND_FIELD, "I", null, null));
        int access = original.access;
        original.name = ORIGINAL;
        original.access |= Opcodes.ACC_SYNTHETIC;

        MethodNode wrapper = new MethodNode(Opcodes.ASM5, access, METHOD, DESCRIPTOR,
            original.signature, original.exceptions == null ? null
                : original.exceptions.toArray(new String[original.exceptions.size()]));
        MethodVisitor code = wrapper;
        code.visitCode();
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "useFastAnimation", "()Z", false);
        Label fast = new Label();
        code.visitJumpInsn(Opcodes.IFNE, fast);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitMethodInsn(Opcodes.INVOKESPECIAL, TARGET, ORIGINAL, DESCRIPTOR, false);
        code.visitInsn(Opcodes.RETURN);
        code.visitLabel(fast);

        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitFieldInsn(Opcodes.GETFIELD, TARGET, "type", "Ljava/lang/String;");
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitFieldInsn(Opcodes.GETFIELD, TARGET, SNAPSHOT_FIELD, "Ljava/lang/String;");
        Label classified = new Label();
        code.visitJumpInsn(Opcodes.IF_ACMPEQ, classified);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitFieldInsn(Opcodes.GETFIELD, TARGET, "type", "Ljava/lang/String;");
        code.visitFieldInsn(Opcodes.PUTFIELD, TARGET, SNAPSHOT_FIELD, "Ljava/lang/String;");
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitFieldInsn(Opcodes.GETFIELD, TARGET, "type", "Ljava/lang/String;");
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "classifyFrame", "(Ljava/lang/String;)I", false);
        code.visitFieldInsn(Opcodes.PUTFIELD, TARGET, KIND_FIELD, "I");
        code.visitLabel(classified);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitFieldInsn(Opcodes.GETFIELD, TARGET, KIND_FIELD, "I");
        Label angle = new Label();
        Label rotate = new Label();
        Label translate = new Label();
        Label scale = new Label();
        Label done = new Label();
        code.visitTableSwitchInsn(1, 4, done, angle, rotate, translate, scale);

        code.visitLabel(angle);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        value(code, "amount");
        value(code, "x");
        value(code, "y");
        value(code, "z");
        code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ANIMATOR, "doAngle", "(FFFF)V", false);
        code.visitJumpInsn(Opcodes.GOTO, done);

        code.visitLabel(rotate);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        scaled(code, "x");
        scaled(code, "y");
        scaled(code, "z");
        code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ANIMATOR, "doRotate", "(FFF)V", false);
        code.visitJumpInsn(Opcodes.GOTO, done);

        code.visitLabel(translate);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        scaled(code, "x");
        scaled(code, "y");
        scaled(code, "z");
        code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ANIMATOR, "doTranslate", "(FFF)V", false);
        code.visitJumpInsn(Opcodes.GOTO, done);

        code.visitLabel(scale);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        scaled(code, "x");
        scaled(code, "y");
        scaled(code, "z");
        code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ANIMATOR, "doScale", "(FFF)V", false);
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

    private static void value(MethodVisitor code, String field) {
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitFieldInsn(Opcodes.GETFIELD, TARGET, field, "F");
    }

    private static void scaled(MethodVisitor code, String field) {
        value(code, field);
        value(code, "amount");
        code.visitInsn(Opcodes.FMUL);
    }

    private static void rejectField(ClassNode node, String name) {
        for (FieldNode field : node.fields) {
            if (name.equals(field.name)) throw new IllegalStateException("Lycanites AnimationFrame 字段冲突：" + name);
        }
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String left, String right) { return "java/lang/Object"; }
    }
}
