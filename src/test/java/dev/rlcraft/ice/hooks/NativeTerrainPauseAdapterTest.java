package dev.rlcraft.ice.hooks;

import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.Assert.*;

public class NativeTerrainPauseAdapterTest {
    @Test public void repeatedSwitchesExecuteOnlyTheSelectedBodyWithWideArguments() throws Exception {
        ClassNode node = new ClassNode(Opcodes.ASM5);
        node.version = Opcodes.V1_8;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = "ice/test/PauseTarget";
        node.superName = "java/lang/Object";
        MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        node.methods.add(constructor);
        MethodNode optimized = new MethodNode(Opcodes.ACC_PUBLIC, "calculate", "(JD)J", null, null);
        optimized.visitVarInsn(Opcodes.LLOAD, 1);
        optimized.visitVarInsn(Opcodes.DLOAD, 3);
        optimized.visitInsn(Opcodes.D2L);
        optimized.visitInsn(Opcodes.LADD);
        optimized.visitInsn(Opcodes.LRETURN);
        node.methods.add(optimized);
        MethodNode baseline = NativeTerrainPauseAdapter.copy(optimized);
        InsnList extra = new InsnList();
        extra.add(new LdcInsnNode(100L));
        extra.add(new InsnNode(Opcodes.LADD));
        optimized.instructions.insertBefore(optimized.instructions.getLast(), extra);
        NativeTerrainPauseAdapter.install(node, optimized, baseline);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        final byte[] bytes = writer.toByteArray();
        Class<?> type = new ClassLoader(getClass().getClassLoader()) {
            Class<?> loadTarget() { return defineClass("ice.test.PauseTarget", bytes, 0, bytes.length); }
        }.loadTarget();
        Object target = type.newInstance();
        try {
            for (int i = 0; i < 8; i++) {
                boolean paused = (i & 1) != 0;
                OptimizerRegistry.setRuntimePaused(paused);
                assertEquals(paused ? 12L : 112L,
                    type.getMethod("calculate", long.class, double.class).invoke(target, 7L, 5.5));
            }
        } finally { OptimizerRegistry.setRuntimePaused(false); }
    }
}
