package dev.rlcraft.ice.hooks;

import java.util.Arrays;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Installs a search-local primitive cache around the reviewed Lycanites processor. */
final class LycanitesNodeProcessorAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "com/lycanitesmobs/core/entity/navigate/CreatureNodeProcessor";
    static final String ACCESSOR = "dev/rlcraft/ice/optimizer/compat/lycanites/LycanitesRawNodeAccessor";
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/lycanites/LycanitesPathingBridge";
    static final String INIT = "func_186315_a";
    static final String INIT_DESCRIPTOR = "(Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/entity/EntityLiving;)V";
    static final String DONE = "func_176163_a";
    static final String RAW = "getPathNodeTypeRaw";
    static final String RAW_DESCRIPTOR = "(Lnet/minecraft/world/IBlockAccess;III)Lnet/minecraft/pathfinding/PathNodeType;";
    static final String ACCESSOR_DESCRIPTOR = RAW_DESCRIPTOR;
    static final String BRIDGE_RAW_DESCRIPTOR = "(L" + ACCESSOR
        + ";Lnet/minecraft/world/IBlockAccess;III)Lnet/minecraft/pathfinding/PathNodeType;";
    static final String STATE_OWNER = "net/minecraft/world/IBlockAccess";
    static final String STATE_METHOD = "func_180495_p";
    static final String STATE_DESCRIPTOR = "(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/state/IBlockState;";
    static final String BRIDGE_STATE_DESCRIPTOR = "(Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/state/IBlockState;";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        final int[] initMethods = new int[1];
        final int[] superInitCalls = new int[1];
        final int[] doneMethods = new int[1];
        final int[] rawCalls = new int[1];
        final int[] stateCalls = new int[1];
        final int[] existingAccessor = new int[1];
        final String[] observedName = new String[1];
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM5, writer) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                observedName[0] = name;
                String[] expanded = Arrays.copyOf(interfaces, interfaces.length + 1);
                expanded[interfaces.length] = ACCESSOR;
                super.visit(version, access, name, signature, superName, expanded);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor parent = super.visitMethod(access, name, descriptor, signature, exceptions);
                if ("ice$rawNodeType".equals(name) && ACCESSOR_DESCRIPTOR.equals(descriptor)) existingAccessor[0]++;
                final boolean init = INIT.equals(name) && INIT_DESCRIPTOR.equals(descriptor);
                final boolean done = DONE.equals(name) && "()V".equals(descriptor);
                if (init) initMethods[0]++;
                if (done) doneMethods[0]++;
                return new MethodVisitor(Opcodes.ASM5, parent) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        if (done) {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "end", "(L" + ACCESSOR + ";)V", false);
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String descriptor, boolean itf) {
                        if (init && opcode == Opcodes.INVOKESPECIAL
                            && "net/minecraft/pathfinding/NodeProcessor".equals(owner)
                            && INIT.equals(name) && INIT_DESCRIPTOR.equals(descriptor)) {
                            superInitCalls[0]++;
                            super.visitMethodInsn(opcode, owner, name, descriptor, itf);
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "begin",
                                "(L" + ACCESSOR + ";Lnet/minecraft/world/IBlockAccess;)V", false);
                            return;
                        }
                        if (opcode == Opcodes.INVOKEVIRTUAL && TARGET.equals(owner)
                            && RAW.equals(name) && RAW_DESCRIPTOR.equals(descriptor)) {
                            rawCalls[0]++;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "rawNodeType", BRIDGE_RAW_DESCRIPTOR, false);
                            return;
                        }
                        if (opcode == Opcodes.INVOKEINTERFACE && STATE_OWNER.equals(owner)
                            && STATE_METHOD.equals(name) && STATE_DESCRIPTOR.equals(descriptor)) {
                            stateCalls[0]++;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "blockState", BRIDGE_STATE_DESCRIPTOR, false);
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, name, descriptor, itf);
                    }
                };
            }

            @Override
            public void visitEnd() {
                MethodVisitor method = super.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                    "ice$rawNodeType", ACCESSOR_DESCRIPTOR, null, null);
                method.visitCode();
                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitVarInsn(Opcodes.ALOAD, 1);
                method.visitVarInsn(Opcodes.ILOAD, 2);
                method.visitVarInsn(Opcodes.ILOAD, 3);
                method.visitVarInsn(Opcodes.ILOAD, 4);
                method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TARGET, RAW, RAW_DESCRIPTOR, false);
                method.visitInsn(Opcodes.ARETURN);
                method.visitMaxs(0, 0);
                method.visitEnd();
                super.visitEnd();
            }
        };
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(observedName[0]) || initMethods[0] != 1 || superInitCalls[0] != 1
            || doneMethods[0] != 1 || rawCalls[0] != 2 || stateCalls[0] != 14 || existingAccessor[0] != 0) {
            throw new IllegalStateException("Lycanites 寻路调用图变化：class=" + observedName[0]
                + ", init=" + initMethods[0] + '/' + superInitCalls[0] + ", done=" + doneMethods[0]
                + ", rawCalls=" + rawCalls[0] + ", blockStateCalls=" + stateCalls[0]
                + ", existingAccessor=" + existingAccessor[0]);
        }
        return writer.toByteArray();
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String left, String right) { return "java/lang/Object"; }
    }
}
