package dev.rlcraft.ice.hooks;

import java.util.HashMap;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Routes two hot ObjectManager getters through a switchable single-probe bridge. */
final class LycanitesObjectManagerAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "com/lycanitesmobs/ObjectManager";
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/lycanites/LycanitesRegistryBridge";
    static final String BRIDGE_DESCRIPTOR = "(Ljava/util/Map;Ljava/lang/String;)Ljava/lang/Object;";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(originalClass).accept(node, 0);
        if (!TARGET.equals(node.name)) throw new IllegalStateException("Lycanites ObjectManager 类名变化：" + node.name);
        Map<String, GetterSpec> expected = new HashMap<String, GetterSpec>();
        expected.put("getBlock(Ljava/lang/String;)Lnet/minecraft/block/Block;",
            new GetterSpec("blocks", "net/minecraft/block/Block"));
        expected.put("getEffect(Ljava/lang/String;)Lcom/lycanitesmobs/PotionBase;",
            new GetterSpec("effects", "com/lycanitesmobs/PotionBase"));
        int patched = 0;
        for (Object value : node.methods) {
            MethodNode method = (MethodNode) value;
            GetterSpec spec = expected.get(method.name + method.desc);
            if (spec == null) continue;
            boolean normalizes = validate(method, spec);
            rewrite(method, spec, normalizes);
            patched++;
        }
        if (patched != expected.size()) throw new IllegalStateException("Lycanites 注册表 getter 数量变化：" + patched);
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean validate(MethodNode method, GetterSpec spec) {
        int lower = 0;
        int contains = 0;
        int get = 0;
        int mapReads = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
             instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if ("java/lang/String".equals(call.owner) && "toLowerCase".equals(call.name)
                    && "()Ljava/lang/String;".equals(call.desc)) lower++;
                if ("java/util/Map".equals(call.owner) && "containsKey".equals(call.name)) contains++;
                if ("java/util/Map".equals(call.owner) && "get".equals(call.name)) get++;
            } else if (instruction instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) instruction;
                if (field.getOpcode() == Opcodes.GETSTATIC && TARGET.equals(field.owner)
                    && spec.field.equals(field.name) && "Ljava/util/Map;".equals(field.desc)) mapReads++;
            }
        }
        if ((lower != 0 && lower != 1) || contains != 1 || get != 1 || mapReads != 2) {
            throw new IllegalStateException("Lycanites " + method.name + " 调用图变化：lower=" + lower
                + ", contains=" + contains + ", get=" + get + ", mapReads=" + mapReads);
        }
        return lower == 1;
    }

    private static void rewrite(MethodNode method, GetterSpec spec, boolean normalizes) {
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) method.localVariables.clear();
        InsnList code = method.instructions;
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, TARGET, spec.field, "Ljava/util/Map;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
            normalizes ? "lookup" : "lookupExact", BRIDGE_DESCRIPTOR, false));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, spec.returnType));
        code.add(new InsnNode(Opcodes.ARETURN));
    }

    private static final class GetterSpec {
        private final String field;
        private final String returnType;
        private GetterSpec(String field, String returnType) { this.field = field; this.returnType = returnType; }
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(int flags) { super(flags); }
        @Override protected String getCommonSuperClass(String left, String right) { return "java/lang/Object"; }
    }
}
