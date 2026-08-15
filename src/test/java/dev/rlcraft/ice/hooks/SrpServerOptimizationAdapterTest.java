package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import dev.rlcraft.ice.optimizer.compat.srp.SrpTargetSearchBridge;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class SrpServerOptimizationAdapterTest {
    private static final String BASE_CLASS =
        "com.dhanantry.scapeandrunparasites.entity.ai.misc.EntityParasiteBase";
    private static final String BASE_SHA =
        "96dccae061cfa550c428e57fe17246b2ddd8dca51e29738b3870cfa52c24a206";
    private static final String TARGET_CLASS =
        "com.dhanantry.scapeandrunparasites.entity.ai.EntityAINearestAttackableTargetStatus";
    private static final String TARGET_SHA =
        "cf826e540ee14651652448331614b2bef0a6eeb2b6500f9ddd7191fb18929eea";

    @Test
    public void navigatorAdapterAddsOneExactFactoryOverride() {
        byte[] original = syntheticParasiteBase();
        byte[] transformed = new SrpParasiteNavigatorAdapter().transform(BASE_CLASS, original,
            target(BASE_CLASS, "srp-path-node-cache", "srp-parasite-navigator"));
        assertEquals(1, countMethods(transformed, SrpParasiteNavigatorAdapter.METHOD,
            SrpParasiteNavigatorAdapter.DESCRIPTOR));
        assertEquals(1, countTypeInstructions(transformed, Opcodes.NEW, SrpParasiteNavigatorAdapter.NAVIGATOR));
        new ClassReader(transformed);
        assertEquals(BASE_CLASS, new ByteLoader(getClass().getClassLoader()).define(BASE_CLASS, transformed).getName());
    }

    @Test
    public void targetAdapterKeepsFallbackOutOfThePatchedPrivateListPath() {
        byte[] original = syntheticTargetTask();
        byte[] transformed = new SrpTargetSearchAdapter().transform(TARGET_CLASS, original,
            target(TARGET_CLASS, "srp-target-search", "srp-target-linear-select"));
        assertEquals(1, countCalls(transformed, Opcodes.INVOKESTATIC,
            SrpTargetSearchAdapter.BRIDGE, "selectFirst", SrpTargetSearchAdapter.SORT_DESCRIPTOR));
        assertEquals(0, countCalls(transformed, Opcodes.INVOKESTATIC,
            SrpTargetSearchAdapter.SORT_OWNER, SrpTargetSearchAdapter.SORT_METHOD,
            SrpTargetSearchAdapter.SORT_DESCRIPTOR));
        new ClassReader(transformed);
    }

    @Test
    public void linearSelectionReturnsTheSameStableFirstMinimum() {
        OptimizerRegistry.breaker(OptimizationModule.SRP_TARGET_SEARCH).configure(true, 3);
        OptimizerRegistry.breaker(OptimizationModule.SRP_TARGET_SEARCH)
            .patchInstalled(TARGET_CLASS, TARGET_SHA);
        try {
            Item first = new Item("first", 2);
            Item tiedFirst = new Item("tied-first", 1);
            Item tiedSecond = new Item("tied-second", 1);
            List<Item> values = new ArrayList<Item>(Arrays.asList(first, tiedFirst, tiedSecond));
            Comparator<Item> comparator = new Comparator<Item>() {
                @Override public int compare(Item left, Item right) {
                    return Integer.compare(left.distance, right.distance);
                }
            };
            SrpTargetSearchBridge.selectFirst(values, comparator);
            assertTrue(values.get(0) == tiedFirst);
            assertEquals(3, values.size());
        } finally {
            OptimizerRegistry.breaker(OptimizationModule.SRP_TARGET_SEARCH).configure(false, 3);
        }
    }

    @Test
    public void transformsAndDefinesBothReviewedRealSrpServerClassesWhenAvailable() throws Exception {
        String configured = System.getProperty("ice.srp.jar", "").trim();
        Assume.assumeTrue("run with -PsrpJar=<jar>", !configured.isEmpty());
        File file = new File(configured);
        Assume.assumeTrue(file.isFile());
        JarFile jar = new JarFile(file);
        URLClassLoader dependencies = new URLClassLoader(new URL[] { file.toURI().toURL() }, getClass().getClassLoader());
        try {
            byte[] base = read(jar, BASE_CLASS);
            assertEquals(BASE_SHA, CoreClassFingerprint.sha256(base));
            byte[] transformedBase = new IceClientOptimizerTransformer().transform(BASE_CLASS, BASE_CLASS, base);
            assertFalse(Arrays.equals(base, transformedBase));
            assertEquals(1, countMethods(transformedBase, SrpParasiteNavigatorAdapter.METHOD,
                SrpParasiteNavigatorAdapter.DESCRIPTOR));
            assertEquals(BASE_CLASS, new ByteLoader(dependencies).define(BASE_CLASS, transformedBase).getName());

            byte[] target = read(jar, TARGET_CLASS);
            assertEquals(TARGET_SHA, CoreClassFingerprint.sha256(target));
            byte[] transformedTarget = new IceClientOptimizerTransformer().transform(
                TARGET_CLASS, TARGET_CLASS, target);
            assertFalse(Arrays.equals(target, transformedTarget));
            assertEquals(1, countCalls(transformedTarget, Opcodes.INVOKESTATIC,
                SrpTargetSearchAdapter.BRIDGE, "selectFirst", SrpTargetSearchAdapter.SORT_DESCRIPTOR));
            assertEquals(TARGET_CLASS,
                new ByteLoader(dependencies).define(TARGET_CLASS, transformedTarget).getName());
        } finally {
            dependencies.close();
            jar.close();
        }
    }

    private static byte[] syntheticParasiteBase() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
            SrpParasiteNavigatorAdapter.TARGET, null, SrpParasiteNavigatorAdapter.EXPECTED_SUPER, null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
            "(Lnet/minecraft/world/World;)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ALOAD, 1);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, SrpParasiteNavigatorAdapter.EXPECTED_SUPER,
            "<init>", "(Lnet/minecraft/world/World;)V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(2, 2);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] syntheticTargetTask() {
        String internal = TARGET_CLASS.replace('.', '/');
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, SrpTargetSearchAdapter.METHOD,
            SrpTargetSearchAdapter.METHOD_DESCRIPTOR, null, null);
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        method.visitVarInsn(Opcodes.ASTORE, 1);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, SrpTargetSearchAdapter.SORT_OWNER,
            SrpTargetSearchAdapter.SORT_METHOD, SrpTargetSearchAdapter.SORT_DESCRIPTOR, false);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(2, 2);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
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

    private static int countMethods(byte[] bytes, final String methodName, final String descriptor) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc,
                                                       String signature, String[] exceptions) {
                if (methodName.equals(name) && descriptor.equals(desc)) count[0]++;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }

    private static int countTypeInstructions(byte[] bytes, final int opcode, final String type) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                       String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override public void visitTypeInsn(int actualOpcode, String actualType) {
                        if (opcode == actualOpcode && type.equals(actualType)) count[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG);
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

    private static final class Item {
        private final String name;
        private final int distance;
        private Item(String name, int distance) { this.name = name; this.distance = distance; }
        @Override public String toString() { return name; }
    }

    private static final class ByteLoader extends ClassLoader {
        private ByteLoader(ClassLoader parent) { super(parent); }
        private Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
    }
}
