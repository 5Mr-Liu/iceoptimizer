package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Replaces only OreLib OpenGlState's two private synchronous GL query helpers. */
final class OreLibOpenGlStateAdapter implements OptimizerBytecodeAdapter {
    static final String GL11_OWNER = "org/lwjgl/opengl/GL11";
    static final String BRIDGE_OWNER = "dev/rlcraft/ice/optimizer/compat/orelib/OreLibGlStateBridge";
    static final String INTEGER_DESCRIPTOR = "(I)I";
    static final String FLOAT_DESCRIPTOR = "(I)F";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassWriter writer = new ClassWriter(reader, 0);
        final int[] integerMethods = new int[1];
        final int[] floatMethods = new int[1];
        final int[] integerCalls = new int[1];
        final int[] floatCalls = new int[1];
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM5, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor parent = super.visitMethod(access, name, descriptor, signature, exceptions);
                final int kind;
                if ("getInteger".equals(name) && INTEGER_DESCRIPTOR.equals(descriptor)) {
                    integerMethods[0]++;
                    kind = 1;
                } else if ("getFloat".equals(name) && FLOAT_DESCRIPTOR.equals(descriptor)) {
                    floatMethods[0]++;
                    kind = 2;
                } else {
                    return parent;
                }
                int required = Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC;
                if ((access & required) != required) {
                    throw new IllegalStateException("OreLib OpenGlState 查询帮助方法不再是 private static");
                }
                return new MethodVisitor(Opcodes.ASM5, parent) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDescriptor, boolean itf) {
                        if (opcode == Opcodes.INVOKESTATIC && GL11_OWNER.equals(owner)) {
                            if (kind == 1 && "glGetInteger".equals(methodName)
                                && INTEGER_DESCRIPTOR.equals(methodDescriptor)) {
                                integerCalls[0]++;
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE_OWNER,
                                    "getInteger", INTEGER_DESCRIPTOR, false);
                                return;
                            }
                            if (kind == 2 && "glGetFloat".equals(methodName)
                                && FLOAT_DESCRIPTOR.equals(methodDescriptor)) {
                                floatCalls[0]++;
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE_OWNER,
                                    "getFloat", FLOAT_DESCRIPTOR, false);
                                return;
                            }
                        }
                        super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, itf);
                    }
                };
            }
        };
        reader.accept(visitor, 0);
        if (integerMethods[0] != 1 || floatMethods[0] != 1
            || integerCalls[0] != 1 || floatCalls[0] != 1) {
            throw new IllegalStateException("OreLib OpenGlState 调用图变化：getIntegerMethods="
                + integerMethods[0] + ", getFloatMethods=" + floatMethods[0]
                + ", glGetInteger=" + integerCalls[0] + ", glGetFloat=" + floatCalls[0]);
        }
        return writer.toByteArray();
    }
}
