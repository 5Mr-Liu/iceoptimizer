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
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Replaces CaveCarver's per-column boxed threshold HashMap with a contiguous map view. */
final class BetterCavesCaveCarverAdapter implements OptimizerBytecodeAdapter {
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/bettercaves/BetterCavesOptimizationBridge";
    static final String THRESHOLDS =
        "dev/rlcraft/ice/optimizer/compat/bettercaves/BetterCavesThresholdMap";
    private static final String METHOD = "generateThresholds";
    private static final String DESCRIPTOR = "(III)Ljava/util/Map;";
    private static final String SETTINGS =
        "Lcom/yungnickyoung/minecraft/bettercaves/world/carver/CarverSettings;";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!transformedName.replace('.', '/').equals(node.name)) {
            throw new IllegalStateException("Better Caves CaveCarver 类名变化：" + node.name);
        }
        MethodNode method = find(node, METHOD, DESCRIPTOR);
        FieldInsnNode settings = null;
        MethodInsnNode thresholdGetter = null;
        int maps = 0;
        int puts = 0;
        int settingsReads = 0;
        int thresholdCalls = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof TypeInsnNode && instruction.getOpcode() == Opcodes.NEW
                && "java/util/HashMap".equals(((TypeInsnNode) instruction).desc)) maps++;
            if (instruction instanceof FieldInsnNode && instruction.getOpcode() == Opcodes.GETFIELD) {
                FieldInsnNode field = (FieldInsnNode) instruction;
                if (node.name.equals(field.owner) && SETTINGS.equals(field.desc)) {
                    settings = field;
                    settingsReads++;
                }
            }
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.getOpcode() == Opcodes.INVOKEINTERFACE && "java/util/Map".equals(call.owner)
                && "put".equals(call.name)
                && "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;".equals(call.desc)) puts++;
            if ("getNoiseThreshold".equals(call.name) && "()F".equals(call.desc)) {
                thresholdGetter = call;
                thresholdCalls++;
            }
        }
        if (maps != 1 || puts != 1 || settingsReads != 1 || thresholdCalls != 1
            || settings == null || thresholdGetter == null) {
            throw new IllegalStateException("Better Caves generateThresholds 调用图变化：maps="
                + maps + ", puts=" + puts + ", settings=" + settingsReads
                + ", threshold=" + thresholdCalls);
        }

        LabelNode original = new LabelNode();
        InsnList fast = new InsnList();
        fast.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "isEnabled", "()Z", false));
        fast.add(new JumpInsnNode(Opcodes.IFEQ, original));
        fast.add(new TypeInsnNode(Opcodes.NEW, THRESHOLDS));
        fast.add(new InsnNode(Opcodes.DUP));
        fast.add(new VarInsnNode(Opcodes.ILOAD, 1));
        fast.add(new VarInsnNode(Opcodes.ILOAD, 2));
        fast.add(new VarInsnNode(Opcodes.ILOAD, 3));
        fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fast.add(new FieldInsnNode(Opcodes.GETFIELD, settings.owner, settings.name, settings.desc));
        fast.add(new MethodInsnNode(thresholdGetter.getOpcode(), thresholdGetter.owner,
            thresholdGetter.name, thresholdGetter.desc, thresholdGetter.itf));
        fast.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, THRESHOLDS, "<init>", "(IIIF)V", false));
        fast.add(new InsnNode(Opcodes.ARETURN));
        fast.add(original);
        method.instructions.insert(fast);

        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
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
            throw new IllegalStateException("Better Caves CaveCarver " + name + descriptor
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
