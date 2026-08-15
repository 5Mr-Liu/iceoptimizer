package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Adds an exact PathNavigateGround-compatible navigator to SRP's base parasite. */
final class SrpParasiteNavigatorAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "com/dhanantry/scapeandrunparasites/entity/ai/misc/EntityParasiteBase";
    static final String EXPECTED_SUPER = "net/minecraft/entity/monster/EntityMob";
    static final String METHOD = "func_175447_b";
    static final String DESCRIPTOR = "(Lnet/minecraft/world/World;)Lnet/minecraft/pathfinding/PathNavigate;";
    static final String NAVIGATOR = "dev/rlcraft/ice/optimizer/compat/srp/SrpPathNavigateGround";
    static final String CONSTRUCTOR = "(Lnet/minecraft/entity/EntityLiving;Lnet/minecraft/world/World;)V";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        final int[] existing = new int[1];
        final String[] observedName = new String[1];
        final String[] observedSuper = new String[1];
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM5, writer) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                observedName[0] = name;
                observedSuper[0] = superName;
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (METHOD.equals(name) && DESCRIPTOR.equals(descriptor)) existing[0]++;
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }

            @Override
            public void visitEnd() {
                MethodVisitor method = super.visitMethod(Opcodes.ACC_PROTECTED, METHOD, DESCRIPTOR, null, null);
                method.visitCode();
                method.visitTypeInsn(Opcodes.NEW, NAVIGATOR);
                method.visitInsn(Opcodes.DUP);
                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitVarInsn(Opcodes.ALOAD, 1);
                method.visitMethodInsn(Opcodes.INVOKESPECIAL, NAVIGATOR, "<init>", CONSTRUCTOR, false);
                method.visitInsn(Opcodes.ARETURN);
                method.visitMaxs(0, 0);
                method.visitEnd();
                super.visitEnd();
            }
        };
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(observedName[0]) || !EXPECTED_SUPER.equals(observedSuper[0]) || existing[0] != 0) {
            throw new IllegalStateException("SRP 导航调用图变化：class=" + observedName[0]
                + ", super=" + observedSuper[0] + ", existingFactory=" + existing[0]);
        }
        return writer.toByteArray();
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String left, String right) { return "java/lang/Object"; }
    }
}
