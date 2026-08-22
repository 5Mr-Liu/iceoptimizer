package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

/** Real-bytecode regression coverage for the save and Lycanites scan hitch fixes. */
public final class HitchFixAdapterTest {
    @Test
    public void transformsReviewedMinecraftSaveClassesWhenSrgJarIsAvailable() throws Exception {
        String configured = System.getProperty("ice.minecraft.srg.jar", "").trim();
        Assume.assumeTrue("run with -PminecraftSrgJar=<jar>", !configured.isEmpty());
        JarFile jar = new JarFile(new File(configured));
        try {
            byte[] world = verifyMinecraftClass(jar, "net.minecraft.world.WorldServer",
                "68016652f60b9efaa98620453dbf12c5e4e6e413247496fb97e89b05210c3405");
            byte[] provider = verifyMinecraftClass(jar, "net.minecraft.world.gen.ChunkProviderServer",
                "25cb0a9da6e0142d3bc1b58913e7ee55e2ec22f9164bdcf18f5a750ddbcf8129");
            ByteLoader loader = new ByteLoader(getClass().getClassLoader());
            assertEquals("net.minecraft.world.WorldServer",
                loader.define("net.minecraft.world.WorldServer", world).getName());
            assertEquals("net.minecraft.world.gen.ChunkProviderServer",
                loader.define("net.minecraft.world.gen.ChunkProviderServer", provider).getName());
        } finally {
            jar.close();
        }
    }

    @Test
    public void transformsReviewedLycanitesSpawnScanWhenJarIsAvailable() throws Exception {
        String configured = System.getProperty("ice.dregora.lycanites.jar", "").trim();
        Assume.assumeTrue("run with -PdregoraLycanitesJar=<jar>", !configured.isEmpty());
        JarFile jar = new JarFile(new File(configured));
        try {
            String className = "com.lycanitesmobs.core.spawner.location.BlockSpawnLocation";
            byte[] original = read(jar, className);
            assertEquals("52ed611aecc7f2e4e0ac006ea8bc1b2f80c0f6a728898999434b64567f8c510d",
                CoreClassFingerprint.sha256(original));
            byte[] transformed = new IceOptimizerTransformer().transform(
                className, className, original);
            assertFalse(Arrays.equals(original, transformed));
            assertTrue(hasInterface(transformed, LycanitesSpawnScanAdapter.ACCESSOR));
            assertTrue(hasMethod(transformed, LycanitesSpawnScanAdapter.BACKUP_METHOD,
                LycanitesSpawnScanAdapter.SCAN_DESCRIPTOR));
            assertEquals(1, countCalls(transformed, LycanitesSpawnScanAdapter.BRIDGE,
                "begin", "(L" + LycanitesSpawnScanAdapter.ACCESSOR + ";)J"));
            assertEquals(2, countCalls(transformed, LycanitesSpawnScanAdapter.BRIDGE,
                "end", "(J)V"));
            assertEquals(1, countCalls(transformed, LycanitesSpawnScanAdapter.BRIDGE,
                "scanState", "(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;)"
                    + "Lnet/minecraft/block/state/IBlockState;"));
            assertEquals(1, countCalls(transformed, LycanitesSpawnScanAdapter.BRIDGE,
                "validationState", "(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;)"
                    + "Lnet/minecraft/block/state/IBlockState;"));
            assertEquals(1, countCalls(transformed, LycanitesSpawnScanAdapter.BRIDGE,
                "newBlockCounter", "(Ljava/lang/Object;)Ljava/util/Map;"));
            assertEquals(2, countCalls(transformed, LycanitesBlockMembershipAdapter.BRIDGE,
                "track", LycanitesBlockMembershipAdapter.TRACK_DESCRIPTOR));
            new ClassReader(transformed);
        } finally {
            jar.close();
        }
    }

