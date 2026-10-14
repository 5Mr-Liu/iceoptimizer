package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Keeps the original search for the user's runtime switch, exposing only SRP's name rule. */
final class SrpLightSearchAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "com/dhanantry/scapeandrunparasites/entity/ai/EntityAIBlockLight";
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/srp/SrpLightSearchBridge";
    static final String CALLBACKS = "dev/rlcraft/ice/optimizer/compat/srp/SrpLightSearchCallbacks";
    static final String ORIGINAL = "ice$originalFindSource";
    static final String FIND_DESC = "()Lnet/minecraft/util/math/BlockPos;";
    static final String BRIDGE_DESC = "(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;II"
        + "Ljava/util/List;L" + CALLBACKS + ";)Lnet/minecraft/util/math/BlockPos;";
    private static final String PARASITE =
        "com/dhanantry/scapeandrunparasites/entity/ai/misc/EntityParasiteBase";
    private static final String CONFIG = "com/dhanantry/scapeandrunparasites/util/config/SRPConfig";

    @Override
    public byte[] transform(String name, byte[] bytes, TargetSpec target) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(node.name)) throw new IllegalStateException("SRP light task changed");
        MethodNode original = null;
        for (MethodNode method : node.methods) {
            if ("findSource".equals(method.name) && FIND_DESC.equals(method.desc)) original = method;
        }
        if (original == null || node.interfaces.contains(CALLBACKS)) {
            throw new IllegalStateException("SRP light search missing or already adapted");
        }
        original.name = ORIGINAL;
        node.interfaces.add(CALLBACKS);
        node.methods.add(wrapper());
        node.methods.add(blockRule());
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static MethodNode wrapper() {
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE, "findSource", FIND_DESC, null, null);
        LabelNode original = new LabelNode();
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            BRIDGE, "isEnabled", "()Z", false));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, original));
        readField(method, "world", "Lnet/minecraft/world/World;");
        readField(method, "parent", "L" + PARASITE + ";");
        readField(method, "range", "I");
        readField(method, "lightTrigger", "I");
        readField(method, "cant", "Ljava/util/ArrayList;");
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            BRIDGE, "findSource", BRIDGE_DESC, false));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.instructions.add(original);
        method.instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
            TARGET, ORIGINAL, FIND_DESC, false));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        return method;
    }

    private static void readField(MethodNode method, String name, String descriptor) {
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET, name, descriptor));
    }

    private static MethodNode blockRule() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
            "ice$lightBlocked", "(Ljava/lang/Object;)Z", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/block/Block"));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
            "net/minecraft/block/Block", "getRegistryName", "()Lnet/minecraft/util/ResourceLocation;", false));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
            "net/minecraft/util/ResourceLocation", "toString", "()Ljava/lang/String;", false));
        method.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, CONFIG,
            "parasiteGriefingBlackList", "[Ljava/lang/String;"));
        method.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, CONFIG,
            "parasiteGriefingWhite", "Z"));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            "com/dhanantry/scapeandrunparasites/util/ParasiteEventEntity", "checkName",
            "(Ljava/lang/String;[Ljava/lang/String;Z)Z", false));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        return method;
    }
}
