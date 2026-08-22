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
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Optional raw iChunUtil fixture plus structural wrapper verification. */
public final class WorldPortalAdapterTest {
    private static final String CLASS_NAME =
        "me.ichun.mods.ichunutil.common.module.worldportals.client.render.WorldPortalRenderer";
    private static final String SHA256 =
        "677cb31c01dfeda5f748bed26fd760e56fe2d65932e7f02f8498c94ac47308aa";

    @Test
    public void transformsReviewedIChunUtilPortalEntry() throws Exception {
        String configured = System.getProperty("ice.ichunutil.jar", "").trim();
        Assume.assumeTrue("run with -PichunUtilJar=<iChunUtil jar>",
            !configured.isEmpty());
        File file = new File(configured);
        Assume.assumeTrue(file.isFile());
        byte[] original = read(file, CLASS_NAME);
        assertEquals(SHA256, CoreClassFingerprint.sha256(original));
        TargetSpec target = OptimizerTargetCatalog.find(CLASS_NAME);
        assertNotNull(target);
        assertEquals("ichun-worldportal-legacy-island", target.adapterId);
        byte[] transformed = OptimizerAdapterRegistry.find(target.adapterId)
            .transform(CLASS_NAME, original, target);
        assertNotNull(transformed);
        assertFalse(Arrays.equals(original, transformed));

        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(transformed).accept(node, 0);
        MethodNode wrapper = find(node, WorldPortalAdapter.METHOD,
            WorldPortalAdapter.DESCRIPTOR);
        MethodNode renamed = find(node, WorldPortalAdapter.ORIGINAL,
            WorldPortalAdapter.DESCRIPTOR);
        assertNotNull(wrapper);
        assertNotNull(renamed);
        assertEquals(1, wrapper.tryCatchBlocks.size());
        assertEquals(1, calls(wrapper, "begin", "()J"));
        assertEquals(1, calls(wrapper, "end", "(J)V"));
        assertEquals(1, calls(wrapper, "abort", "(JLjava/lang/Throwable;)V"));
    }

    private static int calls(MethodNode method, String name, String descriptor) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (WorldPortalAdapter.BOOTSTRAP.equals(call.owner)
                && name.equals(call.name) && descriptor.equals(call.desc)) result++;
        }
        return result;
    }

    private static MethodNode find(ClassNode node, String name,
                                   String descriptor) {
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) return method;
        }
        return null;
    }

    private static byte[] read(File file, String className) throws Exception {
        JarFile jar = new JarFile(file);
        try {
            JarEntry entry = jar.getJarEntry(className.replace('.', '/') + ".class");
            assertNotNull(entry);
            InputStream input = jar.getInputStream(entry);
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            } finally {
                input.close();
            }
        } finally {
            jar.close();
        }
    }
}
