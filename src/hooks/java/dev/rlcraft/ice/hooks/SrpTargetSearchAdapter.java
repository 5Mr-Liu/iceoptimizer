package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Replaces one reviewed full sort whose only observable result is list[0]. */
final class SrpTargetSearchAdapter implements OptimizerBytecodeAdapter {
    static final String METHOD = "func_75250_a";
    static final String METHOD_DESCRIPTOR = "()Z";
    static final String SORT_OWNER = "java/util/Collections";
    static final String SORT_METHOD = "sort";
    static final String SORT_DESCRIPTOR = "(Ljava/util/List;Ljava/util/Comparator;)V";
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/srp/SrpTargetSearchBridge";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        final int[] methods = new int[1];
        final int[] sorts = new int[1];
        final int[] listGets = new int[1];
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM5, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor parent = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!METHOD.equals(name) || !METHOD_DESCRIPTOR.equals(descriptor)) return parent;
                methods[0]++;
                return new MethodVisitor(Opcodes.ASM5, parent) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String descriptor, boolean itf) {
                        if (opcode == Opcodes.INVOKESTATIC && SORT_OWNER.equals(owner)
                            && SORT_METHOD.equals(name) && SORT_DESCRIPTOR.equals(descriptor)) {
                            sorts[0]++;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "selectFirst", SORT_DESCRIPTOR, false);
                            return;
                        }
                        if (opcode == Opcodes.INVOKEINTERFACE && "java/util/List".equals(owner)
                            && "get".equals(name) && "(I)Ljava/lang/Object;".equals(descriptor)) listGets[0]++;
                        super.visitMethodInsn(opcode, owner, name, descriptor, itf);
                    }
                };
            }
        };
        reader.accept(visitor, 0);
        if (methods[0] != 1 || sorts[0] != 1 || listGets[0] != 1) {
            throw new IllegalStateException("SRP 目标搜索调用图变化：methods=" + methods[0]
                + ", sorts=" + sorts[0] + ", listGets=" + listGets[0]);
        }
        return writer.toByteArray();
    }
}
