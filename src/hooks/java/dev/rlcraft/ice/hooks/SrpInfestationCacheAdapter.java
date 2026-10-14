package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Patches the SRPMixins helpers after their merge into ParasiteEventWorld. */
final class SrpInfestationCacheAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "com/dhanantry/scapeandrunparasites/util/ParasiteEventWorld";
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/srp/SrpInfestationCacheBridge";

    @Override public byte[] transform(String name, byte[] bytes, TargetSpec target) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, 0);
        if (patch(node) == 0) throw new OptimizerAdapterSkippedException("SRPMixins infestation helpers are not present");
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    static int patch(ClassNode node) {
        int patches = 0;
        for (MethodNode method : node.methods) {
            if (!method.name.contains("blockIsBlacklistedForInfestation")
                && !method.name.contains("blockIsBlacklistedForBiome")) continue;
            MethodInsnNode stream = null;
            MethodInsnNode match = null;
            int nameLocal = -1;
            boolean containsPredicate = false;
            boolean installed = false;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (BRIDGE.equals(call.owner) && "matches".equals(call.name)) installed = true;
                    if ("java/util/Arrays".equals(call.owner) && "stream".equals(call.name)
                        && "([Ljava/lang/Object;)Ljava/util/stream/Stream;".equals(call.desc)) stream = call;
                    if (stream != null && "java/util/stream/Stream".equals(call.owner)
                        && "anyMatch".equals(call.name)) { match = call; break; }
                }
                if (stream != null && instruction instanceof VarInsnNode
                    && instruction.getOpcode() == Opcodes.ALOAD) nameLocal = ((VarInsnNode) instruction).var;
                if (stream != null && instruction instanceof InvokeDynamicInsnNode) {
                    InvokeDynamicInsnNode dynamic = (InvokeDynamicInsnNode) instruction;
                    for (Object argument : dynamic.bsmArgs) {
                        if (argument instanceof org.objectweb.asm.Handle) {
                            org.objectweb.asm.Handle handle = (org.objectweb.asm.Handle) argument;
                            containsPredicate |= "java/lang/String".equals(handle.getOwner())
                                && "contains".equals(handle.getName());
                        }
                    }
                }
            }
            if (installed) continue;
            if (stream == null || match == null || nameLocal < 0 || !containsPredicate)
                throw new IllegalStateException("SRP infestation name predicate changed: " + method.name);
            InsnList replacement = new InsnList();
            replacement.add(new VarInsnNode(Opcodes.ALOAD, nameLocal));
            replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "matches",
                "([Ljava/lang/String;Ljava/lang/String;)Z", false));
            method.instructions.insertBefore(stream, replacement);
            AbstractInsnNode instruction = stream;
            while (instruction != match) {
                AbstractInsnNode next = instruction.getNext();
                method.instructions.remove(instruction);
                instruction = next;
            }
            method.instructions.remove(match);
            patches++;
        }
        return patches;
    }
}
