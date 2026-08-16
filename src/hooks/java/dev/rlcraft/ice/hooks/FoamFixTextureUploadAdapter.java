package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Installs an exact, fail-open entry guard around FoamFix's reviewed upload helper. */
final class FoamFixTextureUploadAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET_METHOD = "uploadTextureMaxMips";
    static final String TARGET_DESCRIPTOR = "(I[[IIIIIZZZ)V";
    static final String BRIDGE_OWNER = "dev/rlcraft/ice/hooks/TextureUploadBootstrap";
    static final String BRIDGE_METHOD = "tryUpload";
    static final String BRIDGE_DESCRIPTOR = "(I[[IIIIIZZZ)Z";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        final int[] matches = new int[1];
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM5, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor parent = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!TARGET_METHOD.equals(name) || !TARGET_DESCRIPTOR.equals(descriptor)) return parent;
                if ((access & Opcodes.ACC_STATIC) == 0) {
                    throw new IllegalStateException("FoamFix 目标上传方法不再是 static");
                }
                matches[0]++;
                return new MethodVisitor(Opcodes.ASM5, parent) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        visitVarInsn(Opcodes.ILOAD, 0);
                        visitVarInsn(Opcodes.ALOAD, 1);
                        visitVarInsn(Opcodes.ILOAD, 2);
                        visitVarInsn(Opcodes.ILOAD, 3);
                        visitVarInsn(Opcodes.ILOAD, 4);
                        visitVarInsn(Opcodes.ILOAD, 5);
                        visitVarInsn(Opcodes.ILOAD, 6);
                        visitVarInsn(Opcodes.ILOAD, 7);
                        visitVarInsn(Opcodes.ILOAD, 8);
                        visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE_OWNER, BRIDGE_METHOD, BRIDGE_DESCRIPTOR, false);
                        Label originalImplementation = new Label();
                        visitJumpInsn(Opcodes.IFEQ, originalImplementation);
                        visitInsn(Opcodes.RETURN);
                        visitLabel(originalImplementation);
                    }
                };
            }
        };
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        if (matches[0] != 1) {
            throw new IllegalStateException("FoamFix 精确上传方法匹配数量应为 1，实际 " + matches[0]);
        }
        return writer.toByteArray();
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) {
            super(reader, flags);
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            return "java/lang/Object";
        }
    }
}
