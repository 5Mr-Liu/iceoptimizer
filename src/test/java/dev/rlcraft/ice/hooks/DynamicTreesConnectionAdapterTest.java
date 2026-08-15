package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.rlcraft.ice.optimizer.ClientOptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import net.minecraftforge.common.property.IExtendedBlockState;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class DynamicTreesConnectionAdapterTest {
    private static final String SYNTHETIC_INTERNAL =
        "dev/rlcraft/ice/hooks/SyntheticDynamicTreesBranchModel";
    private static final String REAL_CLASS =
        "com.ferreusveritas.dynamictrees.models.bakedmodels.BakedModelBlockBranchBasic";
    private static final String REAL_ENTRY =
        "com/ferreusveritas/dynamictrees/models/bakedmodels/BakedModelBlockBranchBasic.class";
    private static final String REAL_CLASS_SHA256 =
        "aa9a36d1abb890a273005ef0086e578205803ae9cbbb0a9048770a625286a478";
    private static final String REAL_JAR_SHA256 =
        "82b779226d671ebbe305fca7d673a10fbc192508d7667273628b5c299e6fd83c";

    @Test
    public void memoizesOnlyTheSameModelRadiusAndExtendedStateOnTheCurrentThread()
        throws Exception {
        byte[] original = syntheticClass(false);
        TargetSpec target = targetFor(SYNTHETIC_INTERNAL.replace('/', '.'));
        byte[] transformed = new DynamicTreesConnectionAdapter().transform(
            target.className, original, target);
        assertFalse(Arrays.equals(original, transformed));
        assertEquals(1, countCalls(transformed, Opcodes.INVOKESTATIC,
            DynamicTreesConnectionAdapter.BRIDGE_OWNER, "lookup",
            DynamicTreesConnectionAdapter.LOOKUP_DESCRIPTOR));
        assertEquals(1, countCalls(transformed, Opcodes.INVOKESTATIC,
            DynamicTreesConnectionAdapter.BRIDGE_OWNER, "remember",
            DynamicTreesConnectionAdapter.REMEMBER_DESCRIPTOR));
        new ClassReader(transformed);

        boolean oldGlobal = OptimizerConfig.settings.enabled;
        boolean oldModule = OptimizerConfig.settings.dynamicTreesConnectionMemo;
        try {
            OptimizerConfig.settings.enabled = true;
            OptimizerConfig.settings.dynamicTreesConnectionMemo = true;
            OptimizerRegistry.configure(ClientOptimizerConfig.capture());
            OptimizerRegistry.targetObserved("chunk-mesh-dynamic-trees", target.className,
                repeat('b', 64), true);
            OptimizerRegistry.patchInstalled("chunk-mesh-dynamic-trees", target.className,
                repeat('b', 64));

            ByteLoader loader = new ByteLoader(getClass().getClassLoader());
            Class<?> type = loader.define(target.className, transformed);
            Object model = type.newInstance();
            Method poll = type.getDeclaredMethod("pollConnections", Integer.TYPE,
                IExtendedBlockState.class);
            poll.setAccessible(true);
            Field calls = type.getField("radiusCalls");

            int[] first = (int[]) poll.invoke(model, Integer.valueOf(4), null);
            int[] second = (int[]) poll.invoke(model, Integer.valueOf(4), null);
            assertSame(first, second);
            assertEquals(1, calls.getInt(model));
            int[] changedRadius = (int[]) poll.invoke(model, Integer.valueOf(5), null);
            assertNotSame(first, changedRadius);
            assertEquals(2, calls.getInt(model));

            OptimizerConfig.settings.dynamicTreesConnectionMemo = false;
            OptimizerRegistry.configure(ClientOptimizerConfig.capture());
            Object disabled = type.newInstance();
            int[] disabledFirst = (int[]) poll.invoke(disabled, Integer.valueOf(4), null);
            int[] disabledSecond = (int[]) poll.invoke(disabled, Integer.valueOf(4), null);
            assertNotSame(disabledFirst, disabledSecond);
            assertEquals(2, calls.getInt(disabled));
        } finally {
            OptimizerConfig.settings.enabled = oldGlobal;
            OptimizerConfig.settings.dynamicTreesConnectionMemo = oldModule;
            OptimizerRegistry.configure(ClientOptimizerConfig.capture());
        }
    }

    @Test
    public void rejectsAChangedConnectionCallGraph() {
        TargetSpec target = targetFor(SYNTHETIC_INTERNAL.replace('/', '.'));
        try {
            new DynamicTreesConnectionAdapter().transform(target.className,
                syntheticClass(true), target);
            fail("adapter must reject a duplicate connection-radius call");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("调用图变化"));
            assertTrue(expected.getMessage().contains("getConnectionRadius=2"));
        }
    }

    @Test
    public void transformsAndDefinesTheConfiguredRealDynamicTreesClassWhenAvailable()
        throws Exception {
        String configured = System.getProperty("ice.dynamictrees.jar", "").trim();
        Assume.assumeTrue("run with -PdynamicTreesJar=<jar> for the real-JAR integration test",
            !configured.isEmpty());
        File jarFile = new File(configured);
        Assume.assumeTrue("configured Dynamic Trees JAR must exist", jarFile.isFile());
        assertEquals("the configured JAR must be the reviewed Dynamic Trees binary",
            REAL_JAR_SHA256, sha256(jarFile));

        byte[] original = readEntry(jarFile, REAL_ENTRY);
        assertEquals(REAL_CLASS_SHA256, CoreClassFingerprint.sha256(original));
        byte[] transformed = new IceClientOptimizerTransformer().transform(
            REAL_CLASS, REAL_CLASS, original);
        assertFalse(Arrays.equals(original, transformed));
        assertEquals(1, countCalls(transformed, Opcodes.INVOKESTATIC,
            DynamicTreesConnectionAdapter.BRIDGE_OWNER, "lookup",
            DynamicTreesConnectionAdapter.LOOKUP_DESCRIPTOR));
        assertEquals(1, countCalls(transformed, Opcodes.INVOKESTATIC,
            DynamicTreesConnectionAdapter.BRIDGE_OWNER, "remember",
            DynamicTreesConnectionAdapter.REMEMBER_DESCRIPTOR));
        new ClassReader(transformed);

        URLClassLoader dependencies = new URLClassLoader(
            new URL[] { jarFile.toURI().toURL() }, getClass().getClassLoader());
        try {
            ByteLoader loader = new ByteLoader(dependencies);
            assertEquals(REAL_CLASS, loader.define(REAL_CLASS, transformed).getName());
        } finally {
            dependencies.close();
        }
    }

    private static TargetSpec targetFor(String className) {
        return new TargetSpec(className, "chunk-mesh-dynamic-trees",
            "dynamic-trees-connections", Collections.<String>emptySet());
    }

    private static byte[] syntheticClass(boolean duplicateRadiusCall) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, SYNTHETIC_INTERNAL, null,
            "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "radiusCalls", "I", null, null).visitEnd();

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();

        MethodVisitor radius = writer.visitMethod(Opcodes.ACC_PROTECTED,
            DynamicTreesConnectionAdapter.RADIUS_METHOD,
            DynamicTreesConnectionAdapter.RADIUS_DESCRIPTOR, null, null);
        radius.visitCode();
        radius.visitVarInsn(Opcodes.ALOAD, 0);
        radius.visitInsn(Opcodes.DUP);
        radius.visitFieldInsn(Opcodes.GETFIELD, SYNTHETIC_INTERNAL, "radiusCalls", "I");
        radius.visitInsn(Opcodes.ICONST_1);
        radius.visitInsn(Opcodes.IADD);
        radius.visitFieldInsn(Opcodes.PUTFIELD, SYNTHETIC_INTERNAL, "radiusCalls", "I");
        radius.visitInsn(Opcodes.ICONST_1);
        radius.visitInsn(Opcodes.IRETURN);
        radius.visitMaxs(3, 3);
        radius.visitEnd();

        MethodVisitor poll = writer.visitMethod(Opcodes.ACC_PROTECTED,
            DynamicTreesConnectionAdapter.TARGET_METHOD,
            DynamicTreesConnectionAdapter.TARGET_DESCRIPTOR, null, null);
        poll.visitCode();
        poll.visitIntInsn(Opcodes.BIPUSH, 6);
        poll.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT);
        poll.visitVarInsn(Opcodes.ASTORE, 3);
        poll.visitVarInsn(Opcodes.ALOAD, 3);
        poll.visitInsn(Opcodes.ICONST_0);
        poll.visitVarInsn(Opcodes.ALOAD, 0);
        poll.visitVarInsn(Opcodes.ALOAD, 2);
        poll.visitInsn(Opcodes.ACONST_NULL);
        poll.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SYNTHETIC_INTERNAL,
            DynamicTreesConnectionAdapter.RADIUS_METHOD,
            DynamicTreesConnectionAdapter.RADIUS_DESCRIPTOR, false);
        poll.visitInsn(Opcodes.IASTORE);
        if (duplicateRadiusCall) {
            poll.visitVarInsn(Opcodes.ALOAD, 0);
            poll.visitVarInsn(Opcodes.ALOAD, 2);
            poll.visitInsn(Opcodes.ACONST_NULL);
            poll.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SYNTHETIC_INTERNAL,
                DynamicTreesConnectionAdapter.RADIUS_METHOD,
                DynamicTreesConnectionAdapter.RADIUS_DESCRIPTOR, false);
            poll.visitInsn(Opcodes.POP);
        }
        poll.visitVarInsn(Opcodes.ALOAD, 3);
        poll.visitInsn(Opcodes.ARETURN);
        poll.visitMaxs(5, 4);
        poll.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static int countCalls(byte[] bytes, final int opcode, final String owner,
                                  final String name, final String descriptor) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override
            public MethodVisitor visitMethod(int access, String methodName, String methodDescriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override
                    public void visitMethodInsn(int actualOpcode, String actualOwner, String actualName,
                                                String actualDescriptor, boolean itf) {
                        if (actualOpcode == opcode && owner.equals(actualOwner) && name.equals(actualName)
                            && descriptor.equals(actualDescriptor)) count[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }

    private static byte[] readEntry(File file, String entryName) throws Exception {
        JarFile jar = new JarFile(file);
        try {
            JarEntry entry = jar.getJarEntry(entryName);
            assertTrue("reviewed class must exist", entry != null);
            return readFully(jar.getInputStream(entry));
        } finally {
            jar.close();
        }
    }

    private static byte[] readFully(InputStream input) throws Exception {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        InputStream input = new FileInputStream(file);
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        } finally {
            input.close();
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    private static final class ByteLoader extends ClassLoader {
        private ByteLoader(ClassLoader parent) {
            super(parent);
        }

        private Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
