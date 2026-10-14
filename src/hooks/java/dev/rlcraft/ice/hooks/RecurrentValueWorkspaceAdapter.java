package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/** Replaces only the two ruins fields, including their NBT-loading constructor references. */
final class RecurrentValueWorkspaceAdapter implements OptimizerBytecodeAdapter {
    static final String RUINS = "ivorius/reccomplex/world/gen/feature/structure/generic/transformers/TransformerRuins";
    static final String INSTANCE = RUINS + "$InstanceData";
    static final String FIELD = "ivorius/ivtoolkit/random/BlurredValueField";
    static final String WORKSPACE = "dev/rlcraft/ice/optimizer/compat/recurrent/RecurrentValueField";

    @Override public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, 0);
        int allocations = 0, constructors = 0, references = 0;
        for (MethodNode method : node.methods) {
            if (RUINS.equals(node.name) && method.name.equals("prepareInstanceData")) {
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (instruction instanceof TypeInsnNode && instruction.getOpcode() == Opcodes.NEW
                        && FIELD.equals(((TypeInsnNode) instruction).desc)) {
                        ((TypeInsnNode) instruction).desc = WORKSPACE;
                        allocations++;
                    } else if (instruction instanceof MethodInsnNode) {
                        MethodInsnNode call = (MethodInsnNode) instruction;
                        if (FIELD.equals(call.owner) && call.name.equals("<init>")
                            && call.desc.equals("([I)V")) {
                            call.owner = WORKSPACE;
                            constructors++;
                        }
                    }
                }
            } else if (INSTANCE.equals(node.name) && method.name.equals("<init>")
                       && method.desc.equals("(Lnet/minecraft/nbt/NBTTagCompound;)V")) {
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (!(instruction instanceof InvokeDynamicInsnNode)) continue;
                    InvokeDynamicInsnNode factory = (InvokeDynamicInsnNode) instruction;
                    for (int index = 0; index < factory.bsmArgs.length; index++) {
                        Object argument = factory.bsmArgs[index];
                        if (!(argument instanceof Handle)) continue;
                        Handle handle = (Handle) argument;
                        if (handle.getTag() == Opcodes.H_NEWINVOKESPECIAL
                            && FIELD.equals(handle.getOwner()) && handle.getName().equals("<init>")
                            && handle.getDesc().equals("()V")) {
                            factory.bsmArgs[index] = new Handle(Opcodes.H_NEWINVOKESPECIAL,
                                WORKSPACE, "<init>", "()V", false);
                            references++;
                        }
                    }
                }
            }
        }
        if (RUINS.equals(node.name) ? allocations != 2 || constructors != 2 || references != 0
            : !INSTANCE.equals(node.name) || allocations != 0 || constructors != 0 || references != 2)
            throw new IllegalStateException("Changed Recurrent ruins value fields "
                + allocations + "/" + constructors + "/" + references);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
