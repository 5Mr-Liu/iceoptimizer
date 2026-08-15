package dev.rlcraft.ice.hooks;

import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Installs an allocation-reduced replacement while retaining the original method as a fallback. */
final class OtgStringHelperAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET_METHOD = "readCommaSeperatedString";
    static final String ORIGINAL_METHOD = "ice$original$readCommaSeperatedString";
    static final String DESCRIPTOR = "(Ljava/lang/String;)[Ljava/lang/String;";
    static final String BRIDGE_OWNER = "dev/rlcraft/ice/optimizer/compat/otg/OtgParsingBridge";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!transformedName.replace('.', '/').equals(node.name)) {
            throw new IllegalStateException("OTG StringHelper 类名变化：" + node.name);
        }

        MethodNode original = null;
        int matches = 0;
        for (MethodNode method : node.methods) {
            if (TARGET_METHOD.equals(method.name) && DESCRIPTOR.equals(method.desc)) {
                original = method;
                matches++;
            }
            if (ORIGINAL_METHOD.equals(method.name)) {
                throw new IllegalStateException("OTG StringHelper 已存在 ICE 备份方法");
            }
        }
        if (matches != 1 || original == null || (original.access & Opcodes.ACC_STATIC) == 0) {
            throw new IllegalStateException("OTG StringHelper 目标方法匹配数量应为 1，实际 " + matches);
        }

        int wrapperAccess = original.access;
        String signature = original.signature;
        @SuppressWarnings("unchecked")
        List<String> originalExceptions = original.exceptions;
        String[] exceptions = originalExceptions == null
            ? null : originalExceptions.toArray(new String[originalExceptions.size()]);
        original.name = ORIGINAL_METHOD;
        original.access = (original.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
            | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;

        MethodNode wrapper = new MethodNode(Opcodes.ASM5, wrapperAccess, TARGET_METHOD,
            DESCRIPTOR, signature, exceptions);
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        InsnList instructions = wrapper.instructions;
        instructions.add(start);
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE_OWNER,
            "readCommaSeparatedString", DESCRIPTOR, false));
        instructions.add(new InsnNode(Opcodes.ARETURN));
        instructions.add(end);
        instructions.add(handler);
        instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, node.name,
            ORIGINAL_METHOD, DESCRIPTOR, false));
        instructions.add(new InsnNode(Opcodes.ARETURN));
        wrapper.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/LinkageError"));
        wrapper.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/RuntimeException"));
        node.methods.add(wrapper);

        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String type1, String type2) {
            return "java/lang/Object";
        }
    }
}
