package dev.rlcraft.ice.hooks;

import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Preserves Konkrete's reflective scan as fallback and adds a cached wrapper. */
final class KonkreteLocaleAdapter implements OptimizerBytecodeAdapter {
    static final String CLASS = "de/keksuccino/konkrete/localization/LocaleUtils";
    static final String METHOD = "getKeyForString";
    static final String DESCRIPTOR = "(Ljava/lang/String;)Ljava/lang/String;";
    static final String ORIGINAL = "ice$original$getKeyForString";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/konkrete/KonkreteLocaleBridge";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!CLASS.equals(node.name)
            || !transformedName.replace('.', '/').equals(node.name)) {
            throw new IllegalStateException("Konkrete LocaleUtils 类名变化：" + node.name);
        }
        rejectMethod(node, ORIGINAL, DESCRIPTOR);
        MethodNode method = requireMethod(node, METHOD, DESCRIPTOR);
        if ((method.access & Opcodes.ACC_STATIC) == 0) {
            throw new IllegalStateException("Konkrete getKeyForString 不再是静态方法");
        }
        validateGraph(method);

        int access = method.access;
        String signature = method.signature;
        @SuppressWarnings("unchecked")
        List<String> exceptionList = method.exceptions;
        String[] exceptions = exceptionList == null
            ? null : exceptionList.toArray(new String[exceptionList.size()]);
        method.name = ORIGINAL;
        method.access = (method.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
            | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;

        MethodNode wrapper = new MethodNode(Opcodes.ASM5, access, METHOD,
            DESCRIPTOR, signature, exceptions);
        LabelNode optimized = new LabelNode();
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
            "lookup", "(Ljava/lang/String;)Ljava/lang/Object;", false));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
            "isFallback", "(Ljava/lang/Object;)Z", false));
        wrapper.instructions.add(new JumpInsnNode(Opcodes.IFEQ, optimized));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CLASS,
            ORIGINAL, DESCRIPTOR, false));
        wrapper.instructions.add(new InsnNode(Opcodes.ARETURN));
        wrapper.instructions.add(optimized);
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"));
        wrapper.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(wrapper);

        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void validateGraph(MethodNode method) {
        int findFields = 0;
        int fieldGets = 0;
        int entrySets = 0;
        int stringEquals = 0;
        int printStackTraces = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if ("net/minecraftforge/fml/common/ObfuscationReflectionHelper".equals(call.owner)
                && "findField".equals(call.name)
                && "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/reflect/Field;".equals(call.desc)) {
                findFields++;
            } else if ("java/lang/reflect/Field".equals(call.owner)
                && "get".equals(call.name)
                && "(Ljava/lang/Object;)Ljava/lang/Object;".equals(call.desc)) {
                fieldGets++;
            } else if ("java/util/Map".equals(call.owner)
                && "entrySet".equals(call.name)
                && "()Ljava/util/Set;".equals(call.desc)) {
                entrySets++;
            } else if ("java/lang/String".equals(call.owner)
                && "equals".equals(call.name)
                && "(Ljava/lang/Object;)Z".equals(call.desc)) {
                stringEquals++;
            } else if ("java/lang/Exception".equals(call.owner)
                && "printStackTrace".equals(call.name)
                && "()V".equals(call.desc)) {
                printStackTraces++;
            }
        }
        int catches = method.tryCatchBlocks == null ? 0 : method.tryCatchBlocks.size();
        boolean exceptionHandlerShape = catches >= 1 && catches <= 3;
        Object handler = null;
        if (exceptionHandlerShape) {
            for (TryCatchBlockNode block : method.tryCatchBlocks) {
                if (!"java/lang/Exception".equals(block.type)
                    || (handler != null && handler != block.handler)) {
                    exceptionHandlerShape = false;
                    break;
                }
                handler = block.handler;
            }
        }
        if (findFields != 2 || fieldGets != 2 || entrySets != 1
            || stringEquals != 1 || printStackTraces != 1
            || !exceptionHandlerShape) {
            throw new IllegalStateException("Konkrete 本地化扫描调用图变化：findField="
                + findFields + ", fieldGet=" + fieldGets + ", entrySet=" + entrySets
                + ", equals=" + stringEquals + ", print=" + printStackTraces
                + ", catches=" + catches);
        }
    }

    private static MethodNode requireMethod(ClassNode node, String name, String descriptor) {
        MethodNode match = null;
        int count = 0;
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                match = method;
                count++;
            }
        }
        if (count != 1 || match == null) {
            throw new IllegalStateException("Konkrete " + name + descriptor
                + " 匹配数量应为 1，实际 " + count);
        }
        return match;
    }

    private static void rejectMethod(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                throw new IllegalStateException("Konkrete LocaleUtils 已存在 ICE fallback 方法");
            }
        }
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String type1, String type2) {
            return "java/lang/Object";
        }
    }
}
