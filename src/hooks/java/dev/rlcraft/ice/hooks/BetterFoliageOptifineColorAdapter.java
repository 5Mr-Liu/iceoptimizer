package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Caches Better Foliage's OptiFine GameSettings field instead of reflecting per block. */
final class BetterFoliageOptifineColorAdapter implements OptimizerBytecodeAdapter {
    static final String METHOD = "getBlockColor";
    static final String DESCRIPTOR = "(Lmods/octarinecore/client/render/BlockContext;)I";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/chunk/BetterFoliageOptifineColorBridge";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!transformedName.replace('.', '/').equals(node.name)) {
            throw new IllegalStateException("Better Foliage OptifineCustomColors 类名变化：" + node.name);
        }
        MethodNode method = find(node, METHOD, DESCRIPTOR);
        LdcInsnNode start = null;
        MethodInsnNode end = null;
        int fieldNames = 0;
        int equalityCalls = 0;
        int reflectConstructors = 0;
        int accessibleCalls = 0;
        int fieldGets = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof LdcInsnNode
                && "ofCustomColors".equals(((LdcInsnNode) instruction).cst)) {
                start = (LdcInsnNode) instruction;
                fieldNames++;
            }
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if ("kotlin/jvm/internal/Intrinsics".equals(call.owner)
                && "areEqual".equals(call.name)
                && "(Ljava/lang/Object;Ljava/lang/Object;)Z".equals(call.desc)) {
                end = call;
                equalityCalls++;
            }
            if ("mods/octarinecore/metaprog/Reflection$reflectField$1".equals(call.owner)
                && "<init>".equals(call.name)
                && "(Ljava/lang/Object;Ljava/lang/String;)V".equals(call.desc)) reflectConstructors++;
            if ("java/lang/reflect/Field".equals(call.owner) && "setAccessible".equals(call.name)
                && "(Z)V".equals(call.desc)) accessibleCalls++;
            if ("java/lang/reflect/Field".equals(call.owner) && "get".equals(call.name)
                && "(Ljava/lang/Object;)Ljava/lang/Object;".equals(call.desc)) fieldGets++;
        }
        if (fieldNames != 1 || equalityCalls != 1 || reflectConstructors != 1
            || accessibleCalls != 1 || fieldGets != 1 || start == null || end == null) {
            throw new IllegalStateException("Better Foliage OptiFine 颜色反射调用图变化：field="
                + fieldNames + ", equality=" + equalityCalls + ", reflect=" + reflectConstructors
                + ", accessible=" + accessibleCalls + ", get=" + fieldGets);
        }
        AbstractInsnNode settingsStoreInsn = previousOpcode(start);
        AbstractInsnNode following = nextOpcode(end);
        if (!(settingsStoreInsn instanceof VarInsnNode)
            || settingsStoreInsn.getOpcode() != Opcodes.ASTORE
            || !(following instanceof JumpInsnNode) || following.getOpcode() != Opcodes.IFEQ) {
            throw new IllegalStateException("Better Foliage OptiFine 颜色布尔分支结构变化");
        }
        int settingsLocal = ((VarInsnNode) settingsStoreInsn).var;

        InsnList replacement = new InsnList();
        replacement.add(new VarInsnNode(Opcodes.ALOAD, settingsLocal));
        replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
            "isCustomColorsEnabled", "(Ljava/lang/Object;)Z", false));
        method.instructions.insertBefore(start, replacement);
        AbstractInsnNode current = start;
        while (current != null) {
            AbstractInsnNode next = current.getNext();
            method.instructions.remove(current);
            if (current == end) break;
            current = next;
        }

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
            throw new IllegalStateException("Better Foliage " + name + descriptor
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
