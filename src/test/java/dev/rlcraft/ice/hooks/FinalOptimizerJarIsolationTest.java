package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.compat.save.PendingTickAccessor;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

/** Exercises the final installation JAR while every late runtime class is hidden. */
public class FinalOptimizerJarIsolationTest {
    @Test
    public void profilerSingleJarRetainsExceptionFallbackWhenOptimizerIsAbsent() throws Exception {
        String path = System.getProperty("ice.profiler.finalJar");
        Assume.assumeNotNull(path);
        try (URLClassLoader loader = new URLClassLoader(new URL[] { new File(path).toURI().toURL() },
            getClass().getClassLoader()) {
            @Override protected synchronized Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
                if (name.startsWith("dev.rlcraft.ice.hooks.IceProfiler")
                    || name.startsWith("dev.rlcraft.ice.hooks.ProbeProtocol")) {
                    Class<?> type = findLoadedClass(name);
                    if (type == null) type = findClass(name);
                    if (resolve) resolveClass(type);
                    return type;
                }
                if (name.startsWith("dev.rlcraft.ice.")) {
                    throw new ClassNotFoundException("optimizer and late runtime intentionally hidden: " + name);
                }
                return super.loadClass(name, resolve);
            }
        }) {
            Class<?> type = loader.loadClass("dev.rlcraft.ice.hooks.IceProfilerTransformer");
            Object transformer = type.newInstance();
            byte[] invalid = { 1, 2, 3 };
            assertTrue("a profiler exception must preserve the original bytecode without optimizer helpers",
                invalid == type.getMethod("transform", String.class, String.class, byte[].class)
                    .invoke(transformer, "test.Invalid", "test.Invalid", invalid));
        }
    }

    @Test
    public void finalJarProvidesForgeMetadataPluginTransformerAndAllEarlyAbi() throws Exception {
        String path = System.getProperty("ice.optimizer.finalJar");
        Assume.assumeNotNull(path);
        File archive = new File(path);
        String version = System.getProperty("ice.optimizer.version");
        Set<String> earlyClasses = earlyClasses();
        try (JarFile jar = new JarFile(archive)) {
            assertEquals("dev.rlcraft.ice.hooks.IceOptimizerLoadingPlugin",
                jar.getManifest().getMainAttributes().getValue("FMLCorePlugin"));
            assertEquals("true", jar.getManifest().getMainAttributes().getValue("FMLCorePluginContainsFMLMod"));
            assertEquals(version, jar.getManifest().getMainAttributes().getValue("Implementation-Version"));
            ClassNode mod = new ClassNode();
            new ClassReader(jar.getInputStream(jar.getJarEntry("dev/rlcraft/ice/IceMod.class"))).accept(mod, 0);
            boolean foundVersion = false;
            for (FieldNode field : mod.fields) {
                if ("VERSION".equals(field.name)) {
                    assertEquals(version, field.value);
                    foundVersion = true;
                }
            }
            assertTrue(foundVersion);
            AnnotationNode metadata = null;
            for (AnnotationNode annotation : mod.visibleAnnotations) {
                if ("Lnet/minecraftforge/fml/common/Mod;".equals(annotation.desc)) metadata = annotation;
            }
            assertNotNull(metadata);
            assertEquals("iceoptimizer", annotationValue(metadata, "modid"));
            assertEquals(version, annotationValue(metadata, "version"));
            assertEquals("[" + version + "]", annotationValue(metadata, "acceptableRemoteVersions"));
        }
        try (IsolatedStartupLoader loader = new IsolatedStartupLoader(
            archive.toURI().toURL(), getClass().getClassLoader(), earlyClasses)) {
            Class<?> journal = loader.loadClass("dev.rlcraft.ice.hooks.OptimizerPatchJournal");
            assertFalse(Boolean.TRUE.equals(journal.getMethod("isStartupRegistered").invoke(null)));
            Object plugin = loader.loadClass("dev.rlcraft.ice.hooks.IceOptimizerLoadingPlugin").newInstance();
            String[] transformers = (String[]) plugin.getClass().getMethod("getASMTransformerClass").invoke(plugin);
            assertEquals(1, transformers.length);
            plugin.getClass().getMethod("injectData", Map.class).invoke(plugin, Collections.emptyMap());
            Object transformer = loader.loadClass(transformers[0]).newInstance();
            assertEquals(loader, transformer.getClass().getClassLoader());
            assertTrue(Boolean.TRUE.equals(journal.getMethod("isStartupRegistered").invoke(null)));
            for (String name : earlyClasses) {
                Class<?> abi = loader.loadClass(name);
                assertEquals(name + " must come from the final JAR", loader, abi.getClassLoader());
                abi.getDeclaredMethods();
            }
            byte[] untouched = { 1, 2, 3 };
            assertTrue(untouched == transformer.getClass().getMethod("transform", String.class, String.class, byte[].class)
                .invoke(transformer, "test.Unrelated", "test.Unrelated", untouched));
            assertEquals("startup must not link a late runtime class", 0, loader.lateLoads);
        }
    }

    private static Object annotationValue(AnnotationNode annotation, String name) {
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (name.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static Set<String> earlyClasses() throws Exception {
        Path root = new File(PendingTickAccessor.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toPath();
        Set<String> names = new HashSet<String>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(file -> file.toString().endsWith(".class")).forEach(file -> {
                String relative = root.relativize(file).toString().replace(File.separatorChar, '.');
                names.add(relative.substring(0, relative.length() - ".class".length()));
            });
        }
        assertTrue("the complete early source set is required", names.size() >= 24);
        return names;
    }

    private static final class IsolatedStartupLoader extends URLClassLoader {
        private final Set<String> earlyClasses;
        private int lateLoads;

        private IsolatedStartupLoader(URL archive, ClassLoader parent, Set<String> earlyClasses) {
            super(new URL[] { archive }, parent);
            this.earlyClasses = earlyClasses;
        }

        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("dev.rlcraft.ice.hooks.") || earlyClasses.contains(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null) type = findClass(name);
                if (resolve) resolveClass(type);
                return type;
            }
            if (name.startsWith("dev.rlcraft.ice.")) {
                lateLoads++;
                throw new ClassNotFoundException("late runtime intentionally hidden: " + name);
            }
            return super.loadClass(name, resolve);
        }
    }
}
