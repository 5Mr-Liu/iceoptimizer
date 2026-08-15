package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Exact adapter for Xaero World Map 1.44.2's synchronous upload benchmark. */
final class XaeroTextureUploadAdapter implements OptimizerBytecodeAdapter {
    static final String BENCHMARK_OWNER = "xaero/map/graphics/TextureUploadBenchmark";
    static final String BRIDGE_OWNER = "dev/rlcraft/ice/optimizer/compat/xaero/XaeroGpuTimerBridge";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        final int[] finishedCalls = new int[1];
        final int[] averageCalls = new int[1];
        final int[] beforeCalls = new int[1];
        final int[] beginCalls = new int[1];
        final int[] endCalls = new int[1];
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM5, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String methodName, String descriptor, String signature, String[] exceptions) {
                MethodVisitor parent = super.visitMethod(access, methodName, descriptor, signature, exceptions);
                final boolean uploadLoop = "uploadTextures".equals(methodName) && "()V".equals(descriptor);
                return new MethodVisitor(Opcodes.ASM5, parent) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
                        if (opcode == Opcodes.INVOKEVIRTUAL && BENCHMARK_OWNER.equals(owner)
                            && "isFinished".equals(name) && "(I)Z".equals(descriptor)) {
                            finishedCalls[0]++;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE_OWNER, "isFinished", "(Ljava/lang/Object;I)Z", false);
                            return;
                        }
                        if (opcode == Opcodes.INVOKEVIRTUAL && BENCHMARK_OWNER.equals(owner)
                            && "getAverage".equals(name) && "(I)J".equals(descriptor)) {
                            averageCalls[0]++;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE_OWNER, "getAverage", "(Ljava/lang/Object;I)J", false);
                            return;
                        }
                        if (uploadLoop && opcode == Opcodes.INVOKESTATIC && "org/lwjgl/opengl/GL11".equals(owner)
                            && "glFinish".equals(name) && "()V".equals(descriptor)) {
                            beforeCalls[0]++;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE_OWNER, "beforeBatch", "()V", false);
                            return;
                        }
                        if (uploadLoop && opcode == Opcodes.INVOKEVIRTUAL && BENCHMARK_OWNER.equals(owner)
                            && "pre".equals(name) && "()V".equals(descriptor)) {
                            beginCalls[0]++;
                            super.visitVarInsn(Opcodes.ILOAD, 4);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE_OWNER, "begin", "(Ljava/lang/Object;I)V", false);
                            return;
                        }
                        if (uploadLoop && opcode == Opcodes.INVOKEVIRTUAL && BENCHMARK_OWNER.equals(owner)
                            && "post".equals(name) && "(I)V".equals(descriptor)) {
                            endCalls[0]++;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE_OWNER, "end", "(Ljava/lang/Object;I)V", false);
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, name, descriptor, itf);
                    }
                };
            }
        };
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        if (finishedCalls[0] != 8 || averageCalls[0] != 6 || beforeCalls[0] != 1
            || beginCalls[0] != 1 || endCalls[0] != 1) {
            throw new IllegalStateException("Xaero 上传签名漂移：isFinished=" + finishedCalls[0]
                + ", getAverage=" + averageCalls[0] + ", glFinish=" + beforeCalls[0]
                + ", pre=" + beginCalls[0] + ", post=" + endCalls[0]);
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
