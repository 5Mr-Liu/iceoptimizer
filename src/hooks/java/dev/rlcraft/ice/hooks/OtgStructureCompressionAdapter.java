package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Replace only compression computation; OTG's original logging tail stays in place. */
final class OtgStructureCompressionAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "com/pg85/otg/util/CompressionUtils";
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/otg/OtgStructureCompressionBridge";
    @Override public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(node.name) || !transformedName.replace('.', '/').equals(node.name)) {
            throw new IllegalStateException("OTG CompressionUtils class changed");
        }
        MethodNode method = null;
        for (MethodNode candidate : node.methods) {
            if ("compress".equals(candidate.name) && "([B)[B".equals(candidate.desc)) method = candidate;
        }
        if (method == null) throw new IllegalStateException("OTG compress method missing");
        AbstractInsnNode logging = null;
        int computations = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (BRIDGE.equals(call.owner)) throw new IllegalStateException("OTG compression already installed");
            if ("java/io/ByteArrayOutputStream".equals(call.owner) && "toByteArray".equals(call.name)) {
                AbstractInsnNode store = nextCode(instruction);
                if (!(store instanceof VarInsnNode) || store.getOpcode() != Opcodes.ASTORE
                    || ((VarInsnNode) store).var != 4) throw new IllegalStateException("OTG compression result changed");
                logging = nextCode(store);
                computations++;
            }
        }
        if (computations != 1 || !(logging instanceof MethodInsnNode)
            || !"com/pg85/otg/OTG".equals(((MethodInsnNode) logging).owner)
            || !"getPluginConfig".equals(((MethodInsnNode) logging).name)) {
            throw new IllegalStateException("OTG compression logging boundary changed");
        }
        LabelNode loggingLabel = new LabelNode();
        method.instructions.insertBefore(logging, loggingLabel);
        LabelNode original = new LabelNode();
        InsnList prefix = new InsnList();
        prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "isEnabled", "()Z", false));
        prefix.add(new JumpInsnNode(Opcodes.IFEQ, original));
        prefix.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "compress", "([B)[B", false));
        prefix.add(new VarInsnNode(Opcodes.ASTORE, 4));
        prefix.add(new JumpInsnNode(Opcodes.GOTO, loggingLabel));
        prefix.add(original);
        method.instructions.insert(prefix);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        return OtgAsmMethodWriter.write(reader, writer, method);
    }
    private static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
        do { instruction = instruction.getNext(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }
}
