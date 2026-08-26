package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

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

public class PlayerSkullRendererAdapterTest {
    private static final String CLASS_NAME =
        "net.minecraft.client.renderer.tileentity.TileEntitySkullRenderer";
    private static final String ENTRY =
        "net/minecraft/client/renderer/tileentity/TileEntitySkullRenderer.class";
    private static final String MAPPED_SHA256 =
        "2f809f39d68f445751253a688cd40bdf2adc08c7fab0e20490dee56833ba2efd";
    private static final String SRG_SHA256 =
        "72f4e1f4ae5485bcc73ed6b82d3f6554a5cbd3dae356d7edb288e4b128d6fe8d";
    private static final String BRIDGE_DESCRIPTOR =
        "(Lnet/minecraft/client/resources/SkinManager;Lcom/mojang/authlib/GameProfile;)Ljava/util/Map;";

    @Test
    public void transformsMappedRendererSharedByBlocksItemsAndHeadLayers() throws Exception {
        InputStream input = getClass().getClassLoader().getResourceAsStream(ENTRY);
        assertNotNull(input);
        byte[] original = readFully(input);
        assertEquals(MAPPED_SHA256, CoreClassFingerprint.sha256(original));
        verifyTransformation(original);
    }

    @Test
    public void transformsTheExactForgeSrgRendererWithoutChangingCallStackShape() throws Exception {
        String configured = System.getProperty("ice.minecraft.srg.jar", "").trim();
        Assume.assumeTrue("run with -PminecraftSrgJar=<forge-1.12.2-14.23.5.2860-srg.jar>",
            !configured.isEmpty());
        File file = new File(configured);
        Assume.assumeTrue(file.isFile());
        JarFile jar = new JarFile(file);
        byte[] original;
        try {
            JarEntry entry = jar.getJarEntry(ENTRY);
            assertNotNull(entry);
            original = readFully(jar.getInputStream(entry));
        } finally {
            jar.close();
        }
        assertEquals(SRG_SHA256, CoreClassFingerprint.sha256(original));
        verifyTransformation(original);
    }

    private static void verifyTransformation(byte[] original) {
        byte[] transformed = new IceClientOptimizerTransformer().transform(
            CLASS_NAME, CLASS_NAME, original);
        assertFalse(Arrays.equals(original, transformed));
        new ClassReader(transformed);
        assertEquals(1, countCalls(transformed, "decorateForRender", null));
        assertEquals(1, countCalls(transformed, "loadSkinFromCacheForRender",
            BRIDGE_DESCRIPTOR));
        assertEquals(0, countCalls(transformed, "func_152788_a", null));
        assertEquals(0, countCalls(transformed, "loadSkinFromCache", null));
    }

    private static int countCalls(byte[] bytes, final String name,
                                  final String expectedDescriptor) {
        final int[] result = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String methodName,
                                                       String descriptor, String signature,
                                                       String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override public void visitMethodInsn(int opcode, String owner,
                                                          String actualName,
                                                          String actualDescriptor,
                                                          boolean itf) {
                        if (name.equals(actualName)
                            && (expectedDescriptor == null
                                || expectedDescriptor.equals(actualDescriptor))) {
                            result[0]++;
                        }
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
