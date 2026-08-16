package dev.rlcraft.ice.hooks;

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

public final class ChunkSaveCompressionAdapterTest {
    @Test
    public void transformsForgeSrgSaveClassesWhenProvided() throws Exception {
        String configured = System.getProperty("ice.minecraft.srg.jar", "").trim();
        Assume.assumeTrue("run with -PminecraftSrgJar=<jar>", !configured.isEmpty());
        JarFile jar = new JarFile(new File(configured));
        try {
            verify(jar, "net.minecraft.world.chunk.storage.AnvilChunkLoader",
                new ChunkSaveCompressionAdapter(ChunkSaveCompressionAdapter.Part.ANVIL_PIPELINE),
                "schedule", "tryWrite");
            verify(jar, "net.minecraft.world.chunk.storage.RegionFile",
                new ChunkSaveCompressionAdapter(ChunkSaveCompressionAdapter.Part.REGION_RAW_WRITE),
                "createDeflaterStream", ChunkSaveCompressionAdapter.RAW_ACCESS_METHOD);
        } finally {
            jar.close();
        }
    }

    private static void verify(JarFile jar, String className,
                               OptimizerBytecodeAdapter adapter,
                               String firstCall, String secondCall) throws Exception {
        byte[] original = read(jar, className);
        TargetSpec target = OptimizerTargetCatalog.find(className);
        assertNotNull(target);
        byte[] transformed = adapter.transform(className, original, target);
        assertFalse(Arrays.equals(original, transformed));
        assertTrue(countCalls(transformed, firstCall) >= 1);
        assertTrue(countCalls(transformed, secondCall) >= 1);
        new ClassReader(transformed);
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

    private static int countCalls(byte[] bytes, final String name) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String methodName,
                                                       String descriptor, String signature,
                                                       String[] exceptions) {
                if (name.equals(methodName)) count[0]++;
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override public void visitMethodInsn(int opcode, String owner,
                                                          String method, String desc,
                                                          boolean itf) {
                        if (name.equals(method)) count[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }
}
