package dev.rlcraft.ice.hooks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Scope memoization to Recurrent Complex's local, immutable cloud value field. */
final class RecurrentCloudFieldAdapter implements OptimizerBytecodeAdapter {
    static final String FIELD = "ivorius/ivtoolkit/random/BlurredValueField";
    static final String CACHED = "dev/rlcraft/ice/optimizer/compat/recurrent/RecurrentCloudField";

    @Override public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, 0);
        int news = 0, constructors = 0, queries = 0;
        for (MethodNode method : node.methods) {
            if (method.name.equals("buildCloud")) {
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (instruction instanceof TypeInsnNode && instruction.getOpcode() == Opcodes.NEW
                        && FIELD.equals(((TypeInsnNode) instruction).desc)) {
                        ((TypeInsnNode) instruction).desc = CACHED; news++;
                    } else if (instruction instanceof MethodInsnNode) {
                        MethodInsnNode call = (MethodInsnNode) instruction;
                        if (call.owner.equals(FIELD) && call.name.equals("<init>") && call.desc.equals("([I)V")) {
                            call.owner = CACHED; constructors++;
                        }
                    }
                }
            } else if (method.name.equals("lambda$buildCloud$1")) {
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (!(instruction instanceof MethodInsnNode)) continue;
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (!call.owner.equals(FIELD) || !call.name.equals("getValue") || !call.desc.equals("([I)D")) continue;
                    AbstractInsnNode start = call.getPrevious();
                    while (start != null && !(start instanceof IntInsnNode && start.getOpcode() == Opcodes.NEWARRAY)) start = start.getPrevious();
                    if (start == null) throw new IllegalStateException("Missing cloud coordinate array");
                    start = previous(previous(start)); // field receiver before ICONST_3 / NEWARRAY
                    if (!(start instanceof VarInsnNode) || start.getOpcode() != Opcodes.ALOAD)
                        throw new IllegalStateException("Changed cloud field receiver");
                    List<AbstractInsnNode> coordinates = new ArrayList<AbstractInsnNode>();
                    for (AbstractInsnNode p = start; p != call; p = p.getNext()) {
                        if (p instanceof MethodInsnNode && ((MethodInsnNode) p).desc.equals("()I")) {
                            AbstractInsnNode load = previous(p);
                            if (!(load instanceof VarInsnNode) || load.getOpcode() != Opcodes.ALOAD)
                                throw new IllegalStateException("Changed cloud coordinate receiver");
                            coordinates.add(load); coordinates.add(p);
                        }
                    }
                    if (coordinates.size() != 6) throw new IllegalStateException("Changed cloud coordinates");
                    InsnList replacement = new InsnList();
                    replacement.add(start.clone(new HashMap<LabelNode, LabelNode>()));
                    replacement.add(new TypeInsnNode(Opcodes.CHECKCAST, CACHED));
                    for (AbstractInsnNode coordinate : coordinates) replacement.add(coordinate.clone(new HashMap<LabelNode, LabelNode>()));
                    replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CACHED, "ice$getCloudValue", "(III)D", false));
                    method.instructions.insertBefore(start, replacement);
                    for (AbstractInsnNode p = start; p != call;) {
                        AbstractInsnNode following = p.getNext();
                        method.instructions.remove(p); p = following;
                    }
                    method.instructions.remove(call);
                    queries++;
                }
            }
        }
        if (news != 1 || constructors != 1 || queries != 1)
            throw new IllegalStateException("Changed Recurrent cloud field shape " + news + "/" + constructors + "/" + queries);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static AbstractInsnNode previous(AbstractInsnNode node) {
        do { node = node.getPrevious(); } while (node != null && node.getOpcode() < 0);
        return node;
    }
}