    private static byte[] verifyMinecraftClass(JarFile jar, String className, String sha) throws Exception {
        byte[] original = read(jar, className);
        assertEquals(sha, CoreClassFingerprint.sha256(original));
        TargetSpec target = OptimizerTargetCatalog.find(className);
        assertNotNull(target);
        byte[] transformed = new IceOptimizerTransformer().transform(className, className, original);
        assertFalse(Arrays.equals(original, transformed));
        new ClassReader(transformed);
        if (className.endsWith("WorldServer")) {
            assertTrue(hasInterface(transformed, MinecraftSaveTickAdapter.ACCESSOR));
            assertTrue(hasField(transformed, MinecraftSaveTickAdapter.VERSION_FIELD, "J"));
            assertTrue(hasMethod(transformed, MinecraftSaveTickAdapter.ORIGINAL_PENDING_METHOD,
                MinecraftSaveTickAdapter.PENDING_DESCRIPTOR));
            assertEquals(1, countCalls(transformed, MinecraftSaveTickAdapter.BRIDGE,
                "pendingBlockUpdates", "(L" + MinecraftSaveTickAdapter.ACCESSOR
                    + ";Lnet/minecraft/world/chunk/Chunk;Z)Ljava/util/List;"));
        } else {
            assertTrue(hasMethod(transformed, MinecraftSaveTickAdapter.ORIGINAL_SAVE_METHOD,
                MinecraftSaveTickAdapter.SAVE_DESCRIPTOR));
            assertTrue(hasMethod(transformed, MinecraftSaveTickAdapter.ORIGINAL_PROVIDER_TICK_METHOD,
                MinecraftSaveTickAdapter.PROVIDER_TICK_DESCRIPTOR));
            assertEquals(2, countCalls(transformed, MinecraftSaveTickAdapter.BRIDGE,
                "begin", "(Ljava/lang/Object;Ljava/lang/Object;Z)J"));
            assertEquals(4, countCalls(transformed, MinecraftSaveTickAdapter.BRIDGE,
                "end", "(J)V"));
            assertCatchAllFinallyWrapper(transformed,
                MinecraftSaveTickAdapter.SAVE_METHOD,
                MinecraftSaveTickAdapter.SAVE_DESCRIPTOR,
                MinecraftSaveTickAdapter.ORIGINAL_SAVE_METHOD);
            assertCatchAllFinallyWrapper(transformed,
                MinecraftSaveTickAdapter.PROVIDER_TICK_METHOD,
                MinecraftSaveTickAdapter.PROVIDER_TICK_DESCRIPTOR,
                MinecraftSaveTickAdapter.ORIGINAL_PROVIDER_TICK_METHOD);
            assertEquals(1, countCallsInMethod(transformed,
                MinecraftSaveTickAdapter.ORIGINAL_PROVIDER_TICK_METHOD,
                MinecraftSaveTickAdapter.PROVIDER_TICK_DESCRIPTOR,
                MinecraftSaveTickAdapter.PROVIDER_TARGET, "func_73242_b",
                "(Lnet/minecraft/world/chunk/Chunk;)V"));
            assertEquals(1, countCallsInMethod(transformed,
                MinecraftSaveTickAdapter.ORIGINAL_PROVIDER_TICK_METHOD,
                MinecraftSaveTickAdapter.PROVIDER_TICK_DESCRIPTOR,
                MinecraftSaveTickAdapter.PROVIDER_TARGET, "func_73243_a",
                "(Lnet/minecraft/world/chunk/Chunk;)V"));
            assertEquals(1, countCallsInMethod(transformed,
                MinecraftSaveTickAdapter.ORIGINAL_PROVIDER_TICK_METHOD,
                MinecraftSaveTickAdapter.PROVIDER_TICK_DESCRIPTOR,
                "it/unimi/dsi/fastutil/longs/Long2ObjectMap", "remove",
                "(Ljava/lang/Object;)Ljava/lang/Object;"));
        }
        return transformed;
    }

