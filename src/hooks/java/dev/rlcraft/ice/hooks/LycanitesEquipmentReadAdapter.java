package dev.rlcraft.ice.hooks;

import java.util.LinkedHashMap;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableAnnotationNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Shares decoded equipment parts only within one attribute or tooltip query. */
final class LycanitesEquipmentReadAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "com/lycanitesmobs/core/item/equipment/ItemEquipment";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/lycanites/LycanitesEquipmentReadBridge";
    static final String VIEW = BRIDGE + "$View";
    static final String VIEW_DESCRIPTOR = "L" + VIEW + ";";
    static final String HELPER_PREFIX = "rlcraftIce$read$";
    static final String PARTS_HELPER = "rlcraftIce$readParts";
    static final String STACK = "Lnet/minecraft/item/ItemStack;";
    static final String PARTS = "Lnet/minecraft/util/NonNullList;";
    static final String PARTS_DESCRIPTOR = "(" + STACK + ")" + PARTS;
    static final String ATTRIBUTE_DESCRIPTOR =
        "(Lnet/minecraft/inventory/EntityEquipmentSlot;" + STACK
            + ")Lcom/google/common/collect/Multimap;";
    static final String TOOLTIP_DESCRIPTOR =
        "(" + STACK + "Lnet/minecraft/world/World;Lnet/minecraft/client/util/ITooltipFlag;)Ljava/util/List;";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(node.name)) {
            throw new IllegalStateException("Lycanites equipment class changed: " + node.name);
        }

        Map<String, String> descriptors = readMethods();
        Map<String, MethodNode> originals = new LinkedHashMap<String, MethodNode>();
        for (MethodNode method : node.methods) {
            if (method.name.startsWith(HELPER_PREFIX) || PARTS_HELPER.equals(method.name)) {
                throw new IllegalStateException("Lycanites equipment read helpers already exist");
            }
            if (method.desc.equals(descriptors.get(method.name))) {
                originals.put(method.name, method);
            }
        }
        if (originals.size() != descriptors.size()) {
            throw new IllegalStateException("Lycanites equipment read methods changed: " + originals.keySet());
        }

        // Clone before adding the entry branches, so the private graph contains
        // only the original business logic and the explicit view parameter.
        for (MethodNode original : originals.values()) {
            node.methods.add(copyWithView(original, descriptors));
        }
        node.methods.add(partsResolver());
        addEntry(originals.get("getAttributeModifiers"), true);
        addEntry(originals.get("getAdditionalDescriptions"), false);

        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static Map<String, String> readMethods() {
        Map<String, String> methods = new LinkedHashMap<String, String>();
        methods.put("getAdditionalDescriptions", TOOLTIP_DESCRIPTOR);
        methods.put("getAttributeModifiers", ATTRIBUTE_DESCRIPTOR);
        methods.put("getFeatureSummaries", "(" + STACK + "Ljava/lang/String;)Ljava/lang/String;");
        methods.put("getFeaturesByType", "(" + STACK + "Ljava/lang/String;)Ljava/util/List;");
        methods.put("getFeaturesByTypeWithPartStack", "(" + STACK + "Ljava/lang/String;)Ljava/util/Map;");
        methods.put("getSharpness", "(" + STACK + ")I");
        methods.put("getMana", "(" + STACK + ")I");
        methods.put("getDamageAmount", "(" + STACK + ")D");
        methods.put("getDamageCooldown", "(" + STACK + ")D");
        methods.put("getDamageKnockback", "(" + STACK + ")D");
        methods.put("getDamageRange", "(" + STACK + ")D");
        methods.put("getDamageSweep", "(" + STACK + ")D");
        return methods;
    }

    private static MethodNode copyWithView(MethodNode original, Map<String, String> descriptors) {
        MethodNode copy = new MethodNode(Opcodes.ASM5,
            (original.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
                | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
            HELPER_PREFIX + original.name, withView(original.desc), null,
            original.exceptions == null ? null
                : original.exceptions.toArray(new String[original.exceptions.size()]));
        original.accept(copy);
        copy.signature = null;
        copy.parameters = null;
        copy.visibleParameterAnnotations = null;
        copy.invisibleParameterAnnotations = null;

        int viewLocal = argumentEnd(original.desc);
        for (AbstractInsnNode instruction : copy.instructions.toArray()) {
            if (instruction instanceof VarInsnNode) {
                VarInsnNode variable = (VarInsnNode) instruction;
                if (variable.var >= viewLocal) variable.var++;
            } else if (instruction instanceof IincInsnNode) {
                IincInsnNode increment = (IincInsnNode) instruction;
                if (increment.var >= viewLocal) increment.var++;
            } else if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.getOpcode() != Opcodes.INVOKEVIRTUAL || !TARGET.equals(call.owner)) continue;
                if ("getEquipmentPartStacks".equals(call.name) && PARTS_DESCRIPTOR.equals(call.desc)) {
                    copy.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, viewLocal));
                    call.setOpcode(Opcodes.INVOKESPECIAL);
                    call.name = PARTS_HELPER;
                    call.desc = withView(call.desc);
                } else if (call.desc.equals(descriptors.get(call.name))) {
                    copy.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, viewLocal));
                    call.setOpcode(Opcodes.INVOKESPECIAL);
                    call.name = HELPER_PREFIX + call.name;
                    call.desc = withView(call.desc);
                }
            }
        }
        if (copy.localVariables != null) {
            for (LocalVariableNode local : copy.localVariables) {
                if (local.index >= viewLocal) local.index++;
            }
        }
        shiftAnnotations(copy.visibleLocalVariableAnnotations, viewLocal);
        shiftAnnotations(copy.invisibleLocalVariableAnnotations, viewLocal);
        copy.maxLocals = original.maxLocals + 1;
        return copy;
    }

    private static void shiftAnnotations(java.util.List<LocalVariableAnnotationNode> annotations, int viewLocal) {
        if (annotations == null) return;
        for (LocalVariableAnnotationNode annotation : annotations) {
            for (int i = 0; i < annotation.index.size(); i++) {
                int index = annotation.index.get(i).intValue();
                if (index >= viewLocal) annotation.index.set(i, Integer.valueOf(index + 1));
            }
        }
    }

    private static MethodNode partsResolver() {
        MethodNode resolver = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
            PARTS_HELPER, withView(PARTS_DESCRIPTOR), null, null);
        MethodVisitor code = resolver;
        code.visitCode();
        code.visitVarInsn(Opcodes.ALOAD, 2);
        code.visitFieldInsn(Opcodes.GETFIELD, VIEW, "parts", PARTS);
        Label ready = new Label();
        code.visitJumpInsn(Opcodes.IFNONNULL, ready);
        code.visitVarInsn(Opcodes.ALOAD, 2);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TARGET,
            "getEquipmentPartStacks", PARTS_DESCRIPTOR, false);
        code.visitFieldInsn(Opcodes.PUTFIELD, VIEW, "parts", PARTS);
        code.visitLabel(ready);
        code.visitVarInsn(Opcodes.ALOAD, 2);
        code.visitFieldInsn(Opcodes.GETFIELD, VIEW, "parts", PARTS);
        code.visitInsn(Opcodes.ARETURN);
        code.visitMaxs(0, 0);
        code.visitEnd();
        return resolver;
    }

    private static void addEntry(MethodNode original, boolean attributes) {
        MethodNode entry = new MethodNode(Opcodes.ASM5);
        MethodVisitor code = entry;
        LabelNode fallback = new LabelNode();
        code.visitCode();
        // A subclass can override the original read methods. Keep that virtual
        // dispatch intact instead of routing it through our private copies.
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
        code.visitLdcInsn(Type.getObjectType(TARGET));
        entry.instructions.add(new JumpInsnNode(Opcodes.IF_ACMPNE, fallback));
        if (attributes) {
            code.visitVarInsn(Opcodes.ALOAD, 1);
            code.visitFieldInsn(Opcodes.GETSTATIC,
                "net/minecraft/inventory/EntityEquipmentSlot", "MAINHAND",
                "Lnet/minecraft/inventory/EntityEquipmentSlot;");
            entry.instructions.add(new JumpInsnNode(Opcodes.IF_ACMPNE, fallback));
        }
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "useEquipmentReadView", "()Z", false);
        entry.instructions.add(new JumpInsnNode(Opcodes.IFEQ, fallback));
        code.visitVarInsn(Opcodes.ALOAD, 0);
        int local = 1;
        for (Type argument : Type.getArgumentTypes(original.desc)) {
            code.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), local);
            local += argument.getSize();
        }
        code.visitTypeInsn(Opcodes.NEW, VIEW);
        code.visitInsn(Opcodes.DUP);
        code.visitMethodInsn(Opcodes.INVOKESPECIAL, VIEW, "<init>", "()V", false);
        code.visitMethodInsn(Opcodes.INVOKESPECIAL, TARGET,
            HELPER_PREFIX + original.name, withView(original.desc), false);
        code.visitInsn(Type.getReturnType(original.desc).getOpcode(Opcodes.IRETURN));
        entry.instructions.add(fallback);
        InsnList instructions = entry.instructions;
        original.instructions.insert(instructions);
    }

    private static int argumentEnd(String descriptor) {
        int end = 1;
        for (Type argument : Type.getArgumentTypes(descriptor)) end += argument.getSize();
        return end;
    }

    private static String withView(String descriptor) {
        int end = descriptor.indexOf(')');
        return descriptor.substring(0, end) + VIEW_DESCRIPTOR + descriptor.substring(end);
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String left, String right) { return "java/lang/Object"; }
    }
}
