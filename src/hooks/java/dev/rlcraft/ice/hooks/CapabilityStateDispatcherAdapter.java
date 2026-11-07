package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Adds an allocation-free comparison before Forge's unchanged serialization fallback. */
final class CapabilityStateDispatcherAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "net/minecraftforge/common/capabilities/CapabilityDispatcher";
    static final String ACCESS = "dev/rlcraft/ice/optimizer/compat/nbt/CapabilityStateDispatcherAccess";
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/nbt/CapabilityStateBridge";

    @Override public byte[] transform(String name, byte[] original, TargetSpec target) {
        ClassReader reader = new ClassReader(original);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(node.name) || node.interfaces.contains(ACCESS)) throw new IllegalStateException("Capability state dispatcher changed");
        MethodNode compatible = null;
        for (MethodNode method : node.methods) {
            if ("areCompatible".equals(method.name) && ("(L" + TARGET + ";)Z").equals(method.desc)) compatible = method;
        }
        if (compatible == null) throw new IllegalStateException("Capability compatibility method missing");
        AbstractInsnNode firstReceiver = null;
        int calls = 0;
        for (AbstractInsnNode instruction : compatible.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (TARGET.equals(call.owner) && "serializeNBT".equals(call.name)
                && "()Lnet/minecraft/nbt/NBTTagCompound;".equals(call.desc)) {
                calls++;
                if (firstReceiver == null) {
                    firstReceiver = call.getPrevious();
                    while (firstReceiver != null && firstReceiver.getOpcode() < 0) firstReceiver = firstReceiver.getPrevious();
                }
            }
        }
        if (calls != 2 || !(firstReceiver instanceof VarInsnNode)
            || firstReceiver.getOpcode() != Opcodes.ALOAD || ((VarInsnNode) firstReceiver).var != 0) {
            throw new IllegalStateException("Capability serialization fallback changed");
        }
        InsnList entry = new InsnList();
        LabelNode fallback = new LabelNode();
        entry.add(new VarInsnNode(Opcodes.ALOAD, 0));
        entry.add(new VarInsnNode(Opcodes.ALOAD, 1));
        entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "compare", "(Ljava/lang/Object;Ljava/lang/Object;)I", false));
        entry.add(new InsnNode(Opcodes.DUP));
        entry.add(new InsnNode(Opcodes.ICONST_M1));
        entry.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, fallback));
        entry.add(new InsnNode(Opcodes.IRETURN));
        entry.add(fallback);
        entry.add(new InsnNode(Opcodes.POP));
        compatible.instructions.insertBefore(firstReceiver, entry);

        node.interfaces.add(ACCESS);
        getter(node, "ice$stateWriters", "writers", "[Lnet/minecraftforge/common/util/INBTSerializable;");
        getter(node, "ice$stateNames", "names", "[Ljava/lang/String;");
        MethodNode plain = new MethodNode(Opcodes.ASM5, Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
            "ice$plainStateDispatcher", "()Z", null, null);
        plain.visitCode();
        plain.visitVarInsn(Opcodes.ALOAD, 0);
        plain.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
        plain.visitLdcInsn(Type.getObjectType(TARGET));
        Label no = new Label();
        plain.visitJumpInsn(Opcodes.IF_ACMPNE, no);
        plain.visitInsn(Opcodes.ICONST_1);
        plain.visitInsn(Opcodes.IRETURN);
        plain.visitLabel(no);
        plain.visitInsn(Opcodes.ICONST_0);
        plain.visitInsn(Opcodes.IRETURN);
        plain.visitMaxs(0, 0);
        plain.visitEnd();
        node.methods.add(plain);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void getter(ClassNode node, String name, String field, String descriptor) {
        boolean found = false;
        for (FieldNode candidate : node.fields) if (field.equals(candidate.name) && descriptor.equals(candidate.desc)) found = true;
        if (!found) throw new IllegalStateException("Capability dispatcher field changed: " + field);
        MethodNode code = new MethodNode(Opcodes.ASM5, Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
            name, "()" + descriptor, null, null);
        code.visitCode();
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitFieldInsn(Opcodes.GETFIELD, node.name, field, descriptor);
        code.visitInsn(Opcodes.ARETURN);
        code.visitMaxs(0, 0);
        code.visitEnd();
        node.methods.add(code);
    }
}
