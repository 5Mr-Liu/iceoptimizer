package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Skips Quality Tools' seven-tick remove/rebuild pass when mob equipment is unchanged. */
final class QualityToolsAttributeAdapter implements OptimizerBytecodeAdapter {
    static final String METHOD = "onLivingUpdate";
    static final String EVENT =
        "net/minecraftforge/event/entity/living/LivingEvent$LivingUpdateEvent";
    static final String DESCRIPTOR = "(L" + EVENT + ";)V";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/entity/QualityToolsAttributeBridge";
    private static final String ENTITY = "net/minecraft/entity/EntityLivingBase";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!transformedName.replace('.', '/').equals(node.name)) {
            throw new IllegalStateException("Quality Tools CommonEventHandler 类名变化：" + node.name);
        }
        MethodNode method = find(node, METHOD, DESCRIPTOR);
        if ((method.access & Opcodes.ACC_STATIC) == 0) {
            throw new IllegalStateException("Quality Tools onLivingUpdate 不再是静态事件方法");
        }
        MethodInsnNode create = null;
        int creates = 0;
        int equipmentApplications = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.getOpcode() == Opcodes.INVOKESTATIC
                && "com/google/common/collect/HashMultimap".equals(call.owner)
                && "create".equals(call.name)
                && "()Lcom/google/common/collect/HashMultimap;".equals(call.desc)) {
                create = call;
                creates++;
            }
            if ("com/tmtravlr/qualitytools/QualityToolsHelper".equals(call.owner)
                && "applyAttributesForSlot".equals(call.name)) equipmentApplications++;
        }
        AbstractInsnNode moduloBranch = previousOpcode(create);
        AbstractInsnNode remainder = previousOpcode(moduloBranch);
        AbstractInsnNode seven = previousOpcode(remainder);
        if (creates != 1 || create == null || equipmentApplications < 6
            || !(moduloBranch instanceof JumpInsnNode) || moduloBranch.getOpcode() != Opcodes.IFNE
            || remainder == null || remainder.getOpcode() != Opcodes.IREM
            || !(seven instanceof IntInsnNode) || seven.getOpcode() != Opcodes.BIPUSH
            || ((IntInsnNode) seven).operand != 7) {
            throw new IllegalStateException("Quality Tools 七 Tick 属性调用图变化：create="
                + creates + ", apply=" + equipmentApplications);
        }

        LabelNode refresh = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        guard.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, EVENT, "getEntityLiving",
            "()L" + ENTITY + ";", false));
        guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "shouldRefresh",
            "(L" + ENTITY + ";)Z", false));
        guard.add(new JumpInsnNode(Opcodes.IFNE, refresh));
        guard.add(new InsnNode(Opcodes.RETURN));
        guard.add(refresh);
        method.instructions.insertBefore(create, guard);

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
            throw new IllegalStateException("Quality Tools " + name + descriptor
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
