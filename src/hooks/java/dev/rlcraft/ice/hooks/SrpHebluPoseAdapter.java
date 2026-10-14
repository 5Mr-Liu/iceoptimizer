package dev.rlcraft.ice.hooks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

/** Reuses Heblu's repeated cosine expressions only within one pose evaluation. */
final class SrpHebluPoseAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET =
        "com/dhanantry/scapeandrunparasites/client/model/entity/derived/ModelHeblu";
    static final String PART = "net/minecraft/client/model/ModelRenderer";
    static final String METHOD = "func_78087_a";
    static final String DESCRIPTOR = "(FFFFFFLnet/minecraft/entity/Entity;)V";
    static final String ORIGINAL = "ice$hebLuPoseOriginal";
    static final String SWING = "(L" + PART + ";FFIFFFF)V";
    static final String REUSED_SWING = "(L" + PART + ";FFIFFFFD)V";
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/srp/SrpPoseBridge";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(node.name)) throw new IllegalStateException("SRP Heblu class changed");
        MethodNode original = null;
        for (MethodNode method : node.methods) {
            if (method.name.startsWith("ice$hebLu")) {
                throw new IllegalStateException("SRP Heblu pose already adapted");
            }
            if (METHOD.equals(method.name) && DESCRIPTOR.equals(method.desc)) {
                if (original != null) throw new IllegalStateException("Duplicate SRP Heblu pose");
                original = method;
            }
        }
        if (original == null) throw new IllegalStateException("Missing SRP Heblu pose");
        MethodNode optimized = new MethodNode(Opcodes.ASM5, original.access, METHOD,
            DESCRIPTOR, original.signature, original.exceptions.toArray(new String[0]));
        original.accept(optimized);
        List<MethodInsnNode> swings = new ArrayList<MethodInsnNode>();
        for (AbstractInsnNode instruction : optimized.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (TARGET.equals(call.owner) && SWING.equals(call.desc)
                && ("swingX".equals(call.name) || "swingZ".equals(call.name))) {
                swings.add(call);
            }
        }
        if (swings.size() != 36) throw new IllegalStateException("SRP Heblu swing graph changed");
        reuseGroup(optimized, swings, 0, 4, "swingZ", 9, false);
        reuseGroup(optimized, swings, 4, 20, "swingX", 10, true);
        reuseGroup(optimized, swings, 20, 36, "swingX", 10, true);

        InsnList selection = new InsnList();
        LabelNode fast = new LabelNode();
        selection.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "usePoseReuse", "()Z", false));
        selection.add(new JumpInsnNode(Opcodes.IFNE, fast));
        selection.add(new VarInsnNode(Opcodes.ALOAD, 0));
        for (int local = 1; local <= 6; local++) selection.add(new VarInsnNode(Opcodes.FLOAD, local));
        selection.add(new VarInsnNode(Opcodes.ALOAD, 7));
        selection.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, TARGET, ORIGINAL, DESCRIPTOR, false));
        selection.add(new InsnNode(Opcodes.RETURN));
        selection.add(fast);
        optimized.instructions.insert(selection);
        original.name = ORIGINAL;
        original.access = (original.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
            | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
        node.methods.add(optimized);
        node.methods.add(swingHelper("X", "field_78795_f"));
        node.methods.add(swingHelper("Z", "field_78808_h"));
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String left, String right) {
                return "java/lang/Object";
            }
        };
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void reuseGroup(MethodNode method, List<MethodInsnNode> swings,
                                   int from, int to, String expectedName, int speedLocal,
                                   boolean scaleSpeed) {
        Map<Integer, Integer> cosineLocals = new LinkedHashMap<Integer, Integer>();
        InsnList calculation = new InsnList();
        AbstractInsnNode start = callStart(swings.get(from));
        for (int index = from; index < to; index++) {
            MethodInsnNode call = swings.get(index);
            if (!expectedName.equals(call.name)) throw new IllegalStateException("SRP Heblu swing order changed");
            AbstractInsnNode amount = previous(call);
            AbstractInsnNode limb = previous(amount);
            AbstractInsnNode weight = previous(limb);
            AbstractInsnNode phase = previous(weight);
            requireFloatLocal(amount, 2);
            requireFloatLocal(limb, 1);
            float phaseValue = constant(phase);
            int bits = Float.floatToRawIntBits(phaseValue);
            Integer local = cosineLocals.get(bits);
            if (local == null) {
                local = method.maxLocals;
                method.maxLocals += 2;
                cosineLocals.put(bits, local);
                // ModelSRP computes the product and phase addition in float, then widens.
                calculation.add(new VarInsnNode(Opcodes.FLOAD, 1));
                if (scaleSpeed) calculation.add(new LdcInsnNode(0.3F));
                calculation.add(new VarInsnNode(Opcodes.FLOAD, speedLocal));
                if (scaleSpeed) calculation.add(new InsnNode(Opcodes.FMUL));
                calculation.add(new InsnNode(Opcodes.FMUL));
                calculation.add(new LdcInsnNode(phaseValue));
                calculation.add(new InsnNode(Opcodes.FADD));
                calculation.add(new InsnNode(Opcodes.F2D));
                calculation.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "cos", "(D)D", false));
                calculation.add(new VarInsnNode(Opcodes.DSTORE, local));
            }
            method.instructions.insertBefore(call, new VarInsnNode(Opcodes.DLOAD, local));
            call.setOpcode(Opcodes.INVOKESPECIAL);
            call.name = "ice$hebLuSwing" + expectedName.substring(5);
            call.desc = REUSED_SWING;
        }
        if (cosineLocals.size() != 2) throw new IllegalStateException("SRP Heblu phase expressions changed");
        method.instructions.insertBefore(start, calculation);
    }

    private static AbstractInsnNode callStart(MethodInsnNode call) {
        AbstractInsnNode instruction = previous(call);
        while (!(instruction instanceof FieldInsnNode)) instruction = previous(instruction);
        FieldInsnNode part = (FieldInsnNode) instruction;
        AbstractInsnNode loadPartOwner = previous(part);
        AbstractInsnNode loadReceiver = previous(loadPartOwner);
        if (!TARGET.equals(part.owner) || !("L" + PART + ";").equals(part.desc)
            || !isLocal(loadPartOwner, Opcodes.ALOAD, 0)
            || !isLocal(loadReceiver, Opcodes.ALOAD, 0)) {
            throw new IllegalStateException("SRP Heblu swing receiver changed");
        }
        return loadReceiver;
    }

    private static AbstractInsnNode previous(AbstractInsnNode instruction) {
        do {
            instruction = instruction.getPrevious();
            if (instruction == null) throw new IllegalStateException("Truncated SRP Heblu expression");
        } while (instruction.getOpcode() < 0);
        return instruction;
    }

    private static void requireFloatLocal(AbstractInsnNode instruction, int local) {
        if (!isLocal(instruction, Opcodes.FLOAD, local)) {
            throw new IllegalStateException("SRP Heblu pose inputs changed");
        }
    }

    private static boolean isLocal(AbstractInsnNode instruction, int opcode, int local) {
        return instruction instanceof VarInsnNode && instruction.getOpcode() == opcode
            && ((VarInsnNode) instruction).var == local;
    }

    private static float constant(AbstractInsnNode instruction) {
        if (instruction instanceof LdcInsnNode && ((LdcInsnNode) instruction).cst instanceof Float) {
            return (Float) ((LdcInsnNode) instruction).cst;
        }
        if (instruction.getOpcode() >= Opcodes.FCONST_0 && instruction.getOpcode() <= Opcodes.FCONST_2) {
            return instruction.getOpcode() - Opcodes.FCONST_0;
        }
        throw new IllegalStateException("SRP Heblu cosine phase changed");
    }

    private static MethodNode swingHelper(String axis, String field) {
        MethodNode method = new MethodNode(Opcodes.ASM5, Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
            "ice$hebLuSwing" + axis, REUSED_SWING, null, null);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitVarInsn(Opcodes.ILOAD, 4);
        method.visitInsn(Opcodes.I2F);
        method.visitVarInsn(Opcodes.FLOAD, 8);
        method.visitInsn(Opcodes.FMUL);
        method.visitVarInsn(Opcodes.FLOAD, 3);
        method.visitInsn(Opcodes.FMUL);
        method.visitInsn(Opcodes.F2D);
        method.visitVarInsn(Opcodes.DLOAD, 9);
        method.visitInsn(Opcodes.DMUL);
        method.visitVarInsn(Opcodes.FLOAD, 6);
        method.visitVarInsn(Opcodes.FLOAD, 8);
        method.visitInsn(Opcodes.FMUL);
        method.visitInsn(Opcodes.F2D);
        method.visitInsn(Opcodes.DADD);
        method.visitInsn(Opcodes.D2F);
        method.visitFieldInsn(Opcodes.PUTFIELD, PART, field, "F");
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        return method;
    }
}
