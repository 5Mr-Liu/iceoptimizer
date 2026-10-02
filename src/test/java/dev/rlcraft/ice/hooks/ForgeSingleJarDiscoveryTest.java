package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.compat.save.PendingTickAccessor;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraftforge.fml.common.asm.ASMTransformerWrapper;
import net.minecraftforge.fml.relauncher.CoreModManager;
import net.minecraftforge.fml.relauncher.FMLInjectionData;
import net.minecraftforge.fml.relauncher.FMLLaunchHandler;
import net.minecraftforge.fml.relauncher.Side;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Real Forge discovery and wrapper registration, without launching Minecraft. */
public class ForgeSingleJarDiscoveryTest {
    @Test public void clientDiscoversOptimizerAlone() throws Exception { verifyDiscovery(Side.CLIENT, true, false); }
    @Test public void serverDiscoversOptimizerAlone() throws Exception { verifyDiscovery(Side.SERVER, true, false); }
    @Test public void clientDiscoversProfilerAlone() throws Exception { verifyDiscovery(Side.CLIENT, false, true); }
    @Test public void serverDiscoversProfilerAlone() throws Exception { verifyDiscovery(Side.SERVER, false, true); }
    @Test public void clientDiscoversOptimizerWithProfiler() throws Exception { verifyDiscovery(Side.CLIENT, true, true); }
    @Test public void serverDiscoversOptimizerWithProfiler() throws Exception { verifyDiscovery(Side.SERVER, true, true); }

    private void verifyDiscovery(Side side, boolean includeOptimizer, boolean includeProfiler) throws Exception {
        String optimizerPath = System.getProperty("ice.optimizer.finalJar");
        String profilerPath = System.getProperty("ice.profiler.finalJar");
        if (includeOptimizer) Assume.assumeNotNull(optimizerPath);
        if (includeProfiler) Assume.assumeNotNull(profilerPath);
        List<File> installation = new ArrayList<File>();
        if (includeOptimizer) installation.add(new File(optimizerPath));
        if (includeProfiler) installation.add(new File(profilerPath));
        File fixtureRoot = new File("build/forge-startup-fixtures");
        Files.createDirectories(fixtureRoot.toPath());
        File game = Files.createTempDirectory(fixtureRoot.toPath(), side.name().toLowerCase() + "-").toFile();
        Path mods = new File(game, "mods").toPath();
        Files.createDirectories(mods);
        for (File archive : installation) Files.copy(archive.toPath(), mods.resolve(archive.getName()));
        try (StartupGlobals globals = new StartupGlobals();
             FixtureLaunchClassLoader loader = new FixtureLaunchClassLoader(earlyClasses())) {
            globals.replace(CoreModManager.class, "mcDir", game);
            globals.replace(CoreModManager.class, "loadPlugins", new ArrayList<Object>());
            globals.replace(CoreModManager.class, "ignoredModFiles", new ArrayList<String>());
            globals.replace(CoreModManager.class, "candidateModFiles", new ArrayList<String>());
            globals.replace(CoreModManager.class, "accessTransformers", new ArrayList<String>());
            globals.replace(CoreModManager.class, "transformers", new HashMap<String, List<String>>());
            globals.replace(CoreModManager.class, "deobfuscatedEnvironment", false);
            globals.replace(FMLInjectionData.class, "mccversion", "1.12.2");
            globals.replace(FMLLaunchHandler.class, "side", side);
            globals.replace(ASMTransformerWrapper.class, "injected", false);
            globals.clearMap(ASMTransformerWrapper.class, "wrapperModMap");
            globals.clearMap(ASMTransformerWrapper.class, "wrapperParentMap");
            globals.clearMap(Class.forName("net.minecraftforge.fml.relauncher.libraries.ModList"), "cache");
            Map<String, Object> blackboard = new HashMap<String, Object>();
            blackboard.put("forgeLaunchArgs", new HashMap<String, String>());
            globals.replace(Launch.class, "blackboard", blackboard);
            globals.replace(Launch.class, "classLoader", loader);

            Method discover = CoreModManager.class.getDeclaredMethod("discoverCoreMods", File.class, LaunchClassLoader.class);
            discover.setAccessible(true);
            discover.invoke(null, game, loader);
            for (File archive : installation) {
                assertTrue("the same startup JAR must remain eligible for ordinary @Mod scanning: " + archive.getName(),
                    CoreModManager.getReparseableCoremods().contains(archive.getName()));
                assertFalse(CoreModManager.getIgnoredMods().contains(archive.getName()));
            }
            assertEquals(installation.size(), plugins().size());
            for (Object plugin : plugins()) {
                // Calls the actual Forge wrapper, including ASMTransformerWrapper and registerTransformer.
                ((ITweaker) plugin).injectIntoClassLoader(loader);
            }
            assertEquals(installation.size(), loader.getTransformers().size());
            int optimizerRegistered = 0;
            int profilerRegistered = 0;
            for (IClassTransformer transformer : loader.getTransformers()) {
                if (transformer.toString().contains("IceOptimizerTransformer")) optimizerRegistered++;
                if (transformer.toString().contains("IceProfilerTransformer")) profilerRegistered++;
            }
            assertEquals("optimizer transformer registered once", includeOptimizer ? 1 : 0, optimizerRegistered);
            assertEquals("profiler transformer registered once", includeProfiler ? 1 : 0, profilerRegistered);
            if (includeProfiler) {
                assertSingleProfilerProbe(loader);
                Class<?> protocol = loader.loadClass("dev.rlcraft.ice.hooks.ProbeProtocol");
                assertEquals("profiler ABI must come from installed JAR", loader, protocol.getClassLoader());
            }
            if (includeOptimizer) {
                Class<?> journal = loader.loadClass("dev.rlcraft.ice.hooks.OptimizerPatchJournal");
                assertEquals(loader, journal.getClassLoader());
                assertTrue(Boolean.TRUE.equals(journal.getMethod("isStartupRegistered").invoke(null)));
            }
            assertEquals("Forge startup must not load optimizer/profiler runtime classes", 0, loader.lateLoads);
        }
    }

