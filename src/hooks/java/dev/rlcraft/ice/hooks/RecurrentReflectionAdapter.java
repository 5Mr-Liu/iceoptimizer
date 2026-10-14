package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.FrameNode;

/** Leaves invocation and exception handlers intact; replaces repeated metadata lookup. */
final class RecurrentReflectionAdapter implements OptimizerBytecodeAdapter {
    private static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/recurrent/RecurrentReflectionBridge";
    @Override public byte[] transform(String transformedName, byte[] bytes, TargetSpec target) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, 0);
        int changed = 0;
        for (MethodNode method : node.methods) {
            if ("ivorius/reccomplex/events/handlers/RCTerrainGenEventHandler".equals(node.name)
                && ("hasAmountData".equals(method.name) || "getModifiedAmount".equals(method.name)
                    || "setModifiedAmount".equals(method.name))) {
                boolean setter = "setModifiedAmount".equals(method.name);
                boolean has = "hasAmountData".equals(method.name);
                LabelNode original = new LabelNode(); InsnList entry = new InsnList();
                entry.add(new VarInsnNode(Opcodes.ALOAD, 0));
                entry.add(new InsnNode(setter ? Opcodes.ICONST_2 : has ? Opcodes.ICONST_3 : Opcodes.ICONST_1));
                entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "methodsAvailable", "(Ljava/lang/Object;I)Z", false));
                entry.add(new JumpInsnNode(Opcodes.IFNE, original));
                if (!setter) entry.add(new InsnNode(has ? Opcodes.ICONST_0 : Opcodes.ICONST_M1));
                entry.add(new InsnNode(setter ? Opcodes.RETURN : Opcodes.IRETURN)); entry.add(original);
                // The new branch reaches the unchanged method entry with its original
                // argument locals and an empty stack. All later original frames remain valid.
                entry.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                method.instructions.insert(entry);
            }
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if ("java/lang/Class".equals(call.owner) && "getDeclaredMethod".equals(call.name)) {
                call.setOpcode(Opcodes.INVOKESTATIC);
                call.owner = BRIDGE;
                call.name = "declaredMethod";
                call.desc = "(Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;";
                changed++;
            } else if ("net/minecraftforge/fml/relauncher/ReflectionHelper".equals(call.owner)
                    && "findMethod".equals(call.name)) {
                call.owner = BRIDGE;
                call.name = "structureMethod";
                changed++;
            }
            }
        }
        if (changed == 0) throw new IllegalStateException("Recurrent reflection calls missing: " + node.name);
        // Each replacement has the original operand-stack signature. Preserve the
        // input hierarchy information, including shared multi-catch handler frames.
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
