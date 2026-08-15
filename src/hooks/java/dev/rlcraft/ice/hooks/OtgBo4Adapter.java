package dev.rlcraft.ice.hooks;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Removes BO4 source rewrites and reuses the first getBlocks result in one spawn. */
final class OtgBo4Adapter implements OptimizerBytecodeAdapter {
    static final String BO4_CONFIG = "com/pg85/otg/customobjects/bo4/BO4Config";
    static final String CONFIG_FIELD = "config";
    static final String BLOCKS_DESCRIPTOR =
        "()[Lcom/pg85/otg/customobjects/bo4/bo4function/BO4BlockFunction;";
    static final String WRITER_OWNER = "com/pg85/otg/configuration/io/FileSettingsWriterOTGPlus";
    static final String WRITER_NAME = "writeToFile";
    static final String WRITER_DESCRIPTOR =
        "(Lcom/pg85/otg/configuration/customobjects/CustomObjectConfigFile;"
            + "Lcom/pg85/otg/configuration/world/WorldConfig$ConfigMode;)V";
    static final String OPTIMIZER_BRIDGE = "dev/rlcraft/ice/optimizer/bridge/OptimizerBridge";
    static final String ENABLED_DESCRIPTOR = "(Ljava/lang/String;)Z";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!transformedName.replace('.', '/').equals(node.name)) {
            throw new IllegalStateException("OTG BO4 类名变化：" + node.name);
        }

        MethodNode onEnable = findMethod(node, "onEnable", "()Z");
        MethodInsnNode writerCall = null;
        int writerCalls = 0;
        for (AbstractInsnNode instruction : onEnable.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.getOpcode() == Opcodes.INVOKESTATIC && WRITER_OWNER.equals(call.owner)
                    && WRITER_NAME.equals(call.name) && WRITER_DESCRIPTOR.equals(call.desc)) {
                    writerCall = call;
                    writerCalls++;
                }
            }
        }
        if (writerCalls != 1 || writerCall == null) {
            throw new IllegalStateException("OTG BO4 writeToFile 调用数量应为 1，实际 " + writerCalls);
        }
        installWriteGuard(onEnable, writerCall);

        MethodNode spawn = findUniqueMethod(node, "trySpawnAt");
        List<MethodInsnNode> getBlocksCalls = new ArrayList<MethodInsnNode>();
        for (AbstractInsnNode instruction : spawn.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && BO4_CONFIG.equals(call.owner)
                    && "getBlocks".equals(call.name) && BLOCKS_DESCRIPTOR.equals(call.desc)) {
                    getBlocksCalls.add(call);
                }
            }
        }
        if (getBlocksCalls.size() != 2) {
            throw new IllegalStateException("OTG BO4 trySpawnAt getBlocks 调用数量应为 2，实际 "
                + getBlocksCalls.size());
        }
        AbstractInsnNode firstStoreInstruction = nextOpcode(getBlocksCalls.get(0));
        if (!(firstStoreInstruction instanceof VarInsnNode)
            || firstStoreInstruction.getOpcode() != Opcodes.ASTORE) {
            throw new IllegalStateException("OTG BO4 第一个 getBlocks 结果不再写入局部变量");
        }
        int blocksLocal = ((VarInsnNode) firstStoreInstruction).var;
        installBlocksReuse(spawn, getBlocksCalls.get(1), blocksLocal, node.name);

        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void installWriteGuard(MethodNode method, MethodInsnNode call) {
        LabelNode originalWrite = new LabelNode();
        LabelNode complete = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new LdcInsnNode("otg-bo4-io"));
        guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OPTIMIZER_BRIDGE,
            "isEnabled", ENABLED_DESCRIPTOR, false));
        guard.add(new JumpInsnNode(Opcodes.IFEQ, originalWrite));
        guard.add(new InsnNode(Opcodes.POP2));
        guard.add(new JumpInsnNode(Opcodes.GOTO, complete));
        guard.add(originalWrite);
        method.instructions.insertBefore(call, guard);
        method.instructions.insert(call, complete);
    }

    private static void installBlocksReuse(MethodNode method, MethodInsnNode secondCall,
                                           int blocksLocal, String bo4Owner) {
        AbstractInsnNode configFieldInstruction = previousOpcode(secondCall);
        AbstractInsnNode receiverInstruction = previousOpcode(configFieldInstruction);
        if (!(configFieldInstruction instanceof FieldInsnNode)
            || configFieldInstruction.getOpcode() != Opcodes.GETFIELD
            || !bo4Owner.equals(((FieldInsnNode) configFieldInstruction).owner)
            || !CONFIG_FIELD.equals(((FieldInsnNode) configFieldInstruction).name)
            || !(receiverInstruction instanceof VarInsnNode)
            || receiverInstruction.getOpcode() != Opcodes.ALOAD
            || ((VarInsnNode) receiverInstruction).var != 0) {
            throw new IllegalStateException("OTG BO4 第二个 getBlocks 接收者调用图变化");
        }

        LabelNode originalCall = new LabelNode();
        LabelNode complete = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new LdcInsnNode("otg-bo4-layout"));
        guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OPTIMIZER_BRIDGE,
            "isEnabled", ENABLED_DESCRIPTOR, false));
        guard.add(new JumpInsnNode(Opcodes.IFEQ, originalCall));
        guard.add(new VarInsnNode(Opcodes.ALOAD, blocksLocal));
        guard.add(new JumpInsnNode(Opcodes.GOTO, complete));
        guard.add(originalCall);
        method.instructions.insertBefore(receiverInstruction, guard);
        method.instructions.insert(secondCall, complete);
    }

    private static MethodNode findMethod(ClassNode node, String name, String descriptor) {
        MethodNode match = null;
        int matches = 0;
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                match = method;
                matches++;
            }
        }
        if (matches != 1 || match == null) {
            throw new IllegalStateException("OTG BO4 " + name + descriptor + " 匹配数量应为 1，实际 " + matches);
        }
        return match;
    }

    private static MethodNode findUniqueMethod(ClassNode node, String name) {
        MethodNode match = null;
        int matches = 0;
        for (MethodNode method : node.methods) {
            if (name.equals(method.name)) {
                match = method;
                matches++;
            }
        }
        if (matches != 1 || match == null) {
            throw new IllegalStateException("OTG BO4 " + name + " 匹配数量应为 1，实际 " + matches);
        }
        return match;
    }

    private static AbstractInsnNode previousOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private static AbstractInsnNode nextOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getNext();
        while (current != null && current.getOpcode() < 0) current = current.getNext();
        return current;
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String type1, String type2) {
            return "java/lang/Object";
        }
    }
}