    private static void assertCatchAllFinallyWrapper(byte[] bytes,
                                                     String methodName,
                                                     String descriptor,
                                                     String originalName) {
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(bytes).accept(node, 0);
        MethodNode wrapper = null;
        for (MethodNode method : node.methods) {
            if (methodName.equals(method.name) && descriptor.equals(method.desc)) {
                wrapper = method;
            }
        }
        assertNotNull(methodName, wrapper);
        assertEquals(methodName, 1, wrapper.tryCatchBlocks.size());
        TryCatchBlockNode block = wrapper.tryCatchBlocks.get(0);
        assertNull(methodName + " must use a catch-all finally", block.type);
        int start = wrapper.instructions.indexOf(block.start);
        int end = wrapper.instructions.indexOf(block.end);
        int handler = wrapper.instructions.indexOf(block.handler);
        assertTrue(methodName, start >= 0 && start < end && end <= handler);

        int original = -1;
        int beginCalls = 0;
        int endCalls = 0;
        int throwsAfterHandler = 0;
        for (AbstractInsnNode instruction : wrapper.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (MinecraftSaveTickAdapter.PROVIDER_TARGET.equals(call.owner)
                    && originalName.equals(call.name)
                    && descriptor.equals(call.desc)) {
                    original = wrapper.instructions.indexOf(instruction);
                }
                if (MinecraftSaveTickAdapter.BRIDGE.equals(call.owner)
                    && "begin".equals(call.name)) beginCalls++;
                if (MinecraftSaveTickAdapter.BRIDGE.equals(call.owner)
                    && "end".equals(call.name)) endCalls++;
            } else if (instruction.getOpcode() == Opcodes.ATHROW
                && wrapper.instructions.indexOf(instruction) > handler) {
                throwsAfterHandler++;
            }
        }
        assertTrue(methodName + " original call must be protected",
            original > start && original < end);
        assertEquals(methodName, 1, beginCalls);
        assertEquals(methodName, 2, endCalls);
        assertEquals(methodName, 1, throwsAfterHandler);
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

    private static boolean hasField(byte[] bytes, final String name, final String descriptor) {
        final boolean[] found = new boolean[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public FieldVisitor visitField(int access, String actualName,
                                                     String actualDescriptor, String signature,
                                                     Object value) {
                if (name.equals(actualName) && descriptor.equals(actualDescriptor)) found[0] = true;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static boolean hasMethod(byte[] bytes, final String name, final String descriptor) {
        final boolean[] found = new boolean[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String actualName,
                                                       String actualDescriptor, String signature,
                                                       String[] exceptions) {
                if (name.equals(actualName) && descriptor.equals(actualDescriptor)) found[0] = true;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static int countCalls(byte[] bytes, final String owner,
                                  final String name, final String descriptor) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String methodName,
                                                       String methodDescriptor, String signature,
                                                       String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override public void visitMethodInsn(int opcode, String actualOwner,
                                                          String actualName, String actualDescriptor,
                                                          boolean itf) {
                        if (owner.equals(actualOwner) && name.equals(actualName)
                            && descriptor.equals(actualDescriptor)) count[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }

    private static int countCallsInMethod(byte[] bytes, final String methodName,
                                          final String methodDescriptor, final String owner,
                                          final String name, final String descriptor) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String actualMethodName,
                                                       String actualMethodDescriptor,
                                                       String signature, String[] exceptions) {
                if (!methodName.equals(actualMethodName)
                    || !methodDescriptor.equals(actualMethodDescriptor)) return null;
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override public void visitMethodInsn(int opcode, String actualOwner,
                                                          String actualName,
                                                          String actualDescriptor, boolean itf) {
                        if (owner.equals(actualOwner) && name.equals(actualName)
                            && descriptor.equals(actualDescriptor)) count[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }

    private static final class ByteLoader extends ClassLoader {
        private ByteLoader(ClassLoader parent) { super(parent); }
        private Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
