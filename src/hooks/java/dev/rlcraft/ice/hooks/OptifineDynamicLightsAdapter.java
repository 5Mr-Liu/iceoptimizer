package dev.rlcraft.ice.hooks;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Installs a lock-free snapshot boundary around OptiFine dynamic lights. */
final class OptifineDynamicLightsAdapter implements OptimizerBytecodeAdapter {
    enum Part { LIGHTS, LIGHT, MAP }

    static final String LIGHTS = "net/optifine/DynamicLights";
    static final String LIGHT = "net/optifine/DynamicLight";
    static final String MAP = "net/optifine/DynamicLightsMap";
    static final String LIGHT_ACCESS =
        "dev/rlcraft/ice/optimizer/compat/optifine/DynamicLightAccessor";
    static final String MAP_ACCESS =
        "dev/rlcraft/ice/optimizer/compat/optifine/DynamicLightsMapAccessor";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/optifine/OptifineDynamicLightsBridge";
    static final String ORIGINAL_LIGHT_LEVEL = "ice$originalGetLightLevel";
    private static final String MAP_FIELD = "mapDynamicLights";
    private static final String MAP_DESCRIPTOR = "L" + MAP + ";";

    private final Part part;

    OptifineDynamicLightsAdapter(Part part) {
        if (part == null) throw new IllegalArgumentException("part");
        this.part = part;
    }

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        switch (part) {
            case LIGHTS:
                requireTarget(node, LIGHTS);
                transformLights(node);
                break;
            case LIGHT:
                requireTarget(node, LIGHT);
                transformLight(node);
                break;
            case MAP:
                requireTarget(node, MAP);
                transformMap(node);
                break;
            default:
                throw new IllegalStateException("未知 OptiFine 动态光适配部分：" + part);
        }
        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void transformLights(ClassNode node) {
        requireField(node, MAP_FIELD, MAP_DESCRIPTOR);
        MethodNode lightLevel = requireBlockPosLightMethod(node);
        rejectMethod(node, ORIGINAL_LIGHT_LEVEL, lightLevel.desc);
        wrapLightLevel(node, lightLevel);

        MethodNode update = requireNamedVoidMethod(node, "update", 1);
        MethodInsnNode updateMap = uniqueCall(update, node.name, "updateMapDynamicLights");
        int updateRefreshes = injectRefreshAfter(update, updateMap);
        if (updateRefreshes < 1) {
            throw new IllegalStateException("OptiFine update 未找到动态光表更新后的返回路径");
        }
        injectRefreshBeforeAllReturns(requireNamedVoidMethod(node, "entityRemoved", 2));
        injectRefreshBeforeAllReturns(requireNamedVoidMethod(node, "removeLights", 1));
        injectRefreshBeforeAllReturns(requireNamedVoidMethod(node, "clear", 0));
    }

