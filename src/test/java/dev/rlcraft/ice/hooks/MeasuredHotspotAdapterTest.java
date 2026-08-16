package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Arrays;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class MeasuredHotspotAdapterTest {
    @Test
    public void transformsRealOptifineDynamicLightClassesWhenProvided() throws Exception {
        String configured = System.getProperty("ice.optifine.jar", "").trim();
        Assume.assumeTrue("run with -PoptifineJar=<jar>", !configured.isEmpty());
        JarFile jar = new JarFile(new File(configured));
        try {
            verifyTransform(jar, "net.optifine.DynamicLights", "optifine-dynamic-lights");
            verifyTransform(jar, "net.optifine.DynamicLight", "optifine-dynamic-light-access");
            verifyTransform(jar, "net.optifine.DynamicLightsMap", "optifine-dynamic-map-access");
        } finally {
            jar.close();
        }
    }

    @Test
    public void transformsRealRusticLatticeWhenProvided() throws Exception {
        String configured = System.getProperty("ice.rustic.jar", "").trim();
        Assume.assumeTrue("run with -PrusticJar=<jar>", !configured.isEmpty());
        JarFile jar = new JarFile(new File(configured));
        try {
            byte[] original = read(jar, "rustic.common.blocks.BlockLattice");
            byte[] transformed = new IceOptimizerTransformer().transform(
                "rustic.common.blocks.BlockLattice", "rustic.common.blocks.BlockLattice", original);
            assertFalse(Arrays.equals(original, transformed));
            assertEquals(1, countCalls(transformed, RusticLatticeAdapter.BRIDGE, "withProperty"));
            assertEquals(1, countCalls(transformed, RusticLatticeAdapter.BRIDGE, "boundingBox"));
            new ClassReader(transformed);
        } finally {
            jar.close();
        }
    }

    private static void verifyTransform(JarFile jar, String className, String adapterId) throws Exception {
        byte[] original = read(jar, className);
        TargetSpec target = null;
        for (TargetSpec candidate : OptimizerTargetCatalog.findAll(className)) {
            if (adapterId.equals(candidate.adapterId)) target = candidate;
        }
        assertNotNull(target);
        OptimizerBytecodeAdapter adapter = OptimizerAdapterRegistry.find(adapterId);
        assertNotNull(adapter);
        byte[] transformed = adapter.transform(className, original, target);
        assertFalse(Arrays.equals(original, transformed));
        new ClassReader(transformed);
        if (className.endsWith("DynamicLights")) {
            assertTrue(countCalls(transformed, OptifineDynamicLightsAdapter.BRIDGE,
                "getLightLevel") >= 1);
            assertTrue(countCalls(transformed, OptifineDynamicLightsAdapter.BRIDGE,
                "refresh") >= 4);
        }
    }

    private static byte[] read(JarFile jar, String className) throws Exception {
        JarEntry entry = jar.getJarEntry(className.replace('.', '/') + ".class");
        assertNotNull(entry);
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

    private static int countCalls(byte[] bytes, final String owner, final String name) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String methodName,
                                                       String descriptor, String signature,
                                                       String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override public void visitMethodInsn(int opcode, String actualOwner,
                                                          String actualName, String actualDescriptor,
                                                          boolean itf) {
                        if (owner.equals(actualOwner) && name.equals(actualName)) count[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }
}
