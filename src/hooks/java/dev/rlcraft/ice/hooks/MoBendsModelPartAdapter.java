package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/** Exact parent-topology cache and indexed child traversal for Mo' Bends ModelPart. */
final class MoBendsModelPartAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "goblinbob/mobends/core/client/model/ModelPart";
    static final String PART = "goblinbob/mobends/core/client/model/IModelPart";
    static final String MATRIX = "goblinbob/mobends/core/math/matrix/IMat4x4d";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/mobends/MoBendsRenderBridge";
    static final String CHAIN_FIELD = "rlcraftIce$transformChain";
    static final String SCALES_FIELD = "rlcraftIce$transformScales";
    static final String RESOLVE_METHOD = "rlcraftIce$resolveTransformChain";
    static final String RENDER_ORIGINAL = "rlcraftIce$renderPartOriginal";
    static final String JUST_ORIGINAL = "rlcraftIce$renderJustPartOriginal";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(node.name)) throw new IllegalStateException("MoBends ModelPart 类名变化：" + node.name);
        rejectField(node, CHAIN_FIELD);
        rejectField(node, SCALES_FIELD);
        int characterMethods = 0;
        MethodNode render = null;
        MethodNode just = null;
        for (MethodNode method : node.methods) {
            if ("applyCharacterTransform".equals(method.name)) characterMethods++;
            if ("renderPart".equals(method.name) && "(F)V".equals(method.desc)) render = method;
            if ("renderJustPart".equals(method.name) && "(F)V".equals(method.desc)) just = method;
            if (RESOLVE_METHOD.equals(method.name) || RENDER_ORIGINAL.equals(method.name)
                || JUST_ORIGINAL.equals(method.name)) {
                throw new IllegalStateException("MoBends ModelPart 已存在 ICE 方法：" + method.name);
            }
        }
        if (characterMethods != 0 || render == null || just == null) {
            throw new IllegalStateException("MoBends ModelPart 调用图变化：characterMethods="
                + characterMethods + ", render=" + (render != null) + ", just=" + (just != null));
        }
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC,
            CHAIN_FIELD, "[L" + PART + ";", null, null));
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC,
            SCALES_FIELD, "[F", null, null));
        addResolveMethod(node);
        addCharacterTransform(node, false);
        addCharacterTransform(node, true);
        wrapRender(node, render, false);
        wrapRender(node, just, true);

        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void addResolveMethod(ClassNode node) {
        MethodNode method = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            RESOLVE_METHOD, "()[L" + PART + ";", null, null);
        MethodVisitor code = method;
        code.visitCode();
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitFieldInsn(Opcodes.GETFIELD, TARGET, CHAIN_FIELD, "[L" + PART + ";");
        code.visitVarInsn(Opcodes.ASTORE, 1);
        Label rebuild = new Label();
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitJumpInsn(Opcodes.IFNULL, rebuild);
        code.visitInsn(Opcodes.ICONST_0);
        code.visitVarInsn(Opcodes.ISTORE, 2);
        Label validate = new Label();
        Label cachedReady = new Label();
        code.visitLabel(validate);
        code.visitVarInsn(Opcodes.ILOAD, 2);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitInsn(Opcodes.ARRAYLENGTH);
        code.visitJumpInsn(Opcodes.IF_ICMPGE, cachedReady);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitVarInsn(Opcodes.ILOAD, 2);
        code.visitInsn(Opcodes.AALOAD);
        code.visitMethodInsn(Opcodes.INVOKEINTERFACE, PART, "getParent", "()L" + PART + ";", true);
        code.visitVarInsn(Opcodes.ILOAD, 2);
        code.visitInsn(Opcodes.ICONST_1);
        code.visitInsn(Opcodes.IADD);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitInsn(Opcodes.ARRAYLENGTH);
        Label expectedNull = new Label();
        Label expectedReady = new Label();
        code.visitJumpInsn(Opcodes.IF_ICMPGE, expectedNull);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitVarInsn(Opcodes.ILOAD, 2);
        code.visitInsn(Opcodes.ICONST_1);
        code.visitInsn(Opcodes.IADD);
        code.visitInsn(Opcodes.AALOAD);
        code.visitJumpInsn(Opcodes.GOTO, expectedReady);
        code.visitLabel(expectedNull);
        code.visitInsn(Opcodes.ACONST_NULL);
        code.visitLabel(expectedReady);
        code.visitJumpInsn(Opcodes.IF_ACMPNE, rebuild);
        code.visitIincInsn(2, 1);
        code.visitJumpInsn(Opcodes.GOTO, validate);

        code.visitLabel(rebuild);
        code.visitInsn(Opcodes.ICONST_0);
        code.visitVarInsn(Opcodes.ISTORE, 3);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitVarInsn(Opcodes.ASTORE, 4);
        Label count = new Label();
        Label allocate = new Label();
        Label tooDeep = new Label();
        code.visitLabel(count);
        code.visitVarInsn(Opcodes.ALOAD, 4);
        code.visitJumpInsn(Opcodes.IFNULL, allocate);
        code.visitVarInsn(Opcodes.ILOAD, 3);
        code.visitIntInsn(Opcodes.BIPUSH, 64);
        code.visitJumpInsn(Opcodes.IF_ICMPGE, tooDeep);
        code.visitIincInsn(3, 1);
        code.visitVarInsn(Opcodes.ALOAD, 4);
        code.visitMethodInsn(Opcodes.INVOKEINTERFACE, PART, "getParent", "()L" + PART + ";", true);
        code.visitVarInsn(Opcodes.ASTORE, 4);
        code.visitJumpInsn(Opcodes.GOTO, count);
        code.visitLabel(tooDeep);
        code.visitInsn(Opcodes.ACONST_NULL);
        code.visitInsn(Opcodes.ARETURN);

        code.visitLabel(allocate);
        code.visitVarInsn(Opcodes.ILOAD, 3);
        code.visitTypeInsn(Opcodes.ANEWARRAY, PART);
        code.visitVarInsn(Opcodes.ASTORE, 1);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitVarInsn(Opcodes.ASTORE, 4);
        code.visitInsn(Opcodes.ICONST_0);
        code.visitVarInsn(Opcodes.ISTORE, 2);
        Label fill = new Label();
        Label store = new Label();
        code.visitLabel(fill);
        code.visitVarInsn(Opcodes.ILOAD, 2);
        code.visitVarInsn(Opcodes.ILOAD, 3);
        code.visitJumpInsn(Opcodes.IF_ICMPGE, store);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitVarInsn(Opcodes.ILOAD, 2);
        code.visitVarInsn(Opcodes.ALOAD, 4);
        code.visitInsn(Opcodes.AASTORE);
        code.visitVarInsn(Opcodes.ALOAD, 4);
        code.visitMethodInsn(Opcodes.INVOKEINTERFACE, PART, "getParent", "()L" + PART + ";", true);
        code.visitVarInsn(Opcodes.ASTORE, 4);
        code.visitIincInsn(2, 1);
        code.visitJumpInsn(Opcodes.GOTO, fill);
        code.visitLabel(store);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitFieldInsn(Opcodes.PUTFIELD, TARGET, CHAIN_FIELD, "[L" + PART + ";");

        code.visitLabel(cachedReady);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitFieldInsn(Opcodes.GETFIELD, TARGET, SCALES_FIELD, "[F");
        Label allocateScales = new Label();
        Label returnChain = new Label();
        code.visitJumpInsn(Opcodes.IFNULL, allocateScales);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitFieldInsn(Opcodes.GETFIELD, TARGET, SCALES_FIELD, "[F");
        code.visitInsn(Opcodes.ARRAYLENGTH);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitInsn(Opcodes.ARRAYLENGTH);
        code.visitJumpInsn(Opcodes.IF_ICMPGE, returnChain);
        code.visitLabel(allocateScales);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitInsn(Opcodes.ARRAYLENGTH);
        code.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_FLOAT);
        code.visitFieldInsn(Opcodes.PUTFIELD, TARGET, SCALES_FIELD, "[F");
        code.visitLabel(returnChain);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitInsn(Opcodes.ARETURN);
        code.visitMaxs(0, 0);
        code.visitEnd();
        node.methods.add(method);
    }

    private static void addCharacterTransform(ClassNode node, boolean matrix) {
        String descriptor = matrix ? "(FL" + MATRIX + ";)V" : "(F)V";
        MethodNode method = new MethodNode(Opcodes.ASM5, Opcodes.ACC_PUBLIC, "applyCharacterTransform",
            descriptor, null, null);
        MethodVisitor code = method;
        code.visitCode();
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "useModelRender", "()Z", false);
        Label fast = new Label();
        code.visitJumpInsn(Opcodes.IFNE, fast);
        emitDefaultCall(code, descriptor, matrix);
        code.visitInsn(Opcodes.RETURN);
        code.visitLabel(fast);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitMethodInsn(Opcodes.INVOKESPECIAL, TARGET, RESOLVE_METHOD, "()[L" + PART + ";", false);
        int chainLocal = matrix ? 3 : 2;
        int scalesLocal = chainLocal + 1;
        int indexLocal = scalesLocal + 1;
        int currentScaleLocal = indexLocal + 1;
        code.visitVarInsn(Opcodes.ASTORE, chainLocal);
        code.visitVarInsn(Opcodes.ALOAD, chainLocal);
        Label haveChain = new Label();
        code.visitJumpInsn(Opcodes.IFNONNULL, haveChain);
        emitDefaultCall(code, descriptor, matrix);
        code.visitInsn(Opcodes.RETURN);
        code.visitLabel(haveChain);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitFieldInsn(Opcodes.GETFIELD, TARGET, SCALES_FIELD, "[F");
        code.visitVarInsn(Opcodes.ASTORE, scalesLocal);
        code.visitVarInsn(Opcodes.FLOAD, 1);
        code.visitVarInsn(Opcodes.FSTORE, currentScaleLocal);
        code.visitInsn(Opcodes.ICONST_0);
        code.visitVarInsn(Opcodes.ISTORE, indexLocal);
        Label preLoop = new Label();
        Label preDone = new Label();
        code.visitLabel(preLoop);
        code.visitVarInsn(Opcodes.ILOAD, indexLocal);
        code.visitVarInsn(Opcodes.ALOAD, chainLocal);
        code.visitInsn(Opcodes.ARRAYLENGTH);
        code.visitJumpInsn(Opcodes.IF_ICMPGE, preDone);
        code.visitVarInsn(Opcodes.ALOAD, scalesLocal);
        code.visitVarInsn(Opcodes.ILOAD, indexLocal);
        code.visitVarInsn(Opcodes.FLOAD, currentScaleLocal);
        code.visitInsn(Opcodes.FASTORE);
        code.visitVarInsn(Opcodes.ALOAD, chainLocal);
        code.visitVarInsn(Opcodes.ILOAD, indexLocal);
        code.visitInsn(Opcodes.AALOAD);
        code.visitVarInsn(Opcodes.FLOAD, currentScaleLocal);
        if (matrix) code.visitVarInsn(Opcodes.ALOAD, 2);
        code.visitMethodInsn(Opcodes.INVOKEINTERFACE, PART, "applyPreTransform",
            matrix ? "(FL" + MATRIX + ";)V" : "(F)V", true);
        code.visitVarInsn(Opcodes.ILOAD, indexLocal);
        code.visitInsn(Opcodes.ICONST_1);
        code.visitInsn(Opcodes.IADD);
        code.visitVarInsn(Opcodes.ALOAD, chainLocal);
        code.visitInsn(Opcodes.ARRAYLENGTH);
        Label noNext = new Label();
        code.visitJumpInsn(Opcodes.IF_ICMPGE, noNext);
        code.visitVarInsn(Opcodes.FLOAD, currentScaleLocal);
        code.visitVarInsn(Opcodes.ALOAD, chainLocal);
        code.visitVarInsn(Opcodes.ILOAD, indexLocal);
        code.visitInsn(Opcodes.AALOAD);
        code.visitMethodInsn(Opcodes.INVOKEINTERFACE, PART, "getOffsetScale", "()F", true);
        code.visitInsn(Opcodes.FMUL);
        code.visitVarInsn(Opcodes.FSTORE, currentScaleLocal);
        code.visitLabel(noNext);
        code.visitIincInsn(indexLocal, 1);
        code.visitJumpInsn(Opcodes.GOTO, preLoop);
        code.visitLabel(preDone);
        code.visitVarInsn(Opcodes.ALOAD, chainLocal);
        code.visitInsn(Opcodes.ARRAYLENGTH);
        code.visitInsn(Opcodes.ICONST_1);
        code.visitInsn(Opcodes.ISUB);
        code.visitVarInsn(Opcodes.ISTORE, indexLocal);
        Label localLoop = new Label();
        Label done = new Label();
        code.visitLabel(localLoop);
        code.visitVarInsn(Opcodes.ILOAD, indexLocal);
        code.visitJumpInsn(Opcodes.IFLT, done);
        code.visitVarInsn(Opcodes.ALOAD, chainLocal);
        code.visitVarInsn(Opcodes.ILOAD, indexLocal);
        code.visitInsn(Opcodes.AALOAD);
        code.visitVarInsn(Opcodes.ALOAD, scalesLocal);
        code.visitVarInsn(Opcodes.ILOAD, indexLocal);
        code.visitInsn(Opcodes.FALOAD);
        if (matrix) code.visitVarInsn(Opcodes.ALOAD, 2);
        code.visitMethodInsn(Opcodes.INVOKEINTERFACE, PART, "applyLocalTransform",
            matrix ? "(FL" + MATRIX + ";)V" : "(F)V", true);
        code.visitIincInsn(indexLocal, -1);
        code.visitJumpInsn(Opcodes.GOTO, localLoop);
        code.visitLabel(done);
        code.visitInsn(Opcodes.RETURN);
        code.visitMaxs(0, 0);
        code.visitEnd();
        node.methods.add(method);
    }

    private static void emitDefaultCall(MethodVisitor code, String descriptor, boolean matrix) {
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitVarInsn(Opcodes.FLOAD, 1);
        if (matrix) code.visitVarInsn(Opcodes.ALOAD, 2);
        code.visitMethodInsn(Opcodes.INVOKESPECIAL, PART, "applyCharacterTransform", descriptor, true);
    }

    private static void wrapRender(ClassNode node, MethodNode original, boolean justPart) {
        String publicName = justPart ? "renderJustPart" : "renderPart";
        String originalName = justPart ? JUST_ORIGINAL : RENDER_ORIGINAL;
        int access = original.access;
        original.name = originalName;
        original.access |= Opcodes.ACC_SYNTHETIC;
        MethodNode wrapper = new MethodNode(Opcodes.ASM5, access, publicName, "(F)V",
            original.signature, original.exceptions == null ? null
                : original.exceptions.toArray(new String[original.exceptions.size()]));
        MethodVisitor code = wrapper;
        code.visitCode();
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "useModelRender", "()Z", false);
        Label fast = new Label();
        code.visitJumpInsn(Opcodes.IFNE, fast);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitVarInsn(Opcodes.FLOAD, 1);
        code.visitMethodInsn(Opcodes.INVOKESPECIAL, TARGET, originalName, "(F)V", false);
        code.visitInsn(Opcodes.RETURN);
        code.visitLabel(fast);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TARGET, "isShowing", "()Z", false);
        Label visible = new Label();
        code.visitJumpInsn(Opcodes.IFNE, visible);
        code.visitInsn(Opcodes.RETURN);
        code.visitLabel(visible);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitFieldInsn(Opcodes.GETFIELD, TARGET, "field_78812_q", "Z");
        Label compiled = new Label();
        code.visitJumpInsn(Opcodes.IFNE, compiled);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitVarInsn(Opcodes.FLOAD, 1);
        code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TARGET, "func_78788_d", "(F)V", false);
        code.visitLabel(compiled);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/client/renderer/GlStateManager",
            "func_179094_E", "()V", false);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitVarInsn(Opcodes.FLOAD, 1);
        code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TARGET,
            justPart ? "applyLocalTransform" : "applyCharacterTransform", "(F)V", false);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitFieldInsn(Opcodes.GETFIELD, TARGET, "field_78811_r", "I");
        code.visitMethodInsn(Opcodes.INVOKESTATIC,
            "dev/rlcraft/ice/optimizer/compat/model/ModelMeshCaptureBridge",
            "callList", "(I)V", false);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitFieldInsn(Opcodes.GETFIELD, TARGET, "field_78805_m", "Ljava/util/List;");
        Label noChildren = new Label();
        code.visitJumpInsn(Opcodes.IFNULL, noChildren);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitFieldInsn(Opcodes.GETFIELD, TARGET, "field_78805_m", "Ljava/util/List;");
        code.visitVarInsn(Opcodes.ASTORE, 2);
        code.visitVarInsn(Opcodes.ALOAD, 2);
        code.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true);
        code.visitVarInsn(Opcodes.ISTORE, 4);
        code.visitInsn(Opcodes.ICONST_0);
        code.visitVarInsn(Opcodes.ISTORE, 3);
        Label loop = new Label();
        code.visitLabel(loop);
        code.visitVarInsn(Opcodes.ILOAD, 3);
        code.visitVarInsn(Opcodes.ILOAD, 4);
        code.visitJumpInsn(Opcodes.IF_ICMPGE, noChildren);
        code.visitVarInsn(Opcodes.ALOAD, 2);
        code.visitVarInsn(Opcodes.ILOAD, 3);
        code.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true);
        code.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraft/client/model/ModelRenderer");
        code.visitVarInsn(Opcodes.FLOAD, 1);
        code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/client/model/ModelRenderer",
            "func_78785_a", "(F)V", false);
        code.visitIincInsn(3, 1);
        code.visitJumpInsn(Opcodes.GOTO, loop);
        code.visitLabel(noChildren);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/client/renderer/GlStateManager",
            "func_179121_F", "()V", false);
        code.visitInsn(Opcodes.RETURN);
        code.visitMaxs(0, 0);
        code.visitEnd();
        node.methods.add(wrapper);
    }

    private static void rejectField(ClassNode node, String name) {
        for (FieldNode field : node.fields) {
            if (name.equals(field.name)) throw new IllegalStateException("MoBends ModelPart 字段冲突：" + name);
        }
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String left, String right) { return "java/lang/Object"; }
    }
}
