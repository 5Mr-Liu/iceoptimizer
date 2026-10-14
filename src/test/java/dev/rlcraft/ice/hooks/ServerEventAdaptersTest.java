package dev.rlcraft.ice.hooks;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Collections;
import java.util.jar.JarFile;
import java.util.ArrayList;
import java.net.URL;
import java.net.URLClassLoader;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

public class ServerEventAdaptersTest {
    @Test public void actualJvmVerifiesReviewedOptionalClasses() throws Exception {
        verifyJvm("librarianlib-1.12.2-4.22.jar", LibrarianCapabilityAdapter.TARGET, new LibrarianCapabilityAdapter());
        verifyJvm("Level Up! 2-1.1.23-1.12.jar", LevelUpProcessorSnapshotAdapter.TARGET, new LevelUpProcessorSnapshotAdapter());
        verifyJvm("nuclearcraft-1.12.2-2.19a.jar", NuclearRadiationSnapshotAdapter.TARGET, new NuclearRadiationSnapshotAdapter());
        verifyJvm("FishingMadeBetter-1.12.2-2.2.7.jar", FishingBiomeAdapter.TARGET, new FishingBiomeAdapter());
        verifyJvm("fermiumasm-5.28.jar", NormalStringConstantAdapter.TARGET, new NormalStringConstantAdapter());
    }
    private static void verifyJvm(String jarName, final String target, OptimizerBytecodeAdapter adapter) throws Exception {
        final byte[] bytes = adapter.transform(target.replace('/', '.'), read(jarName, target),
            new TargetSpec("test", "test", "test", Collections.<String>emptySet()));
        ArrayList<URL> urls = new ArrayList<URL>();
        for (String path : System.getProperty("java.class.path").split(File.pathSeparator)) urls.add(new File(path).toURI().toURL());
        for (File file : new File(System.getProperty("ice.server.mods")).listFiles()) if (file.getName().endsWith(".jar")) urls.add(file.toURI().toURL());
        URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[urls.size()]), null) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (target.replace('/', '.').equals(name)) return defineClass(name, bytes, 0, bytes.length);
                return super.findClass(name);
            }
        };
        try { loader.loadClass(target.replace('/', '.')).getDeclaredMethods(); }
        finally { loader.close(); }
    }
    @Test public void reviewedRealClassesKeepOriginalsAndVerifyFastMethods() throws Exception {
        check("librarianlib-1.12.2-4.22.jar", LibrarianCapabilityAdapter.TARGET,
            new LibrarianCapabilityAdapter(), "hasCapability", "getInterfaces", "intern");
        check("Level Up! 2-1.1.23-1.12.jar", LevelUpProcessorSnapshotAdapter.TARGET,
            new LevelUpProcessorSnapshotAdapter(), "doFurnaceTicks", "processors", "collect");
        check("nuclearcraft-1.12.2-2.19a.jar", NuclearRadiationSnapshotAdapter.TARGET,
            new NuclearRadiationSnapshotAdapter(), "updateWorldRadiation", "snapshot", "toArray");
        check("FishingMadeBetter-1.12.2-2.2.7.jar", FishingBiomeAdapter.TARGET,
            new FishingBiomeAdapter(), "isChunkBiomeInBiomeList", "matches", "func_177411_a");
    }
    @Test public void lycanitesPassesContextOnlyToThePrivateExactImplementation() throws Exception {
        byte[] bytes = read("lycanitesmobs-1.12.2-2.0.8.10.jar", LycanitesSpawnScanAdapter.TARGET);
        byte[] transformed = new LycanitesSpawnScanAdapter().transform(LycanitesSpawnScanAdapter.TARGET,
            bytes, new TargetSpec("test", "test", "test", Collections.<String>emptySet()));
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(transformed).accept(node, ClassReader.EXPAND_FRAMES);
        MethodNode fast = ServerModAdapterSupport.require(node, LycanitesSpawnScanAdapter.FAST_SCAN,
            LycanitesSpawnScanAdapter.FAST_SCAN_DESCRIPTOR);
        MethodNode valid = ServerModAdapterSupport.require(node, LycanitesSpawnScanAdapter.VALID_METHOD,
            LycanitesSpawnScanAdapter.VALID_DESCRIPTOR);
        assertEquals(1, calls(fast, "scanState"));
        assertEquals(1, calls(valid, "func_180495_p"));
        assertEquals(0, calls(valid, "validationState"));
        for (MethodNode method : node.methods) new Analyzer(new BasicVerifier()).analyze(node.name, method);
    }
    @Test public void normalStringPoolKeepsOneCounterIncrementAndOwnerCache() throws Exception {
        byte[] bytes = read("fermiumasm-5.28.jar", NormalStringConstantAdapter.TARGET);
        byte[] transformed = new NormalStringConstantAdapter().transform(NormalStringConstantAdapter.TARGET,
            bytes, new TargetSpec("test", "test", "test", Collections.<String>emptySet()));
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(transformed).accept(node, ClassReader.EXPAND_FRAMES);
        int counterWrites = 0;
        for (MethodNode method : node.methods) {
            new Analyzer(new BasicVerifier()).analyze(node.name, method);
            if ("addOrGet".equals(method.name)) for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof FieldInsnNode && instruction.getOpcode() == Opcodes.PUTFIELD
                    && "deduplicatedCount".equals(((FieldInsnNode) instruction).name)) counterWrites++;
            }
        }
        assertEquals(1, counterWrites);
    }
    private static void check(String jarName, String target, OptimizerBytecodeAdapter adapter,
                              String method, String retained, String removed) throws Exception {
        byte[] bytes = read(jarName, target);
        byte[] transformed = adapter.transform(target.replace('/', '.'), bytes,
            new TargetSpec(target.replace('/', '.'), "test", "test", Collections.<String>emptySet()));
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(transformed).accept(node, ClassReader.EXPAND_FRAMES);
        MethodNode fast = null, original = null, wrapper = null;
        for (MethodNode candidate : node.methods) {
            if (("ice$fast$" + method).equals(candidate.name)) fast = candidate;
            if (("ice$original$" + method).equals(candidate.name)) original = candidate;
            if (method.equals(candidate.name)) wrapper = candidate;
        }
        assertNotNull(fast); assertNotNull(original); assertNotNull(wrapper);
        assertTrue(calls(fast, retained) > 0);
        assertEquals(0, calls(fast, removed));
        assertTrue(calls(original, removed) > 0);
        new Analyzer(new BasicVerifier()).analyze(node.name, fast);
        new Analyzer(new BasicVerifier()).analyze(node.name, wrapper);
        if ("updateWorldRadiation".equals(method)) {
            assertEquals(3, calls(fast, "snapshot"));
            assertEquals(calls(original, "nextInt"), calls(fast, "nextInt"));
        }
    }
    private static byte[] read(String jarName, String target) throws Exception {
        String directory = System.getProperty("ice.server.mods", "");
        Assume.assumeTrue(!directory.isEmpty());
        JarFile jar = new JarFile(new File(directory, jarName));
        try {
            InputStream input = jar.getInputStream(jar.getJarEntry(target + ".class"));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) >= 0) out.write(buffer, 0, count);
            input.close(); return out.toByteArray();
        } finally { jar.close(); }
    }
    private static int calls(MethodNode method, String name) {
        int n = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray())
            if (instruction instanceof MethodInsnNode && name.equals(((MethodInsnNode) instruction).name)) n++;
        return n;
    }
}
