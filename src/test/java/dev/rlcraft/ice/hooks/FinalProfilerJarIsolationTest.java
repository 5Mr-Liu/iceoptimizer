package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** The merged installation archive must remain independent of both late runtimes. */
public class FinalProfilerJarIsolationTest {
    @Test public void singleJarProvidesProfilerModMetadataAndItsThreeHookFamilies() throws Exception {
        File archive = archive();
        String version = System.getProperty("ice.profiler.version");
        assertNotNull("release version required", version);
        try (JarFile jar = new JarFile(archive)) {
            assertEquals("dev.rlcraft.ice.hooks.IceProfilerLoadingPlugin",
                jar.getManifest().getMainAttributes().getValue("FMLCorePlugin"));
            assertEquals("true", jar.getManifest().getMainAttributes().getValue("FMLCorePluginContainsFMLMod"));
            assertEquals(version, jar.getManifest().getMainAttributes().getValue("Implementation-Version"));
            assertNotNull(jar.getJarEntry("dev/rlcraft/ice/profiler/core/ProfilerRuntime.class"));
            ClassNode mod = node(read(jar.getInputStream(jar.getJarEntry("dev/rlcraft/ice/IceProfilerMod.class"))));
            boolean hasVersion = false;
            for (FieldNode field : mod.fields) if ("VERSION".equals(field.name)) {
                assertEquals(version, field.value);
                hasVersion = true;
            }
            assertTrue("Profiler version field retained", hasVersion);
            AnnotationNode metadata = null;
            for (AnnotationNode annotation : mod.visibleAnnotations) {
                if ("Lnet/minecraftforge/fml/common/Mod;".equals(annotation.desc)) metadata = annotation;
            }
            assertNotNull("ordinary Forge mod annotation retained", metadata);
            assertEquals("iceprofiler", annotationValue(metadata, "modid"));
            assertEquals(version, annotationValue(metadata, "version"));
            assertEquals("*", annotationValue(metadata, "acceptableRemoteVersions"));
            try (InputStreamReader info = new InputStreamReader(jar.getInputStream(jar.getJarEntry("mcmod.info")), "UTF-8")) {
                JsonArray mods = new JsonParser().parse(info).getAsJsonArray();
                assertEquals("one ordinary Profiler mod in the installation archive", 1, mods.size());
                assertEquals("iceprofiler", mods.get(0).getAsJsonObject().get("modid").getAsString());
                assertEquals(version, mods.get(0).getAsJsonObject().get("version").getAsString());
            }
            Set<String> hooks = hooks(jar);
            assertTrue(hooks.contains("dev.rlcraft.ice.hooks.IceProfilerLoadingPlugin"));
            assertTrue(hooks.contains("dev.rlcraft.ice.hooks.IceProfilerTransformer"));
            assertTrue(hooks.contains("dev.rlcraft.ice.hooks.ProbeProtocol"));
        }
    }

    @Test public void finalProfilerHooksInitializeAndTransformBeforeEitherRuntimeCanLoad() throws Exception {
        File archive = archive();
        Set<String> hooks;
        try (JarFile jar = new JarFile(archive)) { hooks = hooks(jar); }
        try (StartupLoader loader = new StartupLoader(archive.toURI().toURL(), getClass().getClassLoader(), hooks)) {
            Object plugin = loader.loadClass("dev.rlcraft.ice.hooks.IceProfilerLoadingPlugin").newInstance();
            String[] transformers = (String[]) plugin.getClass().getMethod("getASMTransformerClass").invoke(plugin);
            assertEquals(1, transformers.length);
            assertEquals("dev.rlcraft.ice.hooks.IceProfilerTransformer", transformers[0]);
            plugin.getClass().getMethod("injectData", Map.class).invoke(plugin, Collections.emptyMap());
            Object transformer = loader.loadClass(transformers[0]).newInstance();
            for (String hook : hooks) {
                Class<?> type = loader.loadClass(hook);
                assertSame("early hook must come from the final installation archive: " + hook,
                    loader, type.getClassLoader());
                type.getDeclaredMethods();
            }
            String world = "net.minecraft.world.World";
            byte[] original = read(getClass().getResourceAsStream("/net/minecraft/world/World.class"));
            byte[] transformed = (byte[]) transformer.getClass()
                .getMethod("transform", String.class, String.class, byte[].class)
                .invoke(transformer, world, world, original);
            int entries = 0;
            for (MethodNode method : node(transformed).methods) {
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (instruction instanceof MethodInsnNode) {
                        MethodInsnNode call = (MethodInsnNode) instruction;
                        if ("dev/rlcraft/ice/profiler/probe/ProbeBridge".equals(call.owner)
                            && "enter".equals(call.name)) entries++;
                    }
                }
            }
            assertEquals("actual World entity-tick hook is present once", 1, entries);
            assertEquals("startup and transformation must not resolve ProfilerRuntime, ProbeBridge, or Optimizer", 0, loader.lateLoads);
        }
    }

    private static File archive() {
        String path = System.getProperty("ice.profiler.finalJar");
        Assume.assumeNotNull(path);
        File archive = new File(path);
        assertTrue("final profiler installation archive must exist", archive.isFile());
        return archive;
    }

    private static Set<String> hooks(JarFile jar) {
        Set<String> hooks = new HashSet<String>();
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (!name.startsWith("dev/rlcraft/ice/hooks/") || !name.endsWith(".class")) continue;
            String type = name.substring(0, name.length() - 6).replace('/', '.');
            assertTrue("only the three standalone Profiler hook families belong in this archive: " + type,
                type.startsWith("dev.rlcraft.ice.hooks.IceProfilerLoadingPlugin")
                || type.startsWith("dev.rlcraft.ice.hooks.IceProfilerTransformer")
                || type.startsWith("dev.rlcraft.ice.hooks.ProbeProtocol"));
            hooks.add(type);
        }
        return hooks;
    }

    private static Object annotationValue(AnnotationNode annotation, String name) {
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (name.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] read(InputStream input) throws Exception {
        assertNotNull(input);
        try (InputStream stream = input) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int count; (count = stream.read(buffer)) != -1;) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static final class StartupLoader extends URLClassLoader {
        private final Set<String> hooks;
        private int lateLoads;
        StartupLoader(URL archive, ClassLoader parent, Set<String> hooks) {
            super(new URL[] {archive}, parent);
            this.hooks = hooks;
        }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (hooks.contains(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null) type = findClass(name);
                if (resolve) resolveClass(type);
                return type;
            }
            if (name.startsWith("dev.rlcraft.ice.")) {
                lateLoads++;
                throw new ClassNotFoundException("Profiler and Optimizer runtimes intentionally hidden: " + name);
            }
            return super.loadClass(name, resolve);
        }
    }
}
