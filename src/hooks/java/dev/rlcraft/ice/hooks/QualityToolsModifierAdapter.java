package dev.rlcraft.ice.hooks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** A narrow fast path inside the unchanged QualityTools attribute loop, including Baubles callers. */
final class QualityToolsModifierAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "com/tmtravlr/qualitytools/QualityToolsHelper";
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/entity/QualityToolsModifierBridge";
    static final String METHOD = "applyAttributesForSlot";
    static final String DESCRIPTOR = "(Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/item/ItemStack;"
        + "Ljava/lang/String;Lcom/google/common/collect/Multimap;)V";

    @Override public byte[] transform(String transformedName, byte[] original, TargetSpec target) {
        ClassReader reader = new ClassReader(original);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(node.name) || !TARGET.equals(transformedName.replace('.', '/'))) {
            throw new IllegalStateException("QualityTools helper target changed");
        }
        MethodNode method = null;
        for (MethodNode candidate : node.methods) {
            if (METHOD.equals(candidate.name) && DESCRIPTOR.equals(candidate.desc)) {
                if (method != null) throw new IllegalStateException("Duplicate QualityTools helper method");
                method = candidate;
            }
        }
        if (method == null || (method.access & Opcodes.ACC_STATIC) == 0 || !method.tryCatchBlocks.isEmpty()) {
            throw new IllegalStateException("QualityTools attribute method changed");
        }
        MethodInsnNode parse = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (BRIDGE.equals(call.owner)) throw new IllegalStateException("QualityTools modifier hook already installed");
            if (call.getOpcode() == Opcodes.INVOKESTATIC
                && "net/minecraft/entity/SharedMonsterAttributes".equals(call.owner)
                && "func_111259_a".equals(call.name)
                && "(Lnet/minecraft/nbt/NBTTagCompound;)Lnet/minecraft/entity/ai/attributes/AttributeModifier;".equals(call.desc)) {
                if (parse != null) throw new IllegalStateException("Multiple QualityTools modifier parsers");
                parse = call;
            }
        }
        AbstractInsnNode read = previousOpcode(parse);
        AbstractInsnNode index = previousOpcode(read);
        AbstractInsnNode list = previousOpcode(index);
        if (!(read instanceof MethodInsnNode) || read.getOpcode() != Opcodes.INVOKEVIRTUAL
            || !"net/minecraft/nbt/NBTTagList".equals(((MethodInsnNode) read).owner)
            || !"func_150305_b".equals(((MethodInsnNode) read).name)
            || !"(I)Lnet/minecraft/nbt/NBTTagCompound;".equals(((MethodInsnNode) read).desc)
            || !(index instanceof VarInsnNode) || index.getOpcode() != Opcodes.ILOAD
            || !(list instanceof VarInsnNode) || list.getOpcode() != Opcodes.ALOAD) {
            throw new IllegalStateException("QualityTools modifier NBT loop changed");
        }
        int indexLocal = ((VarInsnNode) index).var;
        IincInsnNode increment = null;
        for (AbstractInsnNode instruction = parse.getNext(); instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof IincInsnNode && ((IincInsnNode) instruction).var == indexLocal) {
                if (increment != null || ((IincInsnNode) instruction).incr != 1) {
                    throw new IllegalStateException("QualityTools modifier loop increment changed");
                }
                increment = (IincInsnNode) instruction;
            }
        }
        if (increment == null) throw new IllegalStateException("QualityTools modifier loop end missing");
        verifySkippedBody(parse, increment);

        LabelNode next = new LabelNode();
        method.instructions.insertBefore(increment, next);
        LabelNode originalParser = new LabelNode();
        InsnList fast = new InsnList();
        // Reuse the NBT already on the operand stack: a custom list must not be read twice.
        fast.add(new InsnNode(Opcodes.DUP));
        fast.add(new VarInsnNode(Opcodes.ALOAD, 3));
        fast.add(new VarInsnNode(Opcodes.ALOAD, ((VarInsnNode) list).var));
        fast.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "retainExistingModifier",
            "(Lnet/minecraft/nbt/NBTTagCompound;Lcom/google/common/collect/Multimap;Lnet/minecraft/nbt/NBTTagList;)Z", false));
        fast.add(new JumpInsnNode(Opcodes.IFEQ, originalParser));
        fast.add(new InsnNode(Opcodes.POP));
        fast.add(new JumpInsnNode(Opcodes.GOTO, next));
        fast.add(originalParser);
        method.instructions.insertBefore(parse, fast);

        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override protected String getCommonSuperClass(String left, String right) { return "java/lang/Object"; }
        };
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Every executable instruction bypassed by the hit must be the audited original body. */
    private static void verifySkippedBody(MethodInsnNode parse, IincInsnNode increment) {
        List<AbstractInsnNode> body = new ArrayList<AbstractInsnNode>();
        for (AbstractInsnNode instruction = parse; instruction != null; instruction = instruction.getNext()) {
            if (instruction.getOpcode() >= 0) body.add(instruction);
            if (instruction == increment) break;
        }
        List<String> actual = new ArrayList<String>();
        for (AbstractInsnNode instruction : body) {
            String value = Integer.toString(instruction.getOpcode());
            if (instruction instanceof VarInsnNode) value += " " + ((VarInsnNode) instruction).var;
            else if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                value += " " + call.owner + "." + call.name + call.desc + " " + call.itf;
            } else if (instruction instanceof LdcInsnNode) value += " " + ((LdcInsnNode) instruction).cst;
            else if (instruction instanceof JumpInsnNode) {
                AbstractInsnNode destination = ((JumpInsnNode) instruction).label;
                while (destination != null && destination.getOpcode() < 0) destination = destination.getNext();
                value += " " + body.indexOf(destination);
            } else if (instruction instanceof IincInsnNode) {
                value += " " + ((IincInsnNode) instruction).var + " " + ((IincInsnNode) instruction).incr;
            } else if (!(instruction instanceof InsnNode)) {
                throw new IllegalStateException("Unknown instruction in QualityTools modifier body");
            }
            actual.add(value);
        }
        List<String> expected = Arrays.asList(
            "184 net/minecraft/entity/SharedMonsterAttributes.func_111259_a(Lnet/minecraft/nbt/NBTTagCompound;)Lnet/minecraft/entity/ai/attributes/AttributeModifier; false",
            "58 9", "25 7", "21 8",
            "182 net/minecraft/nbt/NBTTagList.func_150305_b(I)Lnet/minecraft/nbt/NBTTagCompound; false",
            "18 AttributeName", "182 net/minecraft/nbt/NBTTagCompound.func_74779_i(Ljava/lang/String;)Ljava/lang/String; false",
            "58 10", "25 3", "25 10",
            "185 com/google/common/collect/Multimap.get(Ljava/lang/Object;)Ljava/util/Collection; true",
            "25 9", "185 java/util/Collection.contains(Ljava/lang/Object;)Z true", "153 21",
            "25 3", "25 10", "185 com/google/common/collect/Multimap.get(Ljava/lang/Object;)Ljava/util/Collection; true",
            "25 9", "185 java/util/Collection.remove(Ljava/lang/Object;)Z true", "87", "167 35",
            "25 0", "182 net/minecraft/entity/EntityLivingBase.func_110140_aT()Lnet/minecraft/entity/ai/attributes/AbstractAttributeMap; false",
            "25 10", "182 net/minecraft/entity/ai/attributes/AbstractAttributeMap.func_111152_a(Ljava/lang/String;)Lnet/minecraft/entity/ai/attributes/IAttributeInstance; false",
            "58 11", "25 11", "198 35", "25 11", "25 9",
            "185 net/minecraft/entity/ai/attributes/IAttributeInstance.func_180374_a(Lnet/minecraft/entity/ai/attributes/AttributeModifier;)Z true",
            "154 35", "25 11", "25 9",
            "185 net/minecraft/entity/ai/attributes/IAttributeInstance.func_111121_a(Lnet/minecraft/entity/ai/attributes/AttributeModifier;)V true",
            "132 8 1");
        if (!expected.equals(actual)) throw new IllegalStateException("QualityTools modifier body differs from audited 1.0.7 bytecode");
    }

    private static AbstractInsnNode previousOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode previous = instruction == null ? null : instruction.getPrevious();
        while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
        return previous;
    }
}
