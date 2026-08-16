package dev.rlcraft.ice.hooks;

import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Removes reviewed OptiFine Reflector calls from chunk light/AO hot paths. */
final class ForgeBlockStateDirectAdapter implements OptimizerBytecodeAdapter {
    enum Part { REFLECTOR_FORGE, STATE_IMPLEMENTATION }

    static final String REFLECTOR_FORGE = "net/optifine/reflect/ReflectorForge";
    static final String STATE =
        "net/minecraft/block/state/BlockStateContainer$StateImplementation";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/chunk/ForgeBlockStateDirectBridge";
    static final String ORIGINAL_REFLECTOR_LIGHT = "ice$original$getLightValue";
    static final String ORIGINAL_STATE_LIGHT = "ice$original$getLightValueWorld";
    static final String ORIGINAL_SIDE_RENDER = "ice$original$doesSideBlockRendering";
    private final Part part;

    ForgeBlockStateDirectAdapter(Part part) {
        this.part = part;
    }

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        String expected = part == Part.REFLECTOR_FORGE ? REFLECTOR_FORGE : STATE;
        if (!expected.equals(node.name)
            || !expected.equals(transformedName.replace('.', '/'))) {
            throw new IllegalStateException("Forge 区块直调目标类变化：" + node.name);
        }
        if (part == Part.REFLECTOR_FORGE) transformReflectorForge(node);
        else transformState(node);
        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void transformReflectorForge(ClassNode node) {
        MethodNode method = findObjectMethod(node, "getLightValue", Type.INT_TYPE, 3);
        if ((method.access & Opcodes.ACC_STATIC) == 0) {
            throw new IllegalStateException("ReflectorForge.getLightValue 不再是静态方法");
        }
        if (countCalls(method, "net/optifine/reflect/ReflectorMethod", "exists") != 1
            || countCalls(method, "net/optifine/reflect/Reflector", "callInt") != 1) {
            throw new IllegalStateException("ReflectorForge.getLightValue 反射调用图变化");
        }
        wrapInt(node, method, ORIGINAL_REFLECTOR_LIGHT, "tryStateLightValue", true);
    }

    private static void transformState(ClassNode node) {
        MethodNode light = findObjectMethod(node, "getLightValue", Type.INT_TYPE, 2);
        MethodNode side = findObjectMethod(node, "doesSideBlockRendering", Type.BOOLEAN_TYPE, 3);
        int lightReflect = countCalls(light, "net/optifine/reflect/Reflector", "callInt");
        int sideReflect = countCalls(side, "net/optifine/reflect/Reflector", "callBoolean");
        if (lightReflect == 0 && sideReflect == 0) {
            throw new OptimizerAdapterSkippedException(
                "当前 BlockState 实现已经是 Forge 直调，不需要额外包装");
        }
        if (lightReflect != 1 || sideReflect != 1) {
            throw new IllegalStateException("OptiFine BlockState 反射调用图变化：light="
                + lightReflect + ", side=" + sideReflect);
        }
        wrapInt(node, light, ORIGINAL_STATE_LIGHT, "tryBlockLightValue", false);
        wrapBoolean(node, side);
    }

    private static void wrapInt(ClassNode node, MethodNode method, String originalName,
                                String bridgeMethod, boolean isStatic) {
        rejectMethod(node, originalName, method.desc);
        int access = method.access;
        String descriptor = method.desc;
        String signature = method.signature;
        String[] exceptions = exceptions(method);
        method.name = originalName;
        method.access = (method.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
            | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;

        MethodNode wrapper = new MethodNode(Opcodes.ASM5, access,
            isStatic ? "getLightValue" : "getLightValue", descriptor,
            signature, exceptions);
        Type[] arguments = Type.getArgumentTypes(descriptor);
        int resultLocal = isStatic ? arguments.length : arguments.length + 1;
        LabelNode fallback = new LabelNode();
        int local = 0;
        if (!isStatic) wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, local++));
        for (Type ignored : arguments) {
            wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, local++));
        }
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
            bridgeMethod, "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)I", false));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ISTORE, resultLocal));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ILOAD, resultLocal));
        wrapper.instructions.add(new LdcInsnNode(Integer.valueOf(Integer.MIN_VALUE)));
        wrapper.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, fallback));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ILOAD, resultLocal));
        wrapper.instructions.add(new InsnNode(Opcodes.IRETURN));
        wrapper.instructions.add(fallback);
        local = 0;
        if (!isStatic) wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, local++));
        for (Type ignored : arguments) {
            wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, local++));
        }
        wrapper.instructions.add(new MethodInsnNode(
            isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL,
            node.name, originalName, descriptor, false));
        wrapper.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(wrapper);
    }

    private static void wrapBoolean(ClassNode node, MethodNode method) {
        rejectMethod(node, ORIGINAL_SIDE_RENDER, method.desc);
        int access = method.access;
        String descriptor = method.desc;
        String signature = method.signature;
        String[] exceptions = exceptions(method);
        method.name = ORIGINAL_SIDE_RENDER;
        method.access = (method.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
            | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;

        MethodNode wrapper = new MethodNode(Opcodes.ASM5, access,
            "doesSideBlockRendering", descriptor, signature, exceptions);
        LabelNode fallback = new LabelNode();
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
            "tryDoesSideBlockRendering",
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)I", false));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ISTORE, 4));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 4));
        wrapper.instructions.add(new InsnNode(Opcodes.ICONST_M1));
        wrapper.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, fallback));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 4));
        wrapper.instructions.add(new InsnNode(Opcodes.IRETURN));
        wrapper.instructions.add(fallback);
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name,
            ORIGINAL_SIDE_RENDER, descriptor, false));
        wrapper.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(wrapper);
    }

    private static MethodNode findObjectMethod(ClassNode node, String name,
                                               Type returnType, int argumentCount) {
        MethodNode found = null;
        int count = 0;
        for (MethodNode method : node.methods) {
            if (!name.equals(method.name) || !returnType.equals(Type.getReturnType(method.desc))) continue;
            Type[] arguments = Type.getArgumentTypes(method.desc);
            if (arguments.length != argumentCount) continue;
            boolean objects = true;
            for (Type argument : arguments) {
                if (argument.getSort() != Type.OBJECT) objects = false;
            }
            if (!objects) continue;
            found = method;
            count++;
        }
        if (count != 1 || found == null) {
            throw new IllegalStateException(node.name + '.' + name
                + " 对象参数方法数量变化：" + count);
        }
        return found;
    }

    private static int countCalls(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (owner.equals(call.owner) && name.equals(call.name)) count++;
        }
        return count;
    }

    private static void rejectMethod(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                throw new IllegalStateException(node.name + " 已存在 " + name);
            }
        }
    }

    private static String[] exceptions(MethodNode method) {
        @SuppressWarnings("unchecked")
        List<String> values = method.exceptions;
        return values == null ? null : values.toArray(new String[values.size()]);
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String type1, String type2) {
            return "java/lang/Object";
        }
    }
}
