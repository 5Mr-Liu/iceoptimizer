package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Keeps every World/Chunk virtual call, predicate, result-list write and candidate order. */
final class SrpArchitectQueryAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "net/minecraft/world/chunk/Chunk";
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/srp/SrpArchitectQueryBridge";
    static final String DESC = "(Ljava/lang/Class;Lnet/minecraft/util/math/AxisAlignedBB;Ljava/util/List;Lcom/google/common/base/Predicate;)V";
    @Override public byte[] transform(String name, byte[] bytes, TargetSpec target) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode(Opcodes.ASM5); reader.accept(node, 0);
        MethodNode method = null;
        for (MethodNode candidate : node.methods) if (DESC.equals(candidate.desc)
            && ("getEntitiesOfTypeWithinAABB".equals(candidate.name) || "func_177430_a".equals(candidate.name))) method = candidate;
        if (method == null) throw new IllegalStateException("Chunk entity query changed");
        MethodNode original = ServerModAdapterSupport.copy(method, "ice$original$architectQuery");
        MethodNode fast = ServerModAdapterSupport.copy(method, "ice$fast$architectQuery");
        int replaced = 0;
        for (AbstractInsnNode instruction : fast.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (!SrpArchitectBucketAdapter.TARGET.equals(call.owner)
                || !"(Ljava/lang/Class;)Ljava/lang/Iterable;".equals(call.desc)) continue;
            AbstractInsnNode next = call.getNext();
            while (next != null && next.getOpcode() < 0) next = next.getNext();
            if (!(next instanceof MethodInsnNode) || !"iterator".equals(((MethodInsnNode) next).name))
                throw new IllegalStateException("Class bucket iteration changed");
            fast.instructions.remove(next);
            fast.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "iterator",
                "(Lnet/minecraft/util/ClassInheritanceMultiMap;Ljava/lang/Class;)Ljava/util/Iterator;", false));
            replaced++;
        }
        if (replaced != 1) throw new IllegalStateException("Expected one entity bucket iteration");
        node.methods.add(original); node.methods.add(fast);
        method.instructions.clear(); method.tryCatchBlocks.clear(); method.localVariables = null;
        LabelNode normal = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "isEnabled", "(Ljava/lang/Class;)Z", false));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, normal));
        invoke(method.instructions, node.name, fast.name); method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.instructions.add(normal); invoke(method.instructions, node.name, original.name);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        return ServerModAdapterSupport.write(reader, node);
    }
    private static void invoke(InsnList code, String owner, String method) {
        for (int local = 0; local < 5; local++) code.add(new VarInsnNode(Opcodes.ALOAD, local));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, method, DESC, false));
    }
}
