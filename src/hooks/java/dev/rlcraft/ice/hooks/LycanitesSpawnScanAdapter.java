package dev.rlcraft.ice.hooks;

import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Surgical, call-scoped optimization for the reviewed Lycanites block spawn scan. */
final class LycanitesSpawnScanAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET =
        "com/lycanitesmobs/core/spawner/location/BlockSpawnLocation";
    static final String ACCESSOR =
        "dev/rlcraft/ice/optimizer/compat/lycanites/LycanitesSpawnScanAccessor";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/lycanites/LycanitesSpawnScanBridge";
    static final String SCAN_METHOD = "getSpawnPositions";
    static final String SCAN_DESCRIPTOR =
        "(Lnet/minecraft/world/World;Lnet/minecraft/entity/player/EntityPlayer;"
            + "Lnet/minecraft/util/math/BlockPos;)Ljava/util/List;";
    static final String BACKUP_METHOD = "ice$scan$getSpawnPositions";
    static final String VALID_METHOD = "isValidBlock";
    static final String VALID_DESCRIPTOR =
        "(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;)Z";
    static final String STATE_OWNER = "net/minecraft/world/World";
    static final String STATE_METHOD = "func_180495_p";
    static final String STATE_DESCRIPTOR =
        "(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/state/IBlockState;";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(node.name)) {
            throw new IllegalStateException("Lycanites BlockSpawnLocation 类名变化：" + node.name);
        }
        if (node.interfaces.contains(ACCESSOR)) {
            throw new IllegalStateException("Lycanites 刷怪扫描访问器已安装");
        }
        requireField(node, "blockIds", "Ljava/util/List;");
        requireField(node, "surface", "Z");
        requireField(node, "underground", "Z");
        MethodNode scan = requireMethod(node, SCAN_METHOD, SCAN_DESCRIPTOR);
        MethodNode valid = requireMethod(node, VALID_METHOD, VALID_DESCRIPTOR);
        rejectMethod(node, BACKUP_METHOD, SCAN_DESCRIPTOR);
        validateScan(scan);
        validateValid(valid);

        replaceCounterAllocation(scan);
        replaceStateCall(scan, 0, "scanState");
        replaceStateCall(valid, 0, "validationState");

        node.interfaces.add(ACCESSOR);
        int wrapperAccess = scan.access;
        String signature = scan.signature;
        String[] exceptions = exceptions(scan);
        scan.name = BACKUP_METHOD;
        scan.access = (scan.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
            | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
        node.methods.add(scanWrapper(wrapperAccess, signature, exceptions));
        addFieldAccessor(node, "ice$spawnSurface", "()Z", "surface", "Z", Opcodes.IRETURN);
        addFieldAccessor(node, "ice$spawnUnderground", "()Z", "underground", "Z", Opcodes.IRETURN);
        addFieldAccessor(node, "ice$spawnBlockIds", "()Ljava/util/List;",
            "blockIds", "Ljava/util/List;", Opcodes.ARETURN);

        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void validateScan(MethodNode method) {
        int hashMaps = 0;
        int hashConstructors = 0;
        int stateCalls = 0;
        int validCalls = 0;
        int contains = 0;
        int gets = 0;
        int puts = 0;
        int sizes = 0;
        int values = 0;
        int sorts = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
             instruction = instruction.getNext()) {
            if (instruction instanceof TypeInsnNode) {
                TypeInsnNode type = (TypeInsnNode) instruction;
                if (type.getOpcode() == Opcodes.NEW && "java/util/HashMap".equals(type.desc)) hashMaps++;
            } else if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.getOpcode() == Opcodes.INVOKESPECIAL && "java/util/HashMap".equals(call.owner)
                    && "<init>".equals(call.name) && "()V".equals(call.desc)) hashConstructors++;
                if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && STATE_OWNER.equals(call.owner)
                    && STATE_METHOD.equals(call.name) && STATE_DESCRIPTOR.equals(call.desc)) stateCalls++;
                if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && TARGET.equals(call.owner)
                    && VALID_METHOD.equals(call.name) && VALID_DESCRIPTOR.equals(call.desc)) validCalls++;
                if (call.getOpcode() == Opcodes.INVOKEINTERFACE && "java/util/Map".equals(call.owner)) {
                    if ("containsKey".equals(call.name)) contains++;
                    else if ("get".equals(call.name)) gets++;
                    else if ("put".equals(call.name)) puts++;
                    else if ("size".equals(call.name)) sizes++;
                    else if ("values".equals(call.name)) values++;
                }
                if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && TARGET.equals(call.owner)
                    && "sortSpawnPositions".equals(call.name)
                    && "(Ljava/util/List;Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;)Ljava/util/List;"
                        .equals(call.desc)) sorts++;
            }
        }
        if (hashMaps != 1 || hashConstructors != 1 || stateCalls != 2 || validCalls != 1
            || contains != 1 || gets != 1 || puts != 2 || sizes != 1 || values != 1 || sorts != 1) {
            throw new IllegalStateException("Lycanites 刷怪扫描调用图变化：map=" + hashMaps + '/'
                + hashConstructors + ", states=" + stateCalls + ", valid=" + validCalls
                + ", contains/get/put=" + contains + '/' + gets + '/' + puts
                + ", size/values/sort=" + sizes + '/' + values + '/' + sorts);
        }
    }

    private static void validateValid(MethodNode method) {
        int states = 0;
        int contains = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
             instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && STATE_OWNER.equals(call.owner)
                && STATE_METHOD.equals(call.name) && STATE_DESCRIPTOR.equals(call.desc)) states++;
            if (call.getOpcode() == Opcodes.INVOKEINTERFACE && "java/util/List".equals(call.owner)
                && "contains".equals(call.name) && "(Ljava/lang/Object;)Z".equals(call.desc)) contains++;
        }
        if (states != 1 || contains != 2) {
            throw new IllegalStateException("Lycanites isValidBlock 调用图变化：state="
                + states + ", contains=" + contains);
        }
    }

    private static void replaceCounterAllocation(MethodNode method) {
        TypeInsnNode allocation = null;
        MethodInsnNode constructor = null;
        AbstractInsnNode duplicate = null;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
             instruction = instruction.getNext()) {
            if (!(instruction instanceof TypeInsnNode) || instruction.getOpcode() != Opcodes.NEW
                || !"java/util/HashMap".equals(((TypeInsnNode) instruction).desc)) continue;
            allocation = (TypeInsnNode) instruction;
            duplicate = allocation.getNext();
            AbstractInsnNode next = duplicate == null ? null : duplicate.getNext();
            if (duplicate == null || duplicate.getOpcode() != Opcodes.DUP
                || !(next instanceof MethodInsnNode)) break;
            MethodInsnNode call = (MethodInsnNode) next;
            if (call.getOpcode() == Opcodes.INVOKESPECIAL && "java/util/HashMap".equals(call.owner)
                && "<init>".equals(call.name) && "()V".equals(call.desc)) constructor = call;
            break;
        }
        if (allocation == null || duplicate == null || constructor == null) {
            throw new IllegalStateException("Lycanites HashMap 分配指令结构变化");
        }
        InsnList replacement = new InsnList();
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 0));
        replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
            "newBlockCounter", "(Ljava/lang/Object;)Ljava/util/Map;", false));
        method.instructions.insertBefore(allocation, replacement);
        method.instructions.remove(allocation);
        method.instructions.remove(duplicate);
        method.instructions.remove(constructor);
    }

    private static void replaceStateCall(MethodNode method, int selectedIndex, String bridgeMethod) {
        int index = 0;
        MethodInsnNode selected = null;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
             instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && STATE_OWNER.equals(call.owner)
                && STATE_METHOD.equals(call.name) && STATE_DESCRIPTOR.equals(call.desc)) {
                if (index++ == selectedIndex) {
                    selected = call;
                    break;
                }
            }
        }
        if (selected == null) throw new IllegalStateException("Lycanites 方块状态调用点缺失：" + bridgeMethod);
        selected.setOpcode(Opcodes.INVOKESTATIC);
        selected.owner = BRIDGE;
        selected.name = bridgeMethod;
        selected.desc = "(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;)"
            + "Lnet/minecraft/block/state/IBlockState;";
        selected.itf = false;
    }

    private static MethodNode scanWrapper(int access, String signature, String[] exceptions) {
        MethodNode wrapper = new MethodNode(Opcodes.ASM5, access, SCAN_METHOD,
            SCAN_DESCRIPTOR, signature, exceptions);
        InsnList code = wrapper.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "begin",
            "(L" + ACCESSOR + ";)J", false));
        code.add(new VarInsnNode(Opcodes.LSTORE, 4));
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        code.add(start);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, TARGET,
            BACKUP_METHOD, SCAN_DESCRIPTOR, false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 6));
        code.add(end);
        code.add(new VarInsnNode(Opcodes.LLOAD, 4));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "end", "(J)V", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 6));
        code.add(new InsnNode(Opcodes.ARETURN));
        code.add(handler);
        code.add(new VarInsnNode(Opcodes.ASTORE, 7));
        code.add(new VarInsnNode(Opcodes.LLOAD, 4));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "end", "(J)V", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 7));
        code.add(new InsnNode(Opcodes.ATHROW));
        wrapper.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
        return wrapper;
    }

    private static void addFieldAccessor(ClassNode node, String methodName, String methodDescriptor,
                                         String fieldName, String fieldDescriptor, int returnOpcode) {
        MethodNode method = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            methodName, methodDescriptor, null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET, fieldName, fieldDescriptor));
        method.instructions.add(new InsnNode(returnOpcode));
        node.methods.add(method);
    }

    private static MethodNode requireMethod(ClassNode node, String name, String descriptor) {
        MethodNode result = null;
        int count = 0;
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                result = method;
                count++;
            }
        }
        if (count != 1) throw new IllegalStateException("Lycanites 方法数量变化："
            + name + descriptor + "=" + count);
        return result;
    }

    private static void rejectMethod(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                throw new IllegalStateException("Lycanites 已存在 ICE 备份方法 " + name);
            }
        }
    }

    private static void requireField(ClassNode node, String name, String descriptor) {
        int count = 0;
        for (FieldNode field : node.fields) {
            if (name.equals(field.name) && descriptor.equals(field.desc)) count++;
        }
        if (count != 1) throw new IllegalStateException("Lycanites 字段数量变化："
            + name + descriptor + "=" + count);
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
