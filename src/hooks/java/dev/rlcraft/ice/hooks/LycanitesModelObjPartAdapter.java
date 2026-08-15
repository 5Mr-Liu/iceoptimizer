package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Uses an indexed animation-frame loop while preserving recursive transform order. */
final class LycanitesModelObjPartAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "com/lycanitesmobs/client/model/ModelObjPart";
    static final String ANIMATOR = "com/lycanitesmobs/client/model/Animator";
    static final String FRAME = "com/lycanitesmobs/client/model/ModelObjAnimationFrame";
    static final String METHOD = "applyAnimationFrames";
    static final String ORIGINAL = "rlcraftIce$applyAnimationFramesOriginal";
    static final String DESCRIPTOR = "(L" + ANIMATOR + ";)V";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/lycanites/LycanitesAnimationBridge";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(node.name)) throw new IllegalStateException("Lycanites ModelObjPart 类名变化：" + node.name);
        MethodNode original = null;
        int matches = 0;
        for (MethodNode method : node.methods) {
            if (METHOD.equals(method.name) && DESCRIPTOR.equals(method.desc)) {
                original = method;
                matches++;
            }
            if (ORIGINAL.equals(method.name) && DESCRIPTOR.equals(method.desc)) {
                throw new IllegalStateException("Lycanites ModelObjPart 已存在 ICE 原始方法");
            }
        }
        if (matches != 1 || original == null) {
            throw new IllegalStateException("Lycanites ModelObjPart 调用图变化：methods=" + matches);
        }
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

        Label noParent = new Label();
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitFieldInsn(Opcodes.GETFIELD, TARGET, "parent", "L" + TARGET + ";");
        code.visitJumpInsn(Opcodes.IFNULL, noParent);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitFieldInsn(Opcodes.GETFIELD, TARGET, "parent", "L" + TARGET + ";");
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TARGET, METHOD, DESCRIPTOR, false);
        code.visitLabel(noParent);

        Label noOffset = new Label();
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitFieldInsn(Opcodes.GETFIELD, TARGET, "offset", "L" + TARGET + ";");
        code.visitJumpInsn(Opcodes.IFNULL, noOffset);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitFieldInsn(Opcodes.GETFIELD, TARGET, "offset", "L" + TARGET + ";");
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TARGET, METHOD, DESCRIPTOR, false);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        centerPlusOffset(code, "centerX");
        centerPlusOffset(code, "centerY");
        centerPlusOffset(code, "centerZ");
        code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ANIMATOR, "doTranslate", "(FFF)V", false);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        negativeOffsetRotation(code, "rotationX");
        negativeOffsetRotation(code, "rotationY");
        negativeOffsetRotation(code, "rotationZ");
        code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ANIMATOR, "doRotate", "(FFF)V", false);
        code.visitLabel(noOffset);

        code.visitVarInsn(Opcodes.ALOAD, 1);
        field(code, "centerX");
        field(code, "centerY");
        field(code, "centerZ");
        code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ANIMATOR, "doTranslate", "(FFF)V", false);

        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitFieldInsn(Opcodes.GETFIELD, TARGET, "animationFrames", "Ljava/util/List;");
        code.visitVarInsn(Opcodes.ASTORE, 2);
        code.visitVarInsn(Opcodes.ALOAD, 2);
        code.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true);
        code.visitVarInsn(Opcodes.ISTORE, 4);
        code.visitInsn(Opcodes.ICONST_0);
        code.visitVarInsn(Opcodes.ISTORE, 3);
        Label loop = new Label();
        Label loopDone = new Label();
        code.visitLabel(loop);
        code.visitVarInsn(Opcodes.ILOAD, 3);
        code.visitVarInsn(Opcodes.ILOAD, 4);
        code.visitJumpInsn(Opcodes.IF_ICMPGE, loopDone);
        code.visitVarInsn(Opcodes.ALOAD, 2);
        code.visitVarInsn(Opcodes.ILOAD, 3);
        code.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true);
        code.visitTypeInsn(Opcodes.CHECKCAST, FRAME);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME, "apply", "(L" + ANIMATOR + ";)V", false);
        code.visitIincInsn(3, 1);
        code.visitJumpInsn(Opcodes.GOTO, loop);
        code.visitLabel(loopDone);

        code.visitVarInsn(Opcodes.ALOAD, 1);
        negativeField(code, "centerX");
        negativeField(code, "centerY");
        negativeField(code, "centerZ");
        code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ANIMATOR, "doTranslate", "(FFF)V", false);
        code.visitInsn(Opcodes.RETURN);
        code.visitMaxs(0, 0);
        code.visitEnd();
        node.methods.add(wrapper);

        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void centerPlusOffset(MethodVisitor code, String name) {
        field(code, name);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitFieldInsn(Opcodes.GETFIELD, TARGET, "offset", "L" + TARGET + ";");
        code.visitFieldInsn(Opcodes.GETFIELD, TARGET, name, "F");
        code.visitInsn(Opcodes.FADD);
    }

    private static void negativeOffsetRotation(MethodVisitor code, String name) {
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitFieldInsn(Opcodes.GETFIELD, TARGET, "offset", "L" + TARGET + ";");
        code.visitFieldInsn(Opcodes.GETFIELD, TARGET, name, "F");
        code.visitInsn(Opcodes.FNEG);
    }

    private static void field(MethodVisitor code, String name) {
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitFieldInsn(Opcodes.GETFIELD, TARGET, name, "F");
    }

    private static void negativeField(MethodVisitor code, String name) {
        field(code, name);
        code.visitInsn(Opcodes.FNEG);
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String left, String right) { return "java/lang/Object"; }
    }
}
