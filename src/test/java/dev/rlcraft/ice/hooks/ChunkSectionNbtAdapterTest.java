package dev.rlcraft.ice.hooks;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.Opcodes;

public final class ChunkSectionNbtAdapterTest {
    @Test public void actualSrgSectionAndRegistryRetainOriginalsAndInstallAccessors() throws Exception {
        String path = System.getProperty("ice.minecraft.srg.jar", "");
        Assume.assumeFalse(path.isEmpty());
        try (JarFile jar = new JarFile(path)) {
            verify(ChunkSectionNbtAdapter.SECTION, read(jar, ChunkSectionNbtAdapter.SECTION),
                "minecraft-section-nbt-palette", "ice$originalSectionNbt");
            byte[] transformed = verify(ChunkSectionNbtAdapter.ID_MAP,
                read(jar, ChunkSectionNbtAdapter.ID_MAP), "minecraft-state-id-map-access", "ice$directStateIds");
            Class<?> type = new ByteLoader(getClass().getClassLoader()).define(
                ChunkSectionNbtAdapter.ID_MAP.replace('/', '.'), transformed);
            Object map = type.newInstance();
            Object state = new Object();
            type.getMethod("func_148746_a", Object.class, int.class).invoke(map, state, 8123);
            java.util.Map<?, ?> raw = (java.util.Map<?, ?>) type.getMethod("ice$directStateIds").invoke(map);
            assertEquals(8123, raw.get(state));
        }
    }

    @Test public void actualOptifinePatchedSectionSupportsTheSameSaveAbi() throws Exception {
        try (OptifinePatchedClassSupport support = OptifinePatchedClassSupport.openOrSkip()) {
            verify(ChunkSectionNbtAdapter.SECTION, support.patchAndRemap("axp",
                ChunkSectionNbtAdapter.SECTION.replace('/', '.')),
                "minecraft-section-nbt-palette", "ice$originalSectionNbt");
        }
    }

    private static byte[] verify(String owner, byte[] original, String adapterId,
                                 String installedMethod) throws Exception {
        String name = owner.replace('/', '.');
        TargetSpec target = null;
        for (TargetSpec candidate : OptimizerTargetCatalog.findAll(name)) {
            if (adapterId.equals(candidate.adapterId)) target = candidate;
        }
        assertNotNull(target);
        byte[] result = OptimizerAdapterRegistry.find(adapterId).transform(name, original, target);
        assertFalse(Arrays.equals(original, result));
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(result).accept(node, 0);
        assertTrue(node.interfaces.contains("dev/rlcraft/ice/optimizer/compat/save/"
            + (owner.equals(ChunkSectionNbtAdapter.SECTION) ? "ChunkSectionNbtAccess" : "BlockStateIdMapAccess")));
        boolean found = false;
        for (MethodNode method : node.methods) if (installedMethod.equals(method.name)) found = true;
        assertTrue(found);
        return result;
    }

    private static byte[] read(JarFile jar, String owner) throws Exception {
        try (InputStream input = jar.getInputStream(jar.getJarEntry(owner + ".class"))) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) >= 0;) bytes.write(buffer, 0, count);
            return bytes.toByteArray();
        }
    }

    private static final class ByteLoader extends ClassLoader {
        ByteLoader(ClassLoader parent) { super(parent); }
        Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
    }
}
