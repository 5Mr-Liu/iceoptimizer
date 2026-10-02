package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
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
    static final String CHAIN_READY_FIELD = "rlcraftIce$stateBatchTransformChainReady";
    static final String TRANSFORM_ACCESS =
        "dev/rlcraft/ice/optimizer/compat/mobends/MoBendsStateBatchTransformAccess";
    static final String RESOLVE_METHOD = "rlcraftIce$resolveTransformChain";
    static final String RENDER_ORIGINAL = "rlcraftIce$renderPartOriginal";
    static final String JUST_ORIGINAL = "rlcraftIce$renderJustPartOriginal";
    static final String ROTATE_POSE_METHOD = "rlcraftIce$rotatePoseMatrix";
    static final String QUATERNION = MoBendsQuaternionAdapter.QUATERNION;
    static final String ROTATE_POSE_DESCRIPTOR = "(L" + MATRIX + ";L" + QUATERNION + ";)V";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(node.name)) throw new IllegalStateException("MoBends ModelPart 类名变化：" + node.name);
        rejectField(node, CHAIN_FIELD);
        rejectField(node, SCALES_FIELD);
        rejectField(node, CHAIN_READY_FIELD);
        boolean batchReviewed = reviewedBatchGraph(node);
        int characterMethods = 0;
        MethodNode render = null;
        MethodNode just = null;
        for (MethodNode method : node.methods) {
            if ("applyCharacterTransform".equals(method.name)) characterMethods++;
            if ("renderPart".equals(method.name) && "(F)V".equals(method.desc)) render = method;
            if ("renderJustPart".equals(method.name) && "(F)V".equals(method.desc)) just = method;
            if (RESOLVE_METHOD.equals(method.name) || RENDER_ORIGINAL.equals(method.name)
                || JUST_ORIGINAL.equals(method.name) || ROTATE_POSE_METHOD.equals(method.name)) {
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
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC,
            CHAIN_READY_FIELD, "Z", null, null));
        if (batchReviewed) {
            node.interfaces.add(TRANSFORM_ACCESS);
            ModelSubclassStateBatchAdapter.addExactClassReady(node, "ice$stateBatchTransformClassReady");
            ModelSubclassStateBatchAdapter.addDisplayListAccess(node, "field_78811_r", "field_78812_q");
            replaceStateBatchReady(node);
        }
        if (ModelSubclassStateBatchAdapter.reviewed(node, "func_78788_d", "(F)V",
            "003c8005df15c1a39e8399eb486a4d0aa8f2a321b4c58afc833f7c56f33a2e77")) {
            ModelSubclassStateBatchAdapter.wrapCompiler(node,
                ModelStateBatchMethodProof.method(node, "func_78788_d", "(F)V"),
                "rlcraftIce$compileMoBendsDisplayListOriginal");
        }
        addResolveMethod(node);
        addCharacterTransform(node, false);
        addCharacterTransform(node, true);
        wrapRender(node, render, false, batchReviewed);
        wrapRender(node, just, true, batchReviewed);
        redirectPoseRotation(node);

        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean reviewedBatchGraph(ClassNode node) {
        return ModelSubclassStateBatchAdapter.reviewed(node, "func_78785_a", "(F)V",
                "d7135cc1e1b07f90fefd47da8a07da8b41d03be459b29f104b7b13fd0705d934")
            && ModelSubclassStateBatchAdapter.reviewed(node, "renderPart", "(F)V",
                "db7a2f9480965d6c5c6f17602a74e66ce2d338b715b7ea5a1b16e1d103b8f46b")
            && ModelSubclassStateBatchAdapter.reviewed(node, "renderJustPart", "(F)V",
                "2bf2c4590fa263dc0df6c66a6d4712e8be5378f8516c46c9a3a6a93303d9f01e")
            && ModelSubclassStateBatchAdapter.reviewed(node, "applyPreTransform", "(F)V",
                "d3db669bca0085e850b664472f755bbedbc084a3457b5b47046e8e8e62935612")
            && ModelSubclassStateBatchAdapter.reviewed(node, "applyLocalTransform", "(F)V",
                "ba08c5fa55332e2e397305f1d2a1ec674603eac117bf969bb161b87cf36bcbb3")
            && ModelSubclassStateBatchAdapter.reviewed(node, "getParent", "()L" + PART + ";",
                "626ed7e4e2308338200991e6cd41a31ad0b8a0d5f9e7e7f355ff4db9b42f9e82")
            && ModelSubclassStateBatchAdapter.reviewed(node, "getOffsetScale", "()F",
                "7abab3728190634778b95b64bee207d120ee8df535cd3f8a8eb7b0310ff0fb79")
            && ModelSubclassStateBatchAdapter.reviewed(node, "isShowing", "()Z",
                "bca759c666933004841f9abc2d228d5ec2b25292d7efed82ef0f25ce84cd3bf3");
    }

    private static void replaceStateBatchReady(ClassNode node) {
        node.methods.remove(ModelStateBatchMethodProof.method(node, "ice$stateBatchNodeReady", "()Z"));
        MethodNode ready = new MethodNode(Opcodes.ASM5, Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
            "ice$stateBatchNodeReady", "()Z", null, null);
        ready.visitCode();
        Label declined = new Label();
        ready.visitVarInsn(Opcodes.ALOAD, 0);
        ready.visitFieldInsn(Opcodes.GETFIELD, TARGET, "field_78812_q", "Z");
        ready.visitJumpInsn(Opcodes.IFEQ, declined);
        ready.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "useModelRender", "()Z", false);
        ready.visitJumpInsn(Opcodes.IFEQ, declined);
        ready.visitVarInsn(Opcodes.ALOAD, 0);
        ready.visitMethodInsn(Opcodes.INVOKESPECIAL, TARGET, RESOLVE_METHOD, "()[L" + PART + ";", false);
        ready.visitJumpInsn(Opcodes.IFNULL, declined);
        ready.visitVarInsn(Opcodes.ALOAD, 0);
        ready.visitFieldInsn(Opcodes.GETFIELD, TARGET, CHAIN_READY_FIELD, "Z");
        ready.visitInsn(Opcodes.IRETURN);
        ready.visitLabel(declined);
        ready.visitInsn(Opcodes.ICONST_0);
        ready.visitInsn(Opcodes.IRETURN);
        ready.visitMaxs(0, 0);
        ready.visitEnd();
        node.methods.add(ready);
    }

    private static void redirectPoseRotation(ClassNode node) {
        int redirected = 0;
        for (MethodNode method : node.methods) {
            if (!"applyLocalTransform".equals(method.name)
                || !("(FL" + MATRIX + ";)V").equals(method.desc)) continue;
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                 instruction = instruction.getNext()) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.getOpcode() == Opcodes.INVOKESTATIC
                    && "goblinbob/mobends/core/math/TransformUtils".equals(call.owner)
                    && "rotate".equals(call.name) && ROTATE_POSE_DESCRIPTOR.equals(call.desc)) {
                    call.owner = TARGET;
                    call.name = ROTATE_POSE_METHOD;
                    redirected++;
                }
            }
        }
        if (redirected != 1) {
            throw new IllegalStateException("MoBends ModelPart CPU rotation 调用图变化：" + redirected);
        }
        MethodNode method = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
            ROTATE_POSE_METHOD, ROTATE_POSE_DESCRIPTOR, null, null);
        MethodVisitor code = method;
        code.visitCode();
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "useQuaternionCache", "()Z", false);
        Label fallback = new Label();
        code.visitJumpInsn(Opcodes.IFEQ, fallback);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitTypeInsn(Opcodes.INSTANCEOF, MoBendsQuaternionAdapter.ACCESS);
        code.visitJumpInsn(Opcodes.IFEQ, fallback);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitTypeInsn(Opcodes.CHECKCAST, MoBendsQuaternionAdapter.ACCESS);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitMethodInsn(Opcodes.INVOKEINTERFACE, MoBendsQuaternionAdapter.ACCESS,
            MoBendsQuaternionAdapter.POSE_METHOD, "(Ljava/lang/Object;)V", true);
        code.visitInsn(Opcodes.RETURN);
        code.visitLabel(fallback);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, "goblinbob/mobends/core/math/TransformUtils",
            "rotate", ROTATE_POSE_DESCRIPTOR, false);
        code.visitInsn(Opcodes.RETURN);
        code.visitMaxs(0, 0);
        code.visitEnd();
        node.methods.add(method);
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
        code.visitInsn(Opcodes.ICONST_1);
        code.visitVarInsn(Opcodes.ISTORE, 5);
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
        Label chainPartProved = new Label();
        Label chainPartUnknown = new Label();
        code.visitVarInsn(Opcodes.ALOAD, 4);
        code.visitTypeInsn(Opcodes.INSTANCEOF, TRANSFORM_ACCESS);
        code.visitJumpInsn(Opcodes.IFEQ, chainPartUnknown);
        code.visitVarInsn(Opcodes.ALOAD, 4);
        code.visitTypeInsn(Opcodes.CHECKCAST, TRANSFORM_ACCESS);
        code.visitMethodInsn(Opcodes.INVOKEINTERFACE, TRANSFORM_ACCESS,
            "ice$stateBatchTransformClassReady", "()Z", true);
        code.visitJumpInsn(Opcodes.IFNE, chainPartProved);
        code.visitLabel(chainPartUnknown);
        code.visitInsn(Opcodes.ICONST_0);
        code.visitVarInsn(Opcodes.ISTORE, 5);
        code.visitLabel(chainPartProved);
        code.visitVarInsn(Opcodes.ALOAD, 4);
        code.visitMethodInsn(Opcodes.INVOKEINTERFACE, PART, "getParent", "()L" + PART + ";", true);
        code.visitVarInsn(Opcodes.ASTORE, 4);
        code.visitIincInsn(2, 1);
        code.visitJumpInsn(Opcodes.GOTO, fill);
        code.visitLabel(store);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitFieldInsn(Opcodes.PUTFIELD, TARGET, CHAIN_FIELD, "[L" + PART + ";");
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitVarInsn(Opcodes.ILOAD, 5);
        code.visitFieldInsn(Opcodes.PUTFIELD, TARGET, CHAIN_READY_FIELD, "Z");

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
        if (matrix) {
            code.visitVarInsn(Opcodes.ALOAD, 0);
            code.visitMethodInsn(Opcodes.INVOKESPECIAL, TARGET, RESOLVE_METHOD, "()[L" + PART + ";", false);
        } else {
            Label resolve = new Label();
            Label chainLoaded = new Label();
            code.visitMethodInsn(Opcodes.INVOKESTATIC, ModelSubclassStateBatchAdapter.BRIDGE,
                "isActive", "()Z", false);
            code.visitJumpInsn(Opcodes.IFEQ, resolve);
            code.visitVarInsn(Opcodes.ALOAD, 0);
            code.visitFieldInsn(Opcodes.GETFIELD, TARGET, CHAIN_FIELD, "[L" + PART + ";");
            code.visitJumpInsn(Opcodes.GOTO, chainLoaded);
            code.visitLabel(resolve);
            code.visitVarInsn(Opcodes.ALOAD, 0);
            code.visitMethodInsn(Opcodes.INVOKESPECIAL, TARGET, RESOLVE_METHOD, "()[L" + PART + ";", false);
            code.visitLabel(chainLoaded);
        }
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

    private static void wrapRender(ClassNode node, MethodNode original, boolean justPart,
                                   boolean batchReviewed) {
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
        Label scopeStart = new Label();
        Label scopeEnd = new Label();
        Label scopeHandler = new Label();
        if (batchReviewed) {
            code.visitVarInsn(Opcodes.ALOAD, 0);
            code.visitMethodInsn(Opcodes.INVOKESTATIC, ModelSubclassStateBatchAdapter.BRIDGE,
                "begin", "(Lnet/minecraft/client/model/ModelRenderer;)Z", false);
            code.visitVarInsn(Opcodes.ISTORE, 5);
            code.visitLabel(scopeStart);
        }
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
        if (batchReviewed) {
            code.visitLabel(scopeEnd);
            ModelSubclassStateBatchAdapter.emitEnd(wrapper, 5);
        }
        code.visitInsn(Opcodes.RETURN);
        if (batchReviewed) {
            code.visitLabel(scopeHandler);
            ModelSubclassStateBatchAdapter.emitFailure(wrapper, 5, 6);
            code.visitTryCatchBlock(scopeStart, scopeEnd, scopeHandler, "java/lang/Throwable");
        }
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
