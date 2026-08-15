package dev.rlcraft.ice.hooks;

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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Replaces Quark's two boxed WeakHashMap probes with one mutable weak state. */
final class QuarkItemSyncAdapter implements OptimizerBytecodeAdapter {
    static final String METHOD = "updateItemInfo";
    static final String ITEM = "net/minecraft/entity/item/EntityItem";
    static final String DESCRIPTOR = "(L" + ITEM + ";)V";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/entity/QuarkItemSyncBridge";
    private static final String WORLD = "Lnet/minecraft/world/World;";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!transformedName.replace('.', '/').equals(node.name)) {
            throw new IllegalStateException("Quark ItemsFlashBeforeExpiring 类名变化：" + node.name);
        }
        MethodNode method = find(node, METHOD, DESCRIPTOR);
        if ((method.access & Opcodes.ACC_STATIC) == 0) {
            throw new IllegalStateException("Quark updateItemInfo 不再是静态方法");
        }
        FieldInsnNode lifespan = null;
        VarInsnNode sendStart = null;
        int lifespanReads = 0;
        int ageMaps = 0;
        int lifespanMaps = 0;
        int sends = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) instruction;
                if (field.getOpcode() == Opcodes.GETFIELD && ITEM.equals(field.owner)
                    && "lifespan".equals(field.name) && "I".equals(field.desc)) {
                    lifespan = field;
                    lifespanReads++;
                }
                if (field.getOpcode() == Opcodes.GETSTATIC && node.name.equals(field.owner)
                    && "AGE_MAP".equals(field.name)) ageMaps++;
                if (field.getOpcode() == Opcodes.GETSTATIC && node.name.equals(field.owner)
                    && "LIFESPAN_MAP".equals(field.name)) lifespanMaps++;
                if (field.getOpcode() == Opcodes.GETFIELD && ITEM.equals(field.owner)
                    && WORLD.equals(field.desc)) {
                    AbstractInsnNode receiver = previousOpcode(field);
                    AbstractInsnNode branch = previousOpcode(receiver);
                    if (receiver instanceof VarInsnNode && receiver.getOpcode() == Opcodes.ALOAD
                        && ((VarInsnNode) receiver).var == 0
                        && branch instanceof JumpInsnNode && branch.getOpcode() == Opcodes.IFEQ) {
                        sendStart = (VarInsnNode) receiver;
                    }
                }
            }
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if ("net/minecraftforge/fml/common/network/simpleimpl/SimpleNetworkWrapper".equals(call.owner)
                    && "sendTo".equals(call.name)) sends++;
            }
        }
        AbstractInsnNode lifespanStore = nextOpcode(lifespan);
        AbstractInsnNode zero = nextOpcode(lifespanStore);
        AbstractInsnNode changedStore = nextOpcode(zero);
        if (lifespanReads != 1 || lifespan == null || ageMaps != 4 || lifespanMaps != 3
            || sends != 1 || sendStart == null
            || !(lifespanStore instanceof VarInsnNode) || lifespanStore.getOpcode() != Opcodes.ISTORE
            || ((VarInsnNode) lifespanStore).var != 2
            || zero == null || zero.getOpcode() != Opcodes.ICONST_0
            || !(changedStore instanceof VarInsnNode) || changedStore.getOpcode() != Opcodes.ISTORE
            || ((VarInsnNode) changedStore).var != 3) {
            throw new IllegalStateException("Quark 掉落物同步调用图变化：lifespan=" + lifespanReads
                + ", ageMap=" + ageMaps + ", lifespanMap=" + lifespanMaps + ", sends=" + sends);
        }

        LabelNode original = new LabelNode();
        LabelNode noSync = new LabelNode();
        LabelNode send = new LabelNode();
        method.instructions.insertBefore(sendStart, send);
        int decisionLocal = method.maxLocals++;
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        guard.add(new VarInsnNode(Opcodes.ILOAD, 1));
        guard.add(new VarInsnNode(Opcodes.ILOAD, 2));
        guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "decision",
            "(L" + ITEM + ";II)I", false));
        guard.add(new VarInsnNode(Opcodes.ISTORE, decisionLocal));
        guard.add(new VarInsnNode(Opcodes.ILOAD, decisionLocal));
        guard.add(new InsnNode(Opcodes.ICONST_M1));
        guard.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, original));
        guard.add(new VarInsnNode(Opcodes.ILOAD, decisionLocal));
        guard.add(new JumpInsnNode(Opcodes.IFEQ, noSync));
        guard.add(new JumpInsnNode(Opcodes.GOTO, send));
        guard.add(noSync);
        guard.add(new InsnNode(Opcodes.RETURN));
        guard.add(original);
        method.instructions.insertBefore(zero, guard);

        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
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

    private static MethodNode find(ClassNode node, String name, String descriptor) {
        MethodNode match = null;
        int count = 0;
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                match = method;
                count++;
            }
        }
        if (count != 1 || match == null) {
            throw new IllegalStateException("Quark " + name + descriptor
                + " 匹配数量应为 1，实际 " + count);
        }
        return match;
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String type1, String type2) {
            return "java/lang/Object";
        }
    }
}
