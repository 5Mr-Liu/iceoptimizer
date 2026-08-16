package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Collections;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class LycanitesOptimizationAdapterTest {
    private static final String PROCESSOR_CLASS =
        "com.lycanitesmobs.core.entity.navigate.CreatureNodeProcessor";
    private static final String PROCESSOR_SHA =
        "7973cbdb967cc40d57c87f5ebd9f537297a4dfc431290573044d5abb62a7cde0";
    private static final String MANAGER_CLASS = "com.lycanitesmobs.ObjectManager";
    private static final String MANAGER_SHA =
        "679202746322d2ee0695a66aac003e1a48a16f4db8e74cb7f9ef43faf5409f63";

    @Test
    public void nodeProcessorAdapterInstallsLifecycleAccessorAndAllReviewedCallSites() {
        byte[] original = syntheticProcessor();
        byte[] transformed = new LycanitesNodeProcessorAdapter().transform(PROCESSOR_CLASS, original,
            target(PROCESSOR_CLASS, "lycanites-path-node-cache", "lycanites-path-search-cache"));
        assertTrue(hasInterface(transformed, LycanitesNodeProcessorAdapter.ACCESSOR));
        assertEquals(1, countMethods(transformed, "ice$rawNodeType",
            LycanitesNodeProcessorAdapter.ACCESSOR_DESCRIPTOR));
        assertEquals(1, countCalls(transformed, Opcodes.INVOKESTATIC,
            LycanitesNodeProcessorAdapter.BRIDGE, "begin",
            "(L" + LycanitesNodeProcessorAdapter.ACCESSOR + ";Lnet/minecraft/world/IBlockAccess;)V"));
        assertEquals(1, countCalls(transformed, Opcodes.INVOKESTATIC,
            LycanitesNodeProcessorAdapter.BRIDGE, "end",
            "(L" + LycanitesNodeProcessorAdapter.ACCESSOR + ";)V"));
        assertEquals(2, countCalls(transformed, Opcodes.INVOKESTATIC,
            LycanitesNodeProcessorAdapter.BRIDGE, "rawNodeType",
            LycanitesNodeProcessorAdapter.BRIDGE_RAW_DESCRIPTOR));
        assertEquals(14, countCalls(transformed, Opcodes.INVOKESTATIC,
            LycanitesNodeProcessorAdapter.BRIDGE, "blockState",
            LycanitesNodeProcessorAdapter.BRIDGE_STATE_DESCRIPTOR));
        new ClassReader(transformed);
        assertEquals(PROCESSOR_CLASS,
            new ByteLoader(getClass().getClassLoader()).define(PROCESSOR_CLASS, transformed).getName());
    }

    @Test
    public void objectManagerAdapterRewritesOnlyTheTwoReviewedGetters() {
        byte[] original = syntheticObjectManager();
        byte[] transformed = new LycanitesObjectManagerAdapter().transform(MANAGER_CLASS, original,
            target(MANAGER_CLASS, "lycanites-registry-lookup", "lycanites-registry-single-probe"));
        assertEquals(2, countCalls(transformed, Opcodes.INVOKESTATIC,
            LycanitesObjectManagerAdapter.BRIDGE, "lookup",
            LycanitesObjectManagerAdapter.BRIDGE_DESCRIPTOR));
        assertEquals(0, countCalls(transformed, Opcodes.INVOKEINTERFACE,
            "java/util/Map", "containsKey", "(Ljava/lang/Object;)Z"));
        new ClassReader(transformed);
    }

    @Test
    public void objectManagerAdapterPreservesDregoraExactKeySemantics() {
        byte[] original = syntheticObjectManager(false);
        byte[] transformed = new LycanitesObjectManagerAdapter().transform(MANAGER_CLASS, original,
            target(MANAGER_CLASS, "lycanites-registry-lookup", "lycanites-registry-single-probe"));
        assertEquals(0, countCalls(transformed, Opcodes.INVOKESTATIC,
            LycanitesObjectManagerAdapter.BRIDGE, "lookup",
            LycanitesObjectManagerAdapter.BRIDGE_DESCRIPTOR));
        assertEquals(2, countCalls(transformed, Opcodes.INVOKESTATIC,
            LycanitesObjectManagerAdapter.BRIDGE, "lookupExact",
            LycanitesObjectManagerAdapter.BRIDGE_DESCRIPTOR));
        assertEquals(0, countCalls(transformed, Opcodes.INVOKEINTERFACE,
            "java/util/Map", "containsKey", "(Ljava/lang/Object;)Z"));
        new ClassReader(transformed);
    }

    @Test
    public void transformsAndDefinesBothReviewedRealLycanitesClassesWhenAvailable() throws Exception {
        String configured = System.getProperty("ice.lycanites.jar", "").trim();
        Assume.assumeTrue("run with -PlycanitesJar=<jar>", !configured.isEmpty());
        File file = new File(configured);
        Assume.assumeTrue(file.isFile());
        JarFile jar = new JarFile(file);
        URLClassLoader dependencies = new URLClassLoader(new URL[] { file.toURI().toURL() }, getClass().getClassLoader());
        try {
            byte[] processor = read(jar, PROCESSOR_CLASS);
            assertEquals(PROCESSOR_SHA, CoreClassFingerprint.sha256(processor));
            byte[] transformedProcessor = new IceClientOptimizerTransformer().transform(
                PROCESSOR_CLASS, PROCESSOR_CLASS, processor);
            assertFalse(Arrays.equals(processor, transformedProcessor));
            assertEquals(2, countCalls(transformedProcessor, Opcodes.INVOKESTATIC,
                LycanitesNodeProcessorAdapter.BRIDGE, "rawNodeType",
                LycanitesNodeProcessorAdapter.BRIDGE_RAW_DESCRIPTOR));
            assertEquals(14, countCalls(transformedProcessor, Opcodes.INVOKESTATIC,
                LycanitesNodeProcessorAdapter.BRIDGE, "blockState",
                LycanitesNodeProcessorAdapter.BRIDGE_STATE_DESCRIPTOR));
            assertEquals(PROCESSOR_CLASS,
                new ByteLoader(dependencies).define(PROCESSOR_CLASS, transformedProcessor).getName());

            byte[] manager = read(jar, MANAGER_CLASS);
            String managerFingerprint = CoreClassFingerprint.sha256(manager);
            assertTrue(MANAGER_SHA.equals(managerFingerprint)
                || OptimizerTargetCatalog.find(MANAGER_CLASS).hasReviewedFingerprint(managerFingerprint));
            byte[] transformedManager = new IceClientOptimizerTransformer().transform(
                MANAGER_CLASS, MANAGER_CLASS, manager);
            assertFalse(Arrays.equals(manager, transformedManager));
            int normalizedLookups = countCalls(transformedManager, Opcodes.INVOKESTATIC,
                LycanitesObjectManagerAdapter.BRIDGE, "lookup",
                LycanitesObjectManagerAdapter.BRIDGE_DESCRIPTOR);
            int exactLookups = countCalls(transformedManager, Opcodes.INVOKESTATIC,
                LycanitesObjectManagerAdapter.BRIDGE, "lookupExact",
                LycanitesObjectManagerAdapter.BRIDGE_DESCRIPTOR);
            assertEquals(2, normalizedLookups + exactLookups);
            assertEquals(MANAGER_CLASS,
                new ByteLoader(dependencies).define(MANAGER_CLASS, transformedManager).getName());
        } finally {
            dependencies.close();
            jar.close();
        }
    }

    private static byte[] syntheticProcessor() {
        String target = LycanitesNodeProcessorAdapter.TARGET;
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
            target, null, "net/minecraft/pathfinding/NodeProcessor", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "net/minecraft/pathfinding/NodeProcessor",
            "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();

        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, LycanitesNodeProcessorAdapter.INIT,
            LycanitesNodeProcessorAdapter.INIT_DESCRIPTOR, null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitVarInsn(Opcodes.ALOAD, 2);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "net/minecraft/pathfinding/NodeProcessor",
            LycanitesNodeProcessorAdapter.INIT, LycanitesNodeProcessorAdapter.INIT_DESCRIPTOR, false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(3, 3);
        init.visitEnd();

        MethodVisitor done = writer.visitMethod(Opcodes.ACC_PUBLIC, LycanitesNodeProcessorAdapter.DONE,
            "()V", null, null);
        done.visitCode();
        done.visitVarInsn(Opcodes.ALOAD, 0);
        done.visitMethodInsn(Opcodes.INVOKESPECIAL, "net/minecraft/pathfinding/NodeProcessor",
            LycanitesNodeProcessorAdapter.DONE, "()V", false);
        done.visitInsn(Opcodes.RETURN);
        done.visitMaxs(1, 1);
        done.visitEnd();

        MethodVisitor raw = writer.visitMethod(Opcodes.ACC_PROTECTED, LycanitesNodeProcessorAdapter.RAW,
            LycanitesNodeProcessorAdapter.RAW_DESCRIPTOR, null, null);
        raw.visitCode();
        raw.visitVarInsn(Opcodes.ALOAD, 1);
        raw.visitTypeInsn(Opcodes.NEW, "net/minecraft/util/math/BlockPos");
        raw.visitInsn(Opcodes.DUP);
        raw.visitVarInsn(Opcodes.ILOAD, 2);
        raw.visitVarInsn(Opcodes.ILOAD, 3);
        raw.visitVarInsn(Opcodes.ILOAD, 4);
        raw.visitMethodInsn(Opcodes.INVOKESPECIAL, "net/minecraft/util/math/BlockPos",
            "<init>", "(III)V", false);
        raw.visitMethodInsn(Opcodes.INVOKEINTERFACE, LycanitesNodeProcessorAdapter.STATE_OWNER,
            LycanitesNodeProcessorAdapter.STATE_METHOD, LycanitesNodeProcessorAdapter.STATE_DESCRIPTOR, true);
        raw.visitInsn(Opcodes.POP);
        raw.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/pathfinding/PathNodeType", "OPEN",
            "Lnet/minecraft/pathfinding/PathNodeType;");
        raw.visitInsn(Opcodes.ARETURN);
        raw.visitMaxs(6, 5);
        raw.visitEnd();

        MethodVisitor probe = writer.visitMethod(Opcodes.ACC_PUBLIC, "probe",
            "(Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/util/math/BlockPos;)V", null, null);
        probe.visitCode();
        for (int i = 0; i < 13; i++) stateCall(probe);
        for (int i = 0; i < 2; i++) {
            probe.visitVarInsn(Opcodes.ALOAD, 0);
            probe.visitVarInsn(Opcodes.ALOAD, 1);
            probe.visitInsn(Opcodes.ICONST_1);
            probe.visitInsn(Opcodes.ICONST_2);
            probe.visitInsn(Opcodes.ICONST_3);
            probe.visitMethodInsn(Opcodes.INVOKEVIRTUAL, target, LycanitesNodeProcessorAdapter.RAW,
                LycanitesNodeProcessorAdapter.RAW_DESCRIPTOR, false);
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
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, LycanitesNodeProcessorAdapter.STATE_OWNER,
            LycanitesNodeProcessorAdapter.STATE_METHOD, LycanitesNodeProcessorAdapter.STATE_DESCRIPTOR, true);
        method.visitInsn(Opcodes.POP);
    }

    private static byte[] syntheticObjectManager() {
        return syntheticObjectManager(true);
    }

    private static byte[] syntheticObjectManager(boolean normalize) {
        String target = LycanitesObjectManagerAdapter.TARGET;
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, target, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "blocks", "Ljava/util/Map;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "effects", "Ljava/util/Map;", null, null).visitEnd();
        addGetter(writer, target, "getBlock", "blocks", "net/minecraft/block/Block", normalize);
        addGetter(writer, target, "getEffect", "effects", "com/lycanitesmobs/PotionBase", normalize);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void addGetter(ClassWriter writer, String owner, String name, String field, String returnType,
                                  boolean normalize) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name,
            "(Ljava/lang/String;)L" + returnType + ";", null, null);
        Label present = new Label();
        method.visitCode();
        if (normalize) {
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toLowerCase",
                "()Ljava/lang/String;", false);
            method.visitVarInsn(Opcodes.ASTORE, 0);
        }
        method.visitFieldInsn(Opcodes.GETSTATIC, owner, field, "Ljava/util/Map;");
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "containsKey",
            "(Ljava/lang/Object;)Z", true);
        method.visitJumpInsn(Opcodes.IFNE, present);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.ARETURN);
        method.visitLabel(present);
        method.visitFieldInsn(Opcodes.GETSTATIC, owner, field, "Ljava/util/Map;");
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "get",
            "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        method.visitTypeInsn(Opcodes.CHECKCAST, returnType);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(2, 1);
        method.visitEnd();
    }

    private static TargetSpec target(String className, String module, String adapter) {
        return new TargetSpec(className, module, adapter, Collections.<String>emptySet());
    }

    private static byte[] read(JarFile jar, String className) throws Exception {
        JarEntry entry = jar.getJarEntry(className.replace('.', '/') + ".class");
        assertTrue(entry != null);
        return readFully(jar.getInputStream(entry));
    }

    private static byte[] readFully(InputStream input) throws Exception {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toByteArray();
        } finally { input.close(); }
    }

    private static boolean hasInterface(byte[] bytes, final String expected) {
        final boolean[] found = new boolean[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public void visit(int version, int access, String name, String signature,
                                        String superName, String[] interfaces) {
                for (String value : interfaces) if (expected.equals(value)) found[0] = true;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static int countMethods(byte[] bytes, final String name, final String descriptor) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String actualName, String actualDescriptor,
                                                       String signature, String[] exceptions) {
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
            @Override public MethodVisitor visitMethod(int access, String methodName, String methodDescriptor,
                                                       String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override public void visitMethodInsn(int actualOpcode, String actualOwner, String actualName,
                                                          String actualDescriptor, boolean itf) {
                        if (opcode == actualOpcode && owner.equals(actualOwner) && name.equals(actualName)
                            && descriptor.equals(actualDescriptor)) count[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG);
        return count[0];
    }

    private static final class ByteLoader extends ClassLoader {
        private ByteLoader(ClassLoader parent) { super(parent); }
        private Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
    }
}
