package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class IceAndFireNodeProcessorAdapterTest {
    private static final String CLASS_NAME =
        "com.github.alexthe666.iceandfire.entity.ai.ExperimentalWalkNodeProcessor";
    private static final String CLASS_SHA =
        "69a6de4249a0af9fb59ba91cd8644e45d5bdd93697a3827f278f33eea6f6f076";

    @Test
    public void installsLifecycleAccessorAndEveryReviewedCallSite() {
        byte[] transformed = adapter().transform(CLASS_NAME, syntheticProcessor(12), target());
        assertTrue(hasInterface(transformed, IceAndFireNodeProcessorAdapter.ACCESSOR));
        assertEquals(1, countMethods(transformed, "ice$rawNodeType",
            IceAndFireNodeProcessorAdapter.ACCESSOR_DESCRIPTOR));
        assertEquals(1, countCalls(transformed, Opcodes.INVOKESTATIC,
            IceAndFireNodeProcessorAdapter.BRIDGE, "begin",
            "(L" + IceAndFireNodeProcessorAdapter.ACCESSOR
                + ";Lnet/minecraft/world/IBlockAccess;)V"));
        assertEquals(1, countCalls(transformed, Opcodes.INVOKESTATIC,
            IceAndFireNodeProcessorAdapter.BRIDGE, "end",
            "(L" + IceAndFireNodeProcessorAdapter.ACCESSOR + ";)V"));
        assertEquals(2, countCalls(transformed, Opcodes.INVOKESTATIC,
            IceAndFireNodeProcessorAdapter.BRIDGE, "rawNodeType",
            IceAndFireNodeProcessorAdapter.BRIDGE_RAW_DESCRIPTOR));
        assertEquals(12, countCalls(transformed, Opcodes.INVOKESTATIC,
            IceAndFireNodeProcessorAdapter.BRIDGE, "blockState",
            IceAndFireNodeProcessorAdapter.BRIDGE_STATE_DESCRIPTOR));
        new ClassReader(transformed);
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsAChangedBlockStateCallGraph() {
        adapter().transform(CLASS_NAME, syntheticProcessor(11), target());
    }

    @Test
    public void transformsTheReviewedDregora209ClassWhenConfigured() throws Exception {
        String configured = System.getProperty("ice.iceandfire.jar", "").trim();
        Assume.assumeTrue("run with -PiceAndFireJar=<Ice and Fire-2.0.9.jar>",
            !configured.isEmpty());
        File file = new File(configured);
        Assume.assumeTrue(file.isFile());
        JarFile jar = new JarFile(file);
        try {
            byte[] original = read(jar, CLASS_NAME);
            assertEquals(CLASS_SHA, CoreClassFingerprint.sha256(original));
            byte[] transformed = new IceClientOptimizerTransformer().transform(
                CLASS_NAME, CLASS_NAME, original);
            assertFalse(Arrays.equals(original, transformed));
            assertTrue(hasInterface(transformed, IceAndFireNodeProcessorAdapter.ACCESSOR));
            assertEquals(2, countCalls(transformed, Opcodes.INVOKESTATIC,
                IceAndFireNodeProcessorAdapter.BRIDGE, "rawNodeType",
                IceAndFireNodeProcessorAdapter.BRIDGE_RAW_DESCRIPTOR));
            assertEquals(12, countCalls(transformed, Opcodes.INVOKESTATIC,
                IceAndFireNodeProcessorAdapter.BRIDGE, "blockState",
                IceAndFireNodeProcessorAdapter.BRIDGE_STATE_DESCRIPTOR));
            new ClassReader(transformed);
        } finally {
            jar.close();
        }
    }

    private static IceAndFireNodeProcessorAdapter adapter() {
        return new IceAndFireNodeProcessorAdapter();
    }

    private static TargetSpec target() {
        return new TargetSpec(CLASS_NAME, "iceandfire-path-node-cache",
            "iceandfire-path-search-cache", Collections.singleton(CLASS_SHA));
    }

    private static byte[] syntheticProcessor(int totalStateCalls) {
        String target = IceAndFireNodeProcessorAdapter.TARGET;
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
            target, null, "net/minecraft/pathfinding/NodeProcessor", null);

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
            "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,
            "net/minecraft/pathfinding/NodeProcessor", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();

        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC,
            IceAndFireNodeProcessorAdapter.INIT,
            IceAndFireNodeProcessorAdapter.INIT_DESCRIPTOR, null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitVarInsn(Opcodes.ALOAD, 2);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL,
            "net/minecraft/pathfinding/NodeProcessor",
            IceAndFireNodeProcessorAdapter.INIT,
            IceAndFireNodeProcessorAdapter.INIT_DESCRIPTOR, false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(3, 3);
        init.visitEnd();

        MethodVisitor done = writer.visitMethod(Opcodes.ACC_PUBLIC,
            IceAndFireNodeProcessorAdapter.DONE, "()V", null, null);
        done.visitCode();
        done.visitVarInsn(Opcodes.ALOAD, 0);
        done.visitMethodInsn(Opcodes.INVOKESPECIAL,
            "net/minecraft/pathfinding/NodeProcessor",
            IceAndFireNodeProcessorAdapter.DONE, "()V", false);
        done.visitInsn(Opcodes.RETURN);
        done.visitMaxs(1, 1);
        done.visitEnd();

        MethodVisitor raw = writer.visitMethod(Opcodes.ACC_PROTECTED,
            IceAndFireNodeProcessorAdapter.RAW,
            IceAndFireNodeProcessorAdapter.RAW_DESCRIPTOR, null, null);
        raw.visitCode();
        raw.visitVarInsn(Opcodes.ALOAD, 1);
        raw.visitTypeInsn(Opcodes.NEW, "net/minecraft/util/math/BlockPos");
        raw.visitInsn(Opcodes.DUP);
        raw.visitVarInsn(Opcodes.ILOAD, 2);
        raw.visitVarInsn(Opcodes.ILOAD, 3);
        raw.visitVarInsn(Opcodes.ILOAD, 4);
        raw.visitMethodInsn(Opcodes.INVOKESPECIAL,
            "net/minecraft/util/math/BlockPos", "<init>", "(III)V", false);
        raw.visitMethodInsn(Opcodes.INVOKEINTERFACE,
            IceAndFireNodeProcessorAdapter.STATE_OWNER,
            IceAndFireNodeProcessorAdapter.STATE_METHOD,
            IceAndFireNodeProcessorAdapter.STATE_DESCRIPTOR, true);
        raw.visitInsn(Opcodes.POP);
        raw.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/pathfinding/PathNodeType",
            "OPEN", "Lnet/minecraft/pathfinding/PathNodeType;");
        raw.visitInsn(Opcodes.ARETURN);
        raw.visitMaxs(6, 5);
        raw.visitEnd();

        MethodVisitor probe = writer.visitMethod(Opcodes.ACC_PUBLIC, "probe",
            "(Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/util/math/BlockPos;)V",
            null, null);
        probe.visitCode();
        for (int i = 1; i < totalStateCalls; i++) stateCall(probe);
        for (int i = 0; i < 2; i++) {
            probe.visitVarInsn(Opcodes.ALOAD, 0);
            probe.visitVarInsn(Opcodes.ALOAD, 1);
            probe.visitInsn(Opcodes.ICONST_1);
            probe.visitInsn(Opcodes.ICONST_2);
            probe.visitInsn(Opcodes.ICONST_3);
            probe.visitMethodInsn(Opcodes.INVOKEVIRTUAL, target,
                IceAndFireNodeProcessorAdapter.RAW,
                IceAndFireNodeProcessorAdapter.RAW_DESCRIPTOR, false);
            probe.visitInsn(Opcodes.POP);
        }
        probe.visitInsn(Opcodes.RETURN);
        probe.visitMaxs(5, 3);
        probe.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void stateCall(MethodVisitor method) {
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitVarInsn(Opcodes.ALOAD, 2);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE,
            IceAndFireNodeProcessorAdapter.STATE_OWNER,
            IceAndFireNodeProcessorAdapter.STATE_METHOD,
            IceAndFireNodeProcessorAdapter.STATE_DESCRIPTOR, true);
        method.visitInsn(Opcodes.POP);
    }

    private static byte[] read(JarFile jar, String className) throws Exception {
        JarEntry entry = jar.getJarEntry(className.replace('.', '/') + ".class");
        assertTrue(entry != null);
        InputStream input = jar.getInputStream(entry);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static boolean hasInterface(byte[] bytes, final String expected) {
        final boolean[] found = new boolean[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                for (String value : interfaces) {
                    if (expected.equals(value)) found[0] = true;
                }
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static int countMethods(byte[] bytes, final String name,
                                    final String descriptor) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override
            public MethodVisitor visitMethod(int access, String actualName,
                                             String actualDescriptor, String signature,
                                             String[] exceptions) {
                if (name.equals(actualName) && descriptor.equals(actualDescriptor)) count[0]++;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }

    private static int countCalls(byte[] bytes, final int opcode, final String owner,
                                  final String name, final String descriptor) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override
            public MethodVisitor visitMethod(int access, String methodName,
                                             String methodDescriptor, String signature,
                                             String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override
                    public void visitMethodInsn(int actualOpcode, String actualOwner,
                                                String actualName, String actualDescriptor,
                                                boolean itf) {
                        if (opcode == actualOpcode && owner.equals(actualOwner)
                            && name.equals(actualName) && descriptor.equals(actualDescriptor)) {
                            count[0]++;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG);
        return count[0];
    }
}
