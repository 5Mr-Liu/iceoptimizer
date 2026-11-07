package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Exposes the two concrete item providers whose serialized formats were reviewed. */
final class CapabilityStateProviderAdapter implements OptimizerBytecodeAdapter {
    static final String ENERGY_TARGET = "cursedflames/bountifulbaubles/item/ItemRingFlywheel$Provider";
    static final String QUIVER_TARGET = "com/oblivioussp/spartanweaponry/capability/CapabilityProviderQuiver";
    static final String ACCESS = "dev/rlcraft/ice/optimizer/compat/nbt/CapabilityStateProviderAccess";
    static final String SCALAR_ACCESS = "dev/rlcraft/ice/optimizer/compat/nbt/CapabilityStateScalarAccess";
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/nbt/CapabilityStateBridge";
    private static final String SCALAR_FIELD = "rlcraftIce$stateScalar";
    private static final String INT_TAG = "Lnet/minecraft/nbt/NBTTagInt;";

    @Override public byte[] transform(String name, byte[] original, TargetSpec target) {
        ClassReader reader = new ClassReader(original);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        boolean energy = ENERGY_TARGET.equals(node.name);
        if (!energy && !QUIVER_TARGET.equals(node.name)) throw new IllegalStateException("Unknown state provider " + node.name);
        if (node.interfaces.contains(ACCESS)) throw new IllegalStateException("Capability state access already installed");
        node.interfaces.add(ACCESS);
        addKind(node, energy ? 1 : 2);
        addObjectGetter(node, "ice$statePayload", energy ? "cap" : "handler",
            energy ? "Lnet/minecraftforge/energy/IEnergyStorage;" : "Lnet/minecraftforge/items/ItemStackHandler;");
        if (energy) {
            MethodNode owner = method("ice$stateOwner", "()Ljava/lang/Object;");
            owner.visitInsn(Opcodes.ACONST_NULL);
            owner.visitInsn(Opcodes.ARETURN);
            finish(node, owner);
            addScalarCache(node);
            int writes = 0;
            for (MethodNode method : node.methods) {
                if (!"serializeNBT".equals(method.name) || !"()Lnet/minecraft/nbt/NBTTagCompound;".equals(method.desc)) continue;
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (!(instruction instanceof MethodInsnNode)) continue;
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (!"net/minecraft/nbt/NBTTagCompound".equals(call.owner)
                        || !("func_74768_a".equals(call.name) || "setInteger".equals(call.name))
                        || !"(Ljava/lang/String;I)V".equals(call.desc)) continue;
                    method.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, 0));
                    method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
                        "writeEnergy", "(Lnet/minecraft/nbt/NBTTagCompound;Ljava/lang/String;IL" + SCALAR_ACCESS + ";)V", false));
                    writes++;
                }
            }
            if (writes != 1) throw new IllegalStateException("Flywheel amount writer changed: " + writes);
        } else {
            addObjectGetter(node, "ice$stateOwner", "quiver", "Lnet/minecraft/item/ItemStack;");
        }
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void addKind(ClassNode node, int kind) {
        MethodNode code = method("ice$stateKind", "()I");
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
        code.visitLdcInsn(Type.getObjectType(node.name));
        Label unknown = new Label();
        code.visitJumpInsn(Opcodes.IF_ACMPNE, unknown);
        code.visitInsn(Opcodes.ICONST_0 + kind);
        code.visitInsn(Opcodes.IRETURN);
        code.visitLabel(unknown);
        code.visitInsn(Opcodes.ICONST_0);
        code.visitInsn(Opcodes.IRETURN);
        finish(node, code);
    }

    private static void addObjectGetter(ClassNode node, String name, String field, String descriptor) {
        boolean found = false;
        for (FieldNode candidate : node.fields) if (field.equals(candidate.name) && descriptor.equals(candidate.desc)) found = true;
        if (!found) throw new IllegalStateException("Capability state field changed: " + field);
        MethodNode code = method(name, "()Ljava/lang/Object;");
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitFieldInsn(Opcodes.GETFIELD, node.name, field, descriptor);
        code.visitInsn(Opcodes.ARETURN);
        finish(node, code);
    }

    private static void addScalarCache(ClassNode node) {
        node.interfaces.add(SCALAR_ACCESS);
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC,
            SCALAR_FIELD, INT_TAG, null, null));
        MethodNode get = method("ice$stateScalar", "()" + INT_TAG);
        get.visitVarInsn(Opcodes.ALOAD, 0);
        get.visitFieldInsn(Opcodes.GETFIELD, node.name, SCALAR_FIELD, INT_TAG);
        get.visitInsn(Opcodes.ARETURN);
        finish(node, get);
        MethodNode set = method("ice$stateScalar", "(" + INT_TAG + ")V");
        set.visitVarInsn(Opcodes.ALOAD, 0);
        set.visitVarInsn(Opcodes.ALOAD, 1);
        set.visitFieldInsn(Opcodes.PUTFIELD, node.name, SCALAR_FIELD, INT_TAG);
        set.visitInsn(Opcodes.RETURN);
        finish(node, set);
    }

    private static MethodNode method(String name, String descriptor) {
        MethodNode result = new MethodNode(Opcodes.ASM5, Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
            name, descriptor, null, null);
        result.visitCode();
        return result;
    }

    private static void finish(ClassNode node, MethodVisitor method) {
        method.visitMaxs(0, 0);
        method.visitEnd();
        node.methods.add((MethodNode) method);
    }
}
