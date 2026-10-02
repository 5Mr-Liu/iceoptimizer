package dev.rlcraft.ice.hooks;

import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Captures ModelRenderer compilation and replaces only its call-list emitters. */
final class ModelRendererVboAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "net/minecraft/client/model/ModelRenderer";
    static final String COMPILE = "func_78788_d";
    static final String ORIGINAL = "rlcraftIce$compileDisplayListOriginal";
    static final String COMPILE_DESCRIPTOR = "(F)V";
    static final String GL_STATE_MANAGER =
        "net/minecraft/client/renderer/GlStateManager";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/model/ModelMeshCaptureBridge";
    static final String STATE_BATCH_BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/model/ModelStateBatchBridge";
    static final String STATE_BATCH_ACCESS =
        "dev/rlcraft/ice/optimizer/compat/model/ModelStateBatchAccess";
    static final String RENDER_ORIGINAL = "rlcraftIce$renderStateBatchOriginal";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(node.name)) {
            throw new IllegalStateException("ModelRenderer target changed: " + node.name);
        }
        // This proof precedes emitter replacement. Exact Java class identity
        // alone cannot authenticate OptiFine/model-update extensions.
        int stateBatchShape = ModelStateBatchMethodProof.reviewedShape(node);
        MethodNode compile = null;
        int matches = 0;
        int callLists = 0;
        for (MethodNode method : node.methods) {
            if (COMPILE.equals(method.name) && COMPILE_DESCRIPTOR.equals(method.desc)) {
                compile = method;
                matches++;
            }
            if (ORIGINAL.equals(method.name)) {
                throw new IllegalStateException("ModelRenderer already adapted");
            }
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.getOpcode() == Opcodes.INVOKESTATIC
                    && GL_STATE_MANAGER.equals(call.owner)
                    && "func_179148_o".equals(call.name) && "(I)V".equals(call.desc)) {
                    call.owner = BRIDGE;
                    call.name = "callList";
                    callLists++;
                }
            }
        }
        if (matches != 1 || compile == null || callLists != 4
            || (compile.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT
                | Opcodes.ACC_NATIVE)) != 0) {
            throw new IllegalStateException("ModelRenderer compile/emitter graph changed: compile="
                + matches + ", callLists=" + callLists);
        }
        int access = compile.access;
        String signature = compile.signature;
        List<String> exceptions = compile.exceptions;
        compile.name = ORIGINAL;
        compile.access |= Opcodes.ACC_SYNTHETIC;
        node.methods.add(wrapper(node.name, access, signature, exceptions));
        if (stateBatchShape != 0) addStateBatch(node, stateBatchShape);
        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void addStateBatch(ClassNode node, int shape) {
        MethodNode render = ModelStateBatchMethodProof.method(node, "func_78785_a", "(F)V");
        int access = render.access;
        String signature = render.signature;
        List<String> exceptions = render.exceptions;
        render.name = RENDER_ORIGINAL;
        render.access |= Opcodes.ACC_SYNTHETIC;
        node.interfaces.add(STATE_BATCH_ACCESS);
        node.methods.add(stateBatchRenderWrapper(access, signature, exceptions));

        MethodNode displayList = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, "ice$stateBatchDisplayList",
            "()I", null, null);
        displayList.visitCode();
        displayList.visitVarInsn(Opcodes.ALOAD, 0);
        displayList.visitFieldInsn(Opcodes.GETFIELD, TARGET, "field_78811_r", "I");
        displayList.visitInsn(Opcodes.IRETURN);
        displayList.visitMaxs(0, 0);
        displayList.visitEnd();
        node.methods.add(displayList);

        MethodNode ready = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, "ice$stateBatchNodeReady",
            "()Z", null, null);
        ready.visitCode();
        Label rejected = new Label();
        ready.visitVarInsn(Opcodes.ALOAD, 0);
        ready.visitFieldInsn(Opcodes.GETFIELD, TARGET, "field_78812_q", "Z");
        ready.visitJumpInsn(Opcodes.IFEQ, rejected);
        if (shape == 2) {
            ready.visitVarInsn(Opcodes.ALOAD, 0);
            ready.visitFieldInsn(Opcodes.GETFIELD, TARGET, "textureLocation",
                "Lnet/minecraft/util/ResourceLocation;");
            ready.visitJumpInsn(Opcodes.IFNONNULL, rejected);
            ready.visitVarInsn(Opcodes.ALOAD, 0);
            ready.visitFieldInsn(Opcodes.GETFIELD, TARGET, "modelUpdater",
                "Lnet/optifine/entity/model/anim/ModelUpdater;");
            ready.visitJumpInsn(Opcodes.IFNONNULL, rejected);
            ready.visitVarInsn(Opcodes.ALOAD, 0);
            ready.visitFieldInsn(Opcodes.GETFIELD, TARGET, "spriteList", "Ljava/util/List;");
            ready.visitMethodInsn(Opcodes.INVOKESTATIC, STATE_BATCH_BRIDGE,
                "isEmptyCanonicalList", "(Ljava/lang/Object;)Z", false);
            ready.visitJumpInsn(Opcodes.IFEQ, rejected);
            ready.visitVarInsn(Opcodes.ALOAD, 0);
            ready.visitFieldInsn(Opcodes.GETFIELD, TARGET, "countResetDisplayList", "I");
            ready.visitFieldInsn(Opcodes.GETSTATIC, "net/optifine/shaders/Shaders",
                "countResetDisplayLists", "I");
            ready.visitJumpInsn(Opcodes.IF_ICMPNE, rejected);
        }
        ready.visitInsn(Opcodes.ICONST_1);
        ready.visitInsn(Opcodes.IRETURN);
        ready.visitLabel(rejected);
        ready.visitInsn(Opcodes.ICONST_0);
        ready.visitInsn(Opcodes.IRETURN);
        ready.visitMaxs(0, 0);
        ready.visitEnd();
        node.methods.add(ready);
    }

    private static MethodNode stateBatchRenderWrapper(int access, String signature,
                                                      List<String> exceptions) {
        MethodNode method = new MethodNode(Opcodes.ASM5, access, "func_78785_a", "(F)V",
            signature, exceptions == null ? null : exceptions.toArray(new String[exceptions.size()]));
        method.visitCode();
        Label scoped = new Label();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, TARGET, "field_78805_m", "Ljava/util/List;");
        method.visitJumpInsn(Opcodes.IFNONNULL, scoped);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.FLOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, TARGET, RENDER_ORIGINAL, "(F)V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitLabel(scoped);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, STATE_BATCH_BRIDGE, "begin",
            "(Lnet/minecraft/client/model/ModelRenderer;)Z", false);
        method.visitVarInsn(Opcodes.ISTORE, 2);
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        Label returned = new Label();
        Label rethrow = new Label();
        method.visitLabel(start);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.FLOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, TARGET, RENDER_ORIGINAL, "(F)V", false);
        method.visitLabel(end);
        method.visitVarInsn(Opcodes.ILOAD, 2);
        method.visitJumpInsn(Opcodes.IFEQ, returned);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, STATE_BATCH_BRIDGE, "end", "()V", false);
        method.visitLabel(returned);
        method.visitInsn(Opcodes.RETURN);
        method.visitLabel(handler);
        method.visitVarInsn(Opcodes.ASTORE, 3);
        method.visitVarInsn(Opcodes.ILOAD, 2);
        method.visitJumpInsn(Opcodes.IFEQ, rethrow);
        method.visitVarInsn(Opcodes.ALOAD, 3);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, STATE_BATCH_BRIDGE,
            "endAfterFailure", "(Ljava/lang/Throwable;)V", false);
        method.visitLabel(rethrow);
        method.visitVarInsn(Opcodes.ALOAD, 3);
        method.visitInsn(Opcodes.ATHROW);
        method.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
        method.visitMaxs(0, 0);
        method.visitEnd();
        return method;
    }

    private static MethodNode wrapper(String owner, int access, String signature,
                                      List<String> exceptions) {
        MethodNode method = new MethodNode(Opcodes.ASM5, access, COMPILE,
            COMPILE_DESCRIPTOR, signature, exceptions == null ? null
                : exceptions.toArray(new String[exceptions.size()]));
        MethodVisitor code = method;
        code.visitCode();
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "begin",
            "(Ljava/lang/Object;)V", false);
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        code.visitLabel(start);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitVarInsn(Opcodes.FLOAD, 1);
        code.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, ORIGINAL,
            COMPILE_DESCRIPTOR, false);
        code.visitLabel(end);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "finish",
            "(Ljava/lang/Object;)V", false);
        code.visitInsn(Opcodes.RETURN);
        code.visitLabel(handler);
        code.visitVarInsn(Opcodes.ASTORE, 2);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "cancel",
            "(Ljava/lang/Object;)V", false);
        code.visitVarInsn(Opcodes.ALOAD, 2);
        code.visitInsn(Opcodes.ATHROW);
        code.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
        code.visitMaxs(0, 0);
        code.visitEnd();
        return method;
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String left, String right) {
            return "java/lang/Object";
        }
    }
}