    private void assertSingleProfilerProbe(LaunchClassLoader loader) throws Exception {
        byte[] bytes;
        try (InputStream input = getClass().getResourceAsStream("/net/minecraft/world/World.class")) {
            assertTrue("actual mapped World fixture required", input != null);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
            bytes = output.toByteArray();
        }
        for (IClassTransformer transformer : loader.getTransformers()) {
            bytes = transformer.transform("net.minecraft.world.World", "net.minecraft.world.World", bytes);
        }
        ClassNode world = new ClassNode();
        new ClassReader(bytes).accept(world, 0);
        int entries = 0;
        for (MethodNode method : world.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if ("dev/rlcraft/ice/profiler/probe/ProbeBridge".equals(call.owner)
                        && "enter".equals(call.name)) entries++;
                }
            }
        }
        assertEquals("one registered profiler must install exactly one entity-tick entry probe", 1, entries);
    }

    private static List<?> plugins() throws Exception {
        Field field = CoreModManager.class.getDeclaredField("loadPlugins");
        field.setAccessible(true);
        return (List<?>) field.get(null);
    }

    private static Set<String> earlyClasses() throws Exception {
        Path root = Paths.get(PendingTickAccessor.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Set<String> names = new HashSet<String>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(file -> file.toString().endsWith(".class")).forEach(file -> {
                String relative = root.relativize(file).toString().replace(File.separatorChar, '.');
                names.add(relative.substring(0, relative.length() - 6));
            });
        }
        return names;
    }

    private static final class FixtureLaunchClassLoader extends LaunchClassLoader {
        private final Set<String> earlyClasses;
        private int lateLoads;

        private FixtureLaunchClassLoader(Set<String> earlyClasses) {
            super(new URL[0]);
            this.earlyClasses = earlyClasses;
            // These are Forge's shared launcher/library types. ICE classes stay child-loaded.
            for (String prefix : new String[] { "net.minecraftforge.", "net.minecraft.", "org.objectweb.asm.",
                "com.google.common.", "com.google.gson.", "org.apache.commons.", "org.apache.maven.",
                "javax.annotation." }) addClassLoaderExclusion(prefix);
        }

        @Override public Class<?> findClass(String name) throws ClassNotFoundException {
            if (name.startsWith("dev.rlcraft.ice.") && !name.startsWith("dev.rlcraft.ice.hooks.")
                && !earlyClasses.contains(name)) {
                lateLoads++;
                throw new ClassNotFoundException("late runtime intentionally hidden: " + name);
            }
            return super.findClass(name);
        }
    }

    private static final class StartupGlobals implements AutoCloseable {
        private final List<Field> fields = new ArrayList<Field>();
        private final List<Object> values = new ArrayList<Object>();
        private final List<Map<Object, Object>> maps = new ArrayList<Map<Object, Object>>();
        private final List<Map<Object, Object>> mapValues = new ArrayList<Map<Object, Object>>();

        private void replace(Class<?> owner, String name, Object value) throws Exception {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            fields.add(field);
            values.add(field.get(null));
            field.set(null, value);
        }

        @SuppressWarnings("unchecked")
        private void clearMap(Class<?> owner, String name) throws Exception {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            Map<Object, Object> map = (Map<Object, Object>) field.get(null);
            maps.add(map);
            mapValues.add(new HashMap<Object, Object>(map));
            map.clear();
        }

        @Override public void close() throws Exception {
            for (int i = maps.size() - 1; i >= 0; i--) {
                maps.get(i).clear();
                maps.get(i).putAll(mapValues.get(i));
            }
            for (int i = fields.size() - 1; i >= 0; i--) fields.get(i).set(null, values.get(i));
        }
    }
}
