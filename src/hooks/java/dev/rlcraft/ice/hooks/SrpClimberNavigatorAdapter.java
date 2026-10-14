package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Changes only the pathfinder of SRP's two climbing navigator variants. */
final class SrpClimberNavigatorAdapter implements OptimizerBytecodeAdapter {
    static final String SRP_NAV = "com/dhanantry/scapeandrunparasites/util/PathNavigateClimberStatus";
    static final String VANILLA_NAV = "net/minecraft/pathfinding/PathNavigateClimber";
    static final String ICE_NAV = "dev/rlcraft/ice/optimizer/compat/srp/SrpPathNavigateClimber";
    static final String FINDER_METHOD = "func_179679_a";
    static final String FINDER_DESC = "()Lnet/minecraft/pathfinding/PathFinder;";
    private static final String NODE = "net/minecraft/pathfinding/NodeProcessor";
    private static final String FINDER = "net/minecraft/pathfinding/PathFinder";

    @Override
    public byte[] transform(String name, byte[] bytes, TargetSpec target) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, 0);
        if (SRP_NAV.equals(node.name)) {
            for (MethodNode method : node.methods) {
                if (FINDER_METHOD.equals(method.name) && FINDER_DESC.equals(method.desc)) {
                    throw new IllegalStateException("SRP climber already declares its pathfinder");
                }
            }
            MethodNode method = new MethodNode(Opcodes.ACC_PROTECTED, FINDER_METHOD,
                FINDER_DESC, null, null);
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ICE_NAV,
                "createProcessor", "()L" + NODE + ";", false));
            method.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD,
                "net/minecraft/pathfinding/PathNavigate", "field_179695_a", "L" + NODE + ";"));
            method.instructions.add(new TypeInsnNode(Opcodes.NEW, FINDER));
            method.instructions.add(new InsnNode(Opcodes.DUP));
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD,
                "net/minecraft/pathfinding/PathNavigate", "field_179695_a", "L" + NODE + ";"));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, FINDER,
                "<init>", "(L" + NODE + ";)V", false));
            method.instructions.add(new InsnNode(Opcodes.ARETURN));
            node.methods.add(method);
        } else {
            int allocations = 0;
            int constructors = 0;
            for (MethodNode method : node.methods) {
                if (!SrpParasiteNavigatorAdapter.METHOD.equals(method.name)
                    || !SrpParasiteNavigatorAdapter.DESCRIPTOR.equals(method.desc)) continue;
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (instruction.getOpcode() == Opcodes.NEW
                        && VANILLA_NAV.equals(((TypeInsnNode) instruction).desc)) {
                        ((TypeInsnNode) instruction).desc = ICE_NAV;
                        allocations++;
                    } else if (instruction instanceof MethodInsnNode) {
                        MethodInsnNode call = (MethodInsnNode) instruction;
                        if (call.getOpcode() == Opcodes.INVOKESPECIAL && VANILLA_NAV.equals(call.owner)
                            && "<init>".equals(call.name)
                            && SrpParasiteNavigatorAdapter.CONSTRUCTOR.equals(call.desc)) {
                            call.owner = ICE_NAV;
                            constructors++;
                        }
                    }
                }
            }
            if (allocations != 1 || constructors != 1) {
                throw new IllegalStateException("SRP climbing factory changed: " + node.name);
            }
        }
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
