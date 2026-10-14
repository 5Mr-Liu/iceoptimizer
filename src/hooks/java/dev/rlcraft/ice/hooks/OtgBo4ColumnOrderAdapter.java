package dev.rlcraft.ice.hooks;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Changes only the local stable column grouping in OTG 9.7 readResources. */
final class OtgBo4ColumnOrderAdapter implements OptimizerBytecodeAdapter {
    static final String BLOCK = "com/pg85/otg/customobjects/bo4/bo4function/BO4BlockFunction";
    static final String ACCESS = "dev/rlcraft/ice/optimizer/compat/otg/OtgBo4ColumnAccess";
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/otg/OtgBo4ColumnOrder";

    @Override public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, 0);
        MethodNode resources = null;
        for (MethodNode method : node.methods)
            if (method.name.equals("readResources") && method.desc.equals("()V")) resources = method;
        if (resources == null) throw new IllegalStateException("Missing BO4 readResources");
        AbstractInsnNode start = null;
        LabelNode end = null;
        int arrays = 0;
        for (AbstractInsnNode instruction : resources.instructions.toArray()) {
            if (instruction instanceof TypeInsnNode && instruction.getOpcode() == Opcodes.ANEWARRAY
                && BLOCK.equals(((TypeInsnNode) instruction).desc)) {
                AbstractInsnNode store = next(instruction);
                if (!(store instanceof VarInsnNode) || store.getOpcode() != Opcodes.ASTORE
                    || ((VarInsnNode) store).var != 19) continue;
                arrays++;
                start = store.getNext();
                for (AbstractInsnNode p = start; p != null; p = p.getNext()) {
                    if (p instanceof JumpInsnNode && p.getOpcode() == Opcodes.IF_ICMPGE) {
                        // The first conditional is the outer x < 16 loop.
                        end = ((JumpInsnNode) p).label;
                        break;
                    }
                }
            }
        }
        if (arrays != 1 || start == null || end == null)
            throw new IllegalStateException("Changed BO4 stable grouping layout");
        // Keep the original grouping as the F6/config-disabled branch.
        LabelNode original = new LabelNode();
        InsnList dispatch = new InsnList();
        dispatch.add(new VarInsnNode(Opcodes.ALOAD, 0));
        dispatch.add(new VarInsnNode(Opcodes.ALOAD, 1));
        dispatch.add(new VarInsnNode(Opcodes.ALOAD, 19));
        dispatch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "order",
            "(L" + ACCESS + ";Ljava/util/List;[Ljava/lang/Object;)Z", false));
        dispatch.add(new JumpInsnNode(Opcodes.IFEQ, original));
        dispatch.add(new JumpInsnNode(Opcodes.GOTO, end));
        dispatch.add(original);
        resources.instructions.insertBefore(start, dispatch);
        final MethodNode editedResources = resources;
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String left, String right) {
                return "java/lang/Object";
            }
        };
        reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
            @Override public void visit(int version, int access, String name, String signature,
                                        String superName, String[] interfaces) {
                String[] updated = java.util.Arrays.copyOf(interfaces, interfaces.length + 1);
                updated[interfaces.length] = ACCESS;
                super.visit(version, access, name, signature, superName, updated);
            }
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                       String signature, String[] exceptions) {
                return name.equals("readResources") && descriptor.equals("()V") ? null
                    : super.visitMethod(access, name, descriptor, signature, exceptions);
            }
            @Override public void visitEnd() {
                editedResources.accept(cv);
                columnAccess().accept(cv);
                super.visitEnd();
            }
        }, 0);
        return writer.toByteArray();
    }

    private static AbstractInsnNode next(AbstractInsnNode instruction) {
        do { instruction = instruction.getNext(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    private static MethodNode columnAccess() {
        MethodNode m = new MethodNode(Opcodes.ASM5, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            "ice$column", "(Ljava/lang/Object;)I", null, null);
        Label invalid = new Label();
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitTypeInsn(Opcodes.CHECKCAST, BLOCK);
        m.visitVarInsn(Opcodes.ASTORE, 2);
        for (String field : new String[] { "x", "z" }) {
            m.visitVarInsn(Opcodes.ALOAD, 2);
            m.visitFieldInsn(Opcodes.GETFIELD, BLOCK, field, "I");
            m.visitJumpInsn(Opcodes.IFLT, invalid);
            m.visitVarInsn(Opcodes.ALOAD, 2);
            m.visitFieldInsn(Opcodes.GETFIELD, BLOCK, field, "I");
            m.visitIntInsn(Opcodes.BIPUSH, 16);
            m.visitJumpInsn(Opcodes.IF_ICMPGE, invalid);
        }
        m.visitVarInsn(Opcodes.ALOAD, 2);
        m.visitFieldInsn(Opcodes.GETFIELD, BLOCK, "x", "I");
        m.visitInsn(Opcodes.ICONST_4);
        m.visitInsn(Opcodes.ISHL);
        m.visitVarInsn(Opcodes.ALOAD, 2);
        m.visitFieldInsn(Opcodes.GETFIELD, BLOCK, "z", "I");
        m.visitInsn(Opcodes.IOR);
        m.visitInsn(Opcodes.IRETURN);
        m.visitLabel(invalid);
        m.visitInsn(Opcodes.ICONST_M1);
        m.visitInsn(Opcodes.IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        return m;
    }
}