    private static void wrapLightLevel(ClassNode node, MethodNode original) {
        int access = original.access;
        String descriptor = original.desc;
        String signature = original.signature;
        String[] exceptions = exceptions(original);
        Type argument = Type.getArgumentTypes(descriptor)[0];
        original.name = ORIGINAL_LIGHT_LEVEL;
        original.access = (original.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
            | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;

        MethodNode wrapper = new MethodNode(Opcodes.ASM5, access, "getLightLevel",
            descriptor, signature, exceptions);
        LabelNode fallback = new LabelNode();
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            "Config", "isClearWater", "()Z", false));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
            "getLightLevel", "(" + argument.getDescriptor() + "Z)D", false));
        wrapper.instructions.add(new VarInsnNode(Opcodes.DSTORE, 1));
        wrapper.instructions.add(new VarInsnNode(Opcodes.DLOAD, 1));
        wrapper.instructions.add(new InsnNode(Opcodes.DCONST_0));
        wrapper.instructions.add(new InsnNode(Opcodes.DCMPL));
        wrapper.instructions.add(new JumpInsnNode(Opcodes.IFLT, fallback));
        wrapper.instructions.add(new VarInsnNode(Opcodes.DLOAD, 1));
        wrapper.instructions.add(new InsnNode(Opcodes.DRETURN));
        wrapper.instructions.add(fallback);
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, node.name,
            ORIGINAL_LIGHT_LEVEL, descriptor, false));
        wrapper.instructions.add(new InsnNode(Opcodes.DRETURN));
        node.methods.add(wrapper);
    }

    private static void transformLight(ClassNode node) {
        rejectInterface(node, LIGHT_ACCESS);
        requireMethod(node, "getLastLightLevel", "()I");
        requireMethod(node, "getLastPosX", "()D");
        requireMethod(node, "getLastPosY", "()D");
        requireMethod(node, "getLastPosZ", "()D");
        requireMethod(node, "isUnderwater", "()Z");
        node.interfaces.add(LIGHT_ACCESS);
        addForwarder(node, "ice$lastLightLevel", "()I", "getLastLightLevel", Opcodes.IRETURN);
        addForwarder(node, "ice$lastPosX", "()D", "getLastPosX", Opcodes.DRETURN);
        addForwarder(node, "ice$lastPosY", "()D", "getLastPosY", Opcodes.DRETURN);
        addForwarder(node, "ice$lastPosZ", "()D", "getLastPosZ", Opcodes.DRETURN);
        addForwarder(node, "ice$isUnderwater", "()Z", "isUnderwater", Opcodes.IRETURN);
    }

    private static void transformMap(ClassNode node) {
        rejectInterface(node, MAP_ACCESS);
        requireMethod(node, "valueList", "()Ljava/util/List;");
        node.interfaces.add(MAP_ACCESS);
        addForwarder(node, "ice$valueList", "()Ljava/util/List;", "valueList", Opcodes.ARETURN);
    }

    private static int injectRefreshAfter(MethodNode method, AbstractInsnNode boundary) {
        boolean after = false;
        List<AbstractInsnNode> returns = new ArrayList<AbstractInsnNode>();
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction == boundary) after = true;
            if (after && instruction.getOpcode() == Opcodes.RETURN) returns.add(instruction);
        }
        for (AbstractInsnNode instruction : returns) {
            method.instructions.insertBefore(instruction, refreshInstructions());
        }
        return returns.size();
    }

    private static void injectRefreshBeforeAllReturns(MethodNode method) {
        List<AbstractInsnNode> returns = new ArrayList<AbstractInsnNode>();
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.RETURN) returns.add(instruction);
        }
        if (returns.isEmpty()) throw new IllegalStateException(method.name + " 不含 RETURN");
        for (AbstractInsnNode instruction : returns) {
            method.instructions.insertBefore(instruction, refreshInstructions());
        }
    }

    private static InsnList refreshInstructions() {
        InsnList code = new InsnList();
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, LIGHTS, MAP_FIELD, MAP_DESCRIPTOR));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
            "refresh", "(Ljava/lang/Object;)V", false));
        return code;
    }

    private static void addForwarder(ClassNode node, String exposed, String descriptor,
                                     String target, int returnOpcode) {
        rejectMethod(node, exposed, descriptor);
        MethodNode method = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            exposed, descriptor, null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
            node.name, target, descriptor, false));
        method.instructions.add(new InsnNode(returnOpcode));
        node.methods.add(method);
    }

    private static MethodNode requireBlockPosLightMethod(ClassNode node) {
        MethodNode found = null;
        int count = 0;
        for (MethodNode method : node.methods) {
            if (!"getLightLevel".equals(method.name) || Type.getReturnType(method.desc).getSort() != Type.DOUBLE) {
                continue;
            }
            Type[] arguments = Type.getArgumentTypes(method.desc);
            if (arguments.length != 1 || arguments[0].getSort() != Type.OBJECT) continue;
            found = method;
            count++;
        }
        if (count != 1) throw new IllegalStateException("OptiFine BlockPos 光照方法数量变化：" + count);
        if ((found.access & Opcodes.ACC_STATIC) == 0) {
            throw new IllegalStateException("OptiFine BlockPos 光照方法不再是 static");
        }
        return found;
    }

    private static MethodNode requireNamedVoidMethod(ClassNode node, String name, int arguments) {
        MethodNode found = null;
        int count = 0;
        for (MethodNode method : node.methods) {
            if (!name.equals(method.name) || Type.getReturnType(method.desc).getSort() != Type.VOID
                || Type.getArgumentTypes(method.desc).length != arguments) continue;
            found = method;
            count++;
        }
        if (count != 1) throw new IllegalStateException(node.name + '.' + name
            + " 方法数量变化：" + count);
        return found;
    }

    private static MethodInsnNode uniqueCall(MethodNode method, String owner, String name) {
        MethodInsnNode found = null;
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (owner.equals(call.owner) && name.equals(call.name)) {
                found = call;
                count++;
            }
        }
        if (count != 1) throw new IllegalStateException(method.name + " 调用数量变化："
            + owner + '.' + name + '=' + count);
        return found;
    }

    private static MethodNode requireMethod(ClassNode node, String name, String descriptor) {
        MethodNode found = null;
        int count = 0;
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                found = method;
                count++;
            }
        }
        if (count != 1) throw new IllegalStateException(node.name + " 方法数量变化："
            + name + descriptor + '=' + count);
        return found;
    }

    private static void rejectMethod(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                throw new IllegalStateException(node.name + " 已存在 ICE 方法 " + name + descriptor);
            }
        }
    }

    private static void requireField(ClassNode node, String name, String descriptor) {
        int count = 0;
        for (FieldNode field : node.fields) {
            if (name.equals(field.name) && descriptor.equals(field.desc)) count++;
        }
        if (count != 1) throw new IllegalStateException(node.name + " 字段数量变化："
            + name + descriptor + '=' + count);
    }

    private static void rejectInterface(ClassNode node, String name) {
        if (node.interfaces.contains(name)) throw new IllegalStateException(node.name + " 已实现 " + name);
    }

    private static void requireTarget(ClassNode node, String expected) {
        if (!expected.equals(node.name)) {
            throw new IllegalStateException("OptiFine 动态光目标变化：" + node.name + "，期望 " + expected);
        }
    }

    @SuppressWarnings("unchecked")
    private static String[] exceptions(MethodNode method) {
        List<String> values = method.exceptions;
        return values == null ? null : values.toArray(new String[values.size()]);
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String left, String right) {
            return "java/lang/Object";
        }
    }
}
