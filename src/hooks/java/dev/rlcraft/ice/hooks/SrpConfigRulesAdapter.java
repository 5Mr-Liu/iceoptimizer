package dev.rlcraft.ice.hooks;

import org.objectweb.asm.*;

/** Reset compiled rules at their real configuration publication points, never by polling. */
final class SrpConfigRulesAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "com/dhanantry/scapeandrunparasites/util/config/SRPConfig";

    @Override public byte[] transform(String name, byte[] bytes, TargetSpec spec) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        final int[] writes = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
            @Override public MethodVisitor visitMethod(int access, String method, String desc,
                    String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5, super.visitMethod(access, method, desc, signature, exceptions)) {
                    @Override public void visitFieldInsn(int opcode, String owner, String field, String type) {
                        super.visitFieldInsn(opcode, owner, field, type);
                        if (opcode != Opcodes.PUTSTATIC || !TARGET.equals(owner) || !"[Ljava/lang/String;".equals(type)) return;
                        String bridge = null;
                        if (field.equals("parasiteWeakToItems") || field.equals("parasiteWeakToMobs")
                            || field.equals("parasiteWeakToElse")) bridge = "SrpWeaknessBridge";
                        if (field.equals("blockBList") || field.equals("blockBBiomeList")) bridge = "SrpInfestationCacheBridge";
                        if (bridge != null) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                "dev/rlcraft/ice/optimizer/compat/srp/" + bridge, "invalidate", "()V", false);
                            writes[0]++;
                        }
                    }
                };
            }
        }, 0);
        if (writes[0] < 5) throw new IllegalStateException("SRP config rule assignments changed");
        return writer.toByteArray();
    }
}
