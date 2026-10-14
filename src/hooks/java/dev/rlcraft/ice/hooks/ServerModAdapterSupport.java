package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/** Shared bytecode plumbing for exact optional-mod event adapters. */
final class ServerModAdapterSupport {
    private ServerModAdapterSupport() { }
    static MethodNode require(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) if (name.equals(method.name) && descriptor.equals(method.desc)) return method;
        throw new IllegalStateException("Missing reviewed method " + node.name + '.' + name + descriptor);
    }
    static MethodNode copy(MethodNode source, String name) {
        MethodNode result = new MethodNode(Opcodes.ASM5, source.access, name, source.desc,
            source.signature, source.exceptions.toArray(new String[source.exceptions.size()]));
        source.accept(result);
        result.name = name;
        result.access = (source.access & Opcodes.ACC_STATIC) | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
        result.visibleAnnotations = null;
        result.invisibleAnnotations = null;
        return result;
    }
    static void route(ClassNode node, MethodNode method, MethodNode optimized, String bridge, String enabled,
                      boolean scoped) {
        MethodNode original = copy(method, "ice$original$" + method.name);
        node.methods.add(original);
        node.methods.add(optimized);
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        InsnList code = method.instructions;
        LabelNode fast = new LabelNode();
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, bridge, enabled, "()Z", false));
        code.add(new JumpInsnNode(Opcodes.IFNE, fast));
        call(code, node.name, original);
        code.add(new InsnNode(Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN)));
        code.add(fast);
        if (!scoped) {
            call(code, node.name, optimized);
            code.add(new InsnNode(Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN)));
            return;
        }
        if (Type.getReturnType(method.desc).getSort() != Type.VOID) throw new IllegalStateException("Scoped event must return void");
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, bridge, "begin", "()V", false));
        LabelNode start = new LabelNode(), end = new LabelNode(), failure = new LabelNode();
        code.add(start);
        call(code, node.name, optimized);
        code.add(end);
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, bridge, "end", "()V", false));
        code.add(new InsnNode(Opcodes.RETURN));
        code.add(failure);
        int local = argumentSlots(method);
        code.add(new VarInsnNode(Opcodes.ASTORE, local));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, bridge, "end", "()V", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, local));
        code.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, failure, null));
    }
    static int argumentSlots(MethodNode method) {
        int n = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (Type type : Type.getArgumentTypes(method.desc)) n += type.getSize();
        return n;
    }
    private static void call(InsnList code, String owner, MethodNode method) {
        boolean statik = (method.access & Opcodes.ACC_STATIC) != 0;
        int local = 0;
        if (!statik) code.add(new VarInsnNode(Opcodes.ALOAD, local++));
        for (Type type : Type.getArgumentTypes(method.desc)) {
            code.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), local));
            local += type.getSize();
        }
        code.add(new MethodInsnNode(statik ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL,
            owner, method.name, method.desc, false));
    }
    static byte[] write(ClassReader reader, ClassNode node) {
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override protected String getCommonSuperClass(String left, String right) { return "java/lang/Object"; }
        };
        node.accept(writer);
        return writer.toByteArray();
    }
}
