package dev.rlcraft.ice.hooks;

import java.util.Arrays;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/** Adds raw-float-validated quaternion matrices and consumes them from GlHelper. */
final class MoBendsQuaternionAdapter implements OptimizerBytecodeAdapter {
    static final String QUATERNION = "goblinbob/mobends/core/math/Quaternion";
    static final String GL_HELPER = "goblinbob/mobends/core/util/GlHelper";
    static final String ACCESS =
        "dev/rlcraft/ice/optimizer/compat/mobends/MoBendsQuaternionAccess";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/mobends/MoBendsRenderBridge";
    static final String MATRIX_FIELD = "rlcraftIce$glMatrix";
    static final String VALID_FIELD = "rlcraftIce$glMatrixValid";
    static final String METHOD = "rlcraftIce$getGlMatrix";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        String internal = transformedName.replace('.', '/');
        if (QUATERNION.equals(internal)) return transformQuaternion(originalClass);
        if (GL_HELPER.equals(internal)) return transformGlHelper(originalClass);
        throw new IllegalStateException("未审查的 MoBends Quaternion 目标：" + transformedName);
    }

    private static byte[] transformQuaternion(byte[] originalClass) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        rejectField(node, MATRIX_FIELD);
        rejectField(node, VALID_FIELD);
        for (String component : new String[] { "X", "Y", "Z", "W" }) rejectField(node, bitsField(component));
        for (MethodNode method : node.methods) {
            if (METHOD.equals(method.name)) throw new IllegalStateException("MoBends Quaternion 已存在 ICE 方法");
        }
        String[] interfaces = node.interfaces.toArray(new String[node.interfaces.size()]);
        if (!Arrays.asList(interfaces).contains(ACCESS)) node.interfaces.add(ACCESS);
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC,
            MATRIX_FIELD, "Ljava/nio/FloatBuffer;", null, null));
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC,
            VALID_FIELD, "Z", null, null));
        for (String component : new String[] { "X", "Y", "Z", "W" }) {
            node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC,
                bitsField(component), "I", null, null));
        }
        addMatrixMethod(node);
        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void addMatrixMethod(ClassNode node) {
        MethodNode method = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            METHOD, "()Ljava/nio/FloatBuffer;", null, null);
        MethodVisitor code = method;
        code.visitCode();
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitFieldInsn(Opcodes.GETFIELD, QUATERNION, MATRIX_FIELD, "Ljava/nio/FloatBuffer;");
        code.visitVarInsn(Opcodes.ASTORE, 1);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        Label haveBuffer = new Label();
        code.visitJumpInsn(Opcodes.IFNONNULL, haveBuffer);
        code.visitIntInsn(Opcodes.BIPUSH, 16);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/BufferUtils", "createFloatBuffer",
            "(I)Ljava/nio/FloatBuffer;", false);
        code.visitVarInsn(Opcodes.ASTORE, 1);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitFieldInsn(Opcodes.PUTFIELD, QUATERNION, MATRIX_FIELD, "Ljava/nio/FloatBuffer;");
        code.visitLabel(haveBuffer);
        String[] fields = { "x", "y", "z", "w" };
        for (int i = 0; i < fields.length; i++) {
            code.visitVarInsn(Opcodes.ALOAD, 0);
            code.visitFieldInsn(Opcodes.GETFIELD, QUATERNION, fields[i], "F");
            code.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "floatToRawIntBits", "(F)I", false);
            code.visitVarInsn(Opcodes.ISTORE, 2 + i);
        }
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitFieldInsn(Opcodes.GETFIELD, QUATERNION, VALID_FIELD, "Z");
        Label recompute = new Label();
        code.visitJumpInsn(Opcodes.IFEQ, recompute);
        for (int i = 0; i < fields.length; i++) {
            code.visitVarInsn(Opcodes.ILOAD, 2 + i);
            code.visitVarInsn(Opcodes.ALOAD, 0);
            code.visitFieldInsn(Opcodes.GETFIELD, QUATERNION,
                bitsField(fields[i].toUpperCase()), "I");
            code.visitJumpInsn(Opcodes.IF_ICMPNE, recompute);
        }
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/nio/FloatBuffer", "rewind", "()Ljava/nio/Buffer;", false);
        code.visitInsn(Opcodes.POP);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitInsn(Opcodes.ARETURN);

        code.visitLabel(recompute);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, "goblinbob/mobends/core/math/QuaternionUtils",
            "quatToGlMatrix", "(Ljava/nio/FloatBuffer;L" + QUATERNION + ";)Ljava/nio/FloatBuffer;", false);
        code.visitInsn(Opcodes.POP);
        for (int i = 0; i < fields.length; i++) {
            code.visitVarInsn(Opcodes.ALOAD, 0);
            code.visitVarInsn(Opcodes.ILOAD, 2 + i);
            code.visitFieldInsn(Opcodes.PUTFIELD, QUATERNION,
                bitsField(fields[i].toUpperCase()), "I");
        }
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitInsn(Opcodes.ICONST_1);
        code.visitFieldInsn(Opcodes.PUTFIELD, QUATERNION, VALID_FIELD, "Z");
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitInsn(Opcodes.ARETURN);
        code.visitMaxs(0, 0);
        code.visitEnd();
        node.methods.add(method);
    }

    private static byte[] transformGlHelper(byte[] originalClass) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        MethodNode rotate = null;
        int matches = 0;
        for (MethodNode method : node.methods) {
            if ("rotate".equals(method.name) && ("(L" + QUATERNION + ";)V").equals(method.desc)) {
                rotate = method;
                matches++;
            }
        }
        if (matches != 1 || rotate == null) {
            throw new IllegalStateException("MoBends GlHelper.rotate 调用图变化：" + matches);
        }
        rotate.instructions.clear();
        rotate.tryCatchBlocks.clear();
        if (rotate.localVariables != null) rotate.localVariables.clear();
        MethodVisitor code = rotate;
        code.visitCode();
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "useQuaternionCache", "()Z", false);
        Label fallback = new Label();
        code.visitJumpInsn(Opcodes.IFEQ, fallback);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitTypeInsn(Opcodes.INSTANCEOF, ACCESS);
        code.visitJumpInsn(Opcodes.IFEQ, fallback);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitTypeInsn(Opcodes.CHECKCAST, ACCESS);
        code.visitMethodInsn(Opcodes.INVOKEINTERFACE, ACCESS, METHOD, "()Ljava/nio/FloatBuffer;", true);
        Label matrixReady = new Label();
        code.visitJumpInsn(Opcodes.GOTO, matrixReady);
        code.visitLabel(fallback);
        code.visitFieldInsn(Opcodes.GETSTATIC, GL_HELPER, "BUF_FLOAT_16", "Ljava/nio/FloatBuffer;");
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, "goblinbob/mobends/core/math/QuaternionUtils",
            "quatToGlMatrix", "(Ljava/nio/FloatBuffer;L" + QUATERNION + ";)Ljava/nio/FloatBuffer;", false);
        code.visitLabel(matrixReady);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/client/renderer/GlStateManager",
            "func_179110_a", "(Ljava/nio/FloatBuffer;)V", false);
        code.visitInsn(Opcodes.RETURN);
        code.visitMaxs(0, 0);
        code.visitEnd();
        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static String bitsField(String component) {
        return "rlcraftIce$" + component.toLowerCase() + "Bits";
    }

    private static void rejectField(ClassNode node, String name) {
        for (FieldNode field : node.fields) {
            if (name.equals(field.name)) throw new IllegalStateException("MoBends Quaternion 字段冲突：" + name);
        }
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String left, String right) { return "java/lang/Object"; }
    }
}
