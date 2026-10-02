package dev.rlcraft.ice.hooks;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.IdentityHashMap;
import java.util.Map;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** One-time proof of the reviewed vanilla/G5 render graph before holding client state. */
final class ModelStateBatchMethodProof {
    private static final String VANILLA_RENDER =
        "7d22abab7db13975450b2b53645663ba869ee35a85762401f9a4ca5d4d925566";
    private static final String G5_RENDER =
        "4cc14605726118aaedebea34093b60d21b9557d9ec2f61f1e4853a4370ac44f0";
    private static final String G5_RESET =
        "570a08aa166132e1cd01abd21537f2ecffba0d7f409b43ba49735219fc5ac8f3";

    private ModelStateBatchMethodProof() { }

    static int reviewedShape(ClassNode owner) {
        MethodNode render = method(owner, "func_78785_a", "(F)V");
        if (render == null) return 0;
        String digest = digest(render);
        if (VANILLA_RENDER.equals(digest)) return 1;
        MethodNode reset = method(owner, "checkResetDisplayList", "()V");
        return G5_RENDER.equals(digest) && reset != null
            && G5_RESET.equals(digest(reset)) ? 2 : 0;
    }

    static MethodNode method(ClassNode owner, String name, String descriptor) {
        for (MethodNode value : owner.methods) {
            if (name.equals(value.name) && descriptor.equals(value.desc)) return value;
        }
        return null;
    }

    static String digest(MethodNode method) {
        return digest(method, method.instructions.getFirst(), null, true);
    }

    /** Hash only a reviewed region; callers must prove its control-flow boundaries. */
    static String digestRegion(MethodNode method, AbstractInsnNode first,
                               AbstractInsnNode endExclusive) {
        return digest(method, first, endExclusive, false);
    }

    private static String digest(MethodNode method, AbstractInsnNode first,
                                 AbstractInsnNode endExclusive, boolean catches) {
        Map<LabelNode, Integer> labels = new IdentityHashMap<LabelNode, Integer>();
        int ordinal = 0;
        for (AbstractInsnNode instruction = first; instruction != endExclusive;
             instruction = instruction.getNext()) {
            if (instruction instanceof LabelNode) labels.put((LabelNode) instruction,
                Integer.valueOf(ordinal));
            else if (instruction.getOpcode() >= 0) ordinal++;
        }
        StringBuilder graph = new StringBuilder(4096);
        graph.append(method.access).append('|').append(method.desc).append('\n');
        for (AbstractInsnNode instruction = first; instruction != endExclusive;
             instruction = instruction.getNext()) {
            if (instruction.getOpcode() < 0) continue;
            graph.append(instruction.getOpcode());
            if (instruction instanceof VarInsnNode) {
                graph.append(':').append(((VarInsnNode) instruction).var);
            } else if (instruction instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) instruction;
                graph.append(':').append(field.owner).append(':').append(field.name)
                    .append(':').append(field.desc);
            } else if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                graph.append(':').append(call.owner).append(':').append(call.name)
                    .append(':').append(call.desc).append(':').append(call.itf);
            } else if (instruction instanceof JumpInsnNode) {
                graph.append(':').append(labels.get(((JumpInsnNode) instruction).label));
            } else if (instruction instanceof LdcInsnNode) {
                Object constant = ((LdcInsnNode) instruction).cst;
                graph.append(':').append(constant.getClass().getName()).append(':')
                    .append(constant instanceof Float
                        ? Integer.valueOf(Float.floatToRawIntBits(((Float) constant).floatValue()))
                        : constant);
            } else if (instruction instanceof IincInsnNode) {
                IincInsnNode increment = (IincInsnNode) instruction;
                graph.append(':').append(increment.var).append(':').append(increment.incr);
            } else if (instruction instanceof IntInsnNode) {
                graph.append(':').append(((IntInsnNode) instruction).operand);
            } else if (instruction instanceof TypeInsnNode) {
                graph.append(':').append(((TypeInsnNode) instruction).desc);
            } else if (instruction.getType() != AbstractInsnNode.INSN) {
                // Reviewed render methods contain no switch, invokedynamic,
                // multi-array or other instruction kind; never certify an extension.
                return "unsupported";
            }
            graph.append('\n');
        }
        if (catches) {
            for (TryCatchBlockNode block : method.tryCatchBlocks) {
                graph.append("catch:").append(labels.get(block.start)).append(':')
                    .append(labels.get(block.end)).append(':').append(labels.get(block.handler))
                    .append(':').append(block.type).append('\n');
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                graph.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) hex.append(Character.forDigit((value >>> 4) & 15, 16))
                .append(Character.forDigit(value & 15, 16));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
