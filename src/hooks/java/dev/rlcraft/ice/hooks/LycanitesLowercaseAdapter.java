package dev.rlcraft.ice.hooks;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Routes reviewed per-frame lowercasing through a bounded Locale-aware cache. */
final class LycanitesLowercaseAdapter implements OptimizerBytecodeAdapter {
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/lycanites/LycanitesAnimationBridge";
    static final String DESCRIPTOR = "(Ljava/lang/String;)Ljava/lang/String;";
    private static final Map<String, Integer> EXPECTED = expected();

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        Integer expected = EXPECTED.get(transformedName);
        if (expected == null) throw new IllegalStateException("未审查的 Lycanites lowercase 目标：" + transformedName);
        ClassReader reader = new ClassReader(originalClass);
        ClassWriter writer = new ClassWriter(reader, 0);
        final int[] calls = new int[1];
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM5, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5,
                    super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDescriptor, boolean itf) {
                        if (opcode == Opcodes.INVOKEVIRTUAL && "java/lang/String".equals(owner)
                            && "toLowerCase".equals(methodName)
                            && "()Ljava/lang/String;".equals(methodDescriptor)) {
                            calls[0]++;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE,
                                "lower", DESCRIPTOR, false);
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, itf);
                    }
                };
            }
        };
        reader.accept(visitor, 0);
        if (calls[0] != expected.intValue()) {
            throw new IllegalStateException("Lycanites lowercase 调用图变化：class=" + transformedName
                + ", calls=" + calls[0] + '/' + expected);
        }
        return writer.toByteArray();
    }

    private static Map<String, Integer> expected() {
        Map<String, Integer> values = new HashMap<String, Integer>();
        values.put("com.lycanitesmobs.client.model.ModelCreatureObj", Integer.valueOf(4));
        values.put("com.lycanitesmobs.client.model.ModelItemBase", Integer.valueOf(3));
        values.put("com.lycanitesmobs.client.model.ModelObjOld", Integer.valueOf(3));
        return Collections.unmodifiableMap(values);
    }
}
