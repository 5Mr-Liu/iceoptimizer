package dev.rlcraft.ice.hooks;

import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Bytecode shared by the two reviewed model-subclass scopes; no runtime graph lookup. */
final class ModelSubclassStateBatchAdapter {
    static final String ACCESS =
        "dev/rlcraft/ice/optimizer/compat/model/ModelStateBatchSubclassAccess";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/model/ModelStateBatchBridge";

    private ModelSubclassStateBatchAdapter() { }

    static boolean reviewed(ClassNode node, String name, String descriptor, String digest) {
        MethodNode method = ModelStateBatchMethodProof.method(node, name, descriptor);
        return method != null && digest.equals(ModelStateBatchMethodProof.digest(method));
    }

    static void addExactClassReady(ClassNode node, String name) {
        if (ModelStateBatchMethodProof.method(node, name, "()Z") != null) {
            throw new IllegalStateException("model subclass already adapted: " + name);
        }
        MethodNode method = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, name, "()Z", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass",
            "()Ljava/lang/Class;", false);
        method.visitLdcInsn(Type.getObjectType(node.name));
        Label declined = new Label();
        method.visitJumpInsn(Opcodes.IF_ACMPNE, declined);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.IRETURN);
        method.visitLabel(declined);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        node.methods.add(method);
    }

    static void addDisplayListAccess(ClassNode node, String displayList, String compiled) {
        node.interfaces.add(ACCESS);
        addExactClassReady(node, "ice$stateBatchRenderClassReady");
        MethodNode id = new MethodNode(Opcodes.ASM5, Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
            "ice$stateBatchDisplayList", "()I", null, null);
        id.visitCode();
        id.visitVarInsn(Opcodes.ALOAD, 0);
        id.visitFieldInsn(Opcodes.GETFIELD, node.name, displayList, "I");
        id.visitInsn(Opcodes.IRETURN);
        id.visitMaxs(0, 0);
        id.visitEnd();
        node.methods.add(id);
        MethodNode ready = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
            "ice$stateBatchNodeReady", "()Z", null, null);
        ready.visitCode();
        ready.visitVarInsn(Opcodes.ALOAD, 0);
        ready.visitFieldInsn(Opcodes.GETFIELD, node.name, compiled, "Z");
        ready.visitInsn(Opcodes.IRETURN);
        ready.visitMaxs(0, 0);
        ready.visitEnd();
        node.methods.add(ready);
    }

    static void wrapRender(ClassNode node, MethodNode original) {
        String name = original.name;
        String renamed = "rlcraftIce$" + name + "SubclassBatchOriginal";
        int access = original.access;
        original.name = renamed;
        original.access |= Opcodes.ACC_SYNTHETIC;
        MethodNode wrapper = new MethodNode(Opcodes.ASM5, access, name, "(F)V",
            original.signature, original.exceptions == null ? null
                : original.exceptions.toArray(new String[original.exceptions.size()]));
        wrapper.visitCode();
        wrapper.visitVarInsn(Opcodes.ALOAD, 0);
        wrapper.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "begin",
            "(Lnet/minecraft/client/model/ModelRenderer;)Z", false);
        wrapper.visitVarInsn(Opcodes.ISTORE, 2);
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        wrapper.visitLabel(start);
        wrapper.visitVarInsn(Opcodes.ALOAD, 0);
        wrapper.visitVarInsn(Opcodes.FLOAD, 1);
        wrapper.visitMethodInsn(Opcodes.INVOKESPECIAL, node.name, renamed, "(F)V", false);
        wrapper.visitLabel(end);
        emitEnd(wrapper, 2);
        wrapper.visitInsn(Opcodes.RETURN);
        wrapper.visitLabel(handler);
        emitFailure(wrapper, 2, 3);
        wrapper.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
        wrapper.visitMaxs(0, 0);
        wrapper.visitEnd();
        node.methods.add(wrapper);
    }

    static void wrapCompiler(ClassNode node, MethodNode original, String renamed) {
        String name = original.name;
        int access = original.access;
        original.name = renamed;
        original.access |= Opcodes.ACC_SYNTHETIC;
        MethodNode wrapper = new MethodNode(Opcodes.ASM5, access, name, "(F)V",
            original.signature, original.exceptions == null ? null
                : original.exceptions.toArray(new String[original.exceptions.size()]));
        String capture = ModelRendererVboAdapter.BRIDGE;
        wrapper.visitCode();
        wrapper.visitVarInsn(Opcodes.ALOAD, 0);
        wrapper.visitMethodInsn(Opcodes.INVOKESTATIC, capture, "begin", "(Ljava/lang/Object;)V", false);
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        wrapper.visitLabel(start);
        wrapper.visitVarInsn(Opcodes.ALOAD, 0);
        wrapper.visitVarInsn(Opcodes.FLOAD, 1);
        wrapper.visitMethodInsn(Opcodes.INVOKESPECIAL, node.name, renamed, "(F)V", false);
        wrapper.visitLabel(end);
        wrapper.visitVarInsn(Opcodes.ALOAD, 0);
        wrapper.visitMethodInsn(Opcodes.INVOKESTATIC, capture, "finish", "(Ljava/lang/Object;)V", false);
        wrapper.visitInsn(Opcodes.RETURN);
        wrapper.visitLabel(handler);
        wrapper.visitVarInsn(Opcodes.ASTORE, 2);
        wrapper.visitVarInsn(Opcodes.ALOAD, 0);
        wrapper.visitMethodInsn(Opcodes.INVOKESTATIC, capture, "cancel", "(Ljava/lang/Object;)V", false);
        wrapper.visitVarInsn(Opcodes.ALOAD, 2);
        wrapper.visitInsn(Opcodes.ATHROW);
        wrapper.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
        wrapper.visitMaxs(0, 0);
        wrapper.visitEnd();
        node.methods.add(wrapper);
    }

    static void emitEnd(MethodNode method, int scopeLocal) {
        Label returned = new Label();
        method.visitVarInsn(Opcodes.ILOAD, scopeLocal);
        method.visitJumpInsn(Opcodes.IFEQ, returned);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "end", "()V", false);
        method.visitLabel(returned);
    }

    static void emitFailure(MethodNode method, int scopeLocal, int failureLocal) {
        method.visitVarInsn(Opcodes.ASTORE, failureLocal);
        Label rethrow = new Label();
        method.visitVarInsn(Opcodes.ILOAD, scopeLocal);
        method.visitJumpInsn(Opcodes.IFEQ, rethrow);
        method.visitVarInsn(Opcodes.ALOAD, failureLocal);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE,
            "endAfterFailure", "(Ljava/lang/Throwable;)V", false);
        method.visitLabel(rethrow);
        method.visitVarInsn(Opcodes.ALOAD, failureLocal);
        method.visitInsn(Opcodes.ATHROW);
    }
}
