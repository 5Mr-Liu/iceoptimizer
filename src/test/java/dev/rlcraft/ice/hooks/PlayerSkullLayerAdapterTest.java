package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

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

public class PlayerSkullLayerAdapterTest {
    private static final String CLASS_NAME =
        "net.minecraft.client.renderer.entity.layers.LayerCustomHead";
    private static final String ENTRY =
        "net/minecraft/client/renderer/entity/layers/LayerCustomHead.class";
    private static final String SRG_SHA256 =
        "f68122c2a0e3c795a8163fe7a4ceb9e592d6955da8567efe3e1bb7acd30d8732";

    @Test
    public void transformsTheExactForgeSrgLayerWithoutChangingStackShape() throws Exception {
        String configured = System.getProperty("ice.minecraft.srg.jar", "").trim();
        Assume.assumeTrue("run with -PminecraftSrgJar=<forge-1.12.2-14.23.5.2860-srg.jar>",
            !configured.isEmpty());
        File file = new File(configured);
        Assume.assumeTrue(file.isFile());
        JarFile jar = new JarFile(file);
        byte[] original;
        try {
            JarEntry entry = jar.getJarEntry(ENTRY);
            original = readFully(jar.getInputStream(entry));
        } finally {
            jar.close();
        }
        assertEquals(SRG_SHA256, CoreClassFingerprint.sha256(original));
        byte[] transformed = new IceClientOptimizerTransformer().transform(
            CLASS_NAME, CLASS_NAME, original);
        assertFalse(Arrays.equals(original, transformed));
        new ClassReader(transformed);
        assertEquals(1, countCalls(transformed, "resolveForRenderLookup"));
        assertEquals(1, countCalls(transformed, "decorateForRender"));
        assertEquals(0, countCalls(transformed, "func_174884_b"));
    }

    private static int countCalls(byte[] bytes, final String name) {
        final int[] result = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String methodName,
                                                       String descriptor, String signature,
                                                       String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override public void visitMethodInsn(int opcode, String owner,
                                                          String actualName, String actualDescriptor,
                                                          boolean itf) {
                        if (name.equals(actualName)) result[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return result[0];
    }

    private static byte[] readFully(InputStream input) throws Exception {
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
}
