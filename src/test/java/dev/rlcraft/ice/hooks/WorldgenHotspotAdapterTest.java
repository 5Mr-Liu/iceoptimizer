package dev.rlcraft.ice.hooks;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import dev.rlcraft.ice.optimizer.compat.otg.OtgBo4ColumnAccess;
import dev.rlcraft.ice.optimizer.compat.otg.OtgBo4ColumnOrder;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import java.util.jar.*;
import org.junit.*;
import static org.junit.Assert.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Exercises the actual OTG 9.7 and Recurrent Complex 1.4.8.6 bytecode. */
public class WorldgenHotspotAdapterTest {
    private boolean paused;
    @Before public void enable() {
        paused = OptimizerRegistry.isRuntimePaused();
        OptimizerRegistry.setRuntimePaused(false);
        for (OptimizationModule module : modules()) {
            OptimizerRegistry.breaker(module).configure(true, 3);
            OptimizerRegistry.breaker(module).patchInstalled("worldgen", "test");
        }
    }
    @After public void restore() {
        for (OptimizationModule module : modules()) OptimizerRegistry.breaker(module).configure(false, 3);
        OptimizerRegistry.setRuntimePaused(paused);
    }
    private static OptimizationModule[] modules() {
        return new OptimizationModule[] { OptimizationModule.OTG_BO4_LAYOUT, OptimizationModule.OTG_BIOME_ARRAY_CLEAR,
            OptimizationModule.RECURRENT_CLOUD_FIELD };
    }

    @Test public void columnDistributionUsesLinearListAndCoordinateWork() {
        final int[] reads = { 0 }, coordinates = { 0 };
        List<Integer> blocks = new AbstractList<Integer>() {
            @Override public Integer get(int index) { reads[0]++; return index; }
            @Override public int size() { return 32768; }
        };
        OtgBo4ColumnAccess access = new OtgBo4ColumnAccess() {
            @Override public int ice$column(Object block) { coordinates[0]++; return ((Integer) block) & 255; }
        };
        Object[] output = new Object[blocks.size()];
        assertTrue(OtgBo4ColumnOrder.order(access, blocks, output));
        assertEquals(blocks.size(), coordinates[0]);
        assertEquals(blocks.size() * 2, reads[0]);
        for (int column = 0; column < 256; column++) for (int row = 0; row < 128; row++)
            assertEquals(column + row * 256, ((Integer) output[column * 128 + row]).intValue());
    }

    @Test public void realBo4GroupingIsStableAndRetainsInvalidCoordinateNullTails() throws Exception {
        File jar = jar("ice.otg.jar");
        String name = "com.pg85.otg.customobjects.bo4.BO4Config";
        byte[] original = read(jar, name);
        byte[] changed = new OtgBo4ColumnOrderAdapter().transform(name, original, null);
        assertUnchangedExcept(original, changed, "readResources", "ice$column");
        try (ChildLoader loader = new ChildLoader(jar, name, changed)) {
            Class<?> config = loader.loadClass(name);
            config.getDeclaredMethods(); // Force verifier, including the changed large method.
            Field singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) singleton.get(null);
            OtgBo4ColumnAccess access = (OtgBo4ColumnAccess) unsafe.allocateInstance(config);
            Class<?> block = loader.loadClass(OtgBo4ColumnOrderAdapter.BLOCK.replace('/', '.'));
            Field x = block.getField("x"), z = block.getField("z");
            Random random = new Random(928327L);
            for (int size : new int[] { 0, 1, 31, 2048, 32768 }) {
                List<Object> source = new ArrayList<Object>();
                for (int i = 0; i < size; i++) {
                    Object value = unsafe.allocateInstance(block);
                    x.setInt(value, random.nextInt(18) - 1);
                    z.setInt(value, random.nextInt(18) - 1);
                    source.add(value);
                }
                Object[] expected = new Object[size], actual = new Object[size];
                int at = 0;
                for (int cx = 0; cx < 16; cx++) for (int cz = 0; cz < 16; cz++)
                    for (Object value : source) if (x.getInt(value) == cx && z.getInt(value) == cz) expected[at++] = value;
                assertTrue(OtgBo4ColumnOrder.order(access, source, actual));
                assertArrayEquals(expected, actual);
                OptimizerRegistry.setRuntimePaused(true);
                Object[] disabled = new Object[size];
                assertFalse(OtgBo4ColumnOrder.order(access, source, disabled));
                assertArrayEquals(new Object[size], disabled);
                OptimizerRegistry.setRuntimePaused(false);
            }
        }
    }

    @Test public void fullOptimizerChainKeepsBothBo4ColumnOptimizations() throws Exception {
        File jar = jar("ice.otg.jar");
        String name = "com.pg85.otg.customobjects.bo4.BO4Config";
        byte[] original = read(jar, name);
        byte[] changed = new IceOptimizerTransformer().transform(name, name, original);
        int offsets = 0, grouping = 0;
        for (MethodNode method : node(changed).methods) for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.owner.equals(OtgBo4ConfigAdapter.BRIDGE_OWNER) && call.name.equals("columnBlockIndex")) offsets++;
            if (call.owner.equals(OtgBo4ColumnOrderAdapter.BRIDGE) && call.name.equals("order")) grouping++;
        }
        assertEquals(1, offsets); assertEquals(1, grouping);
        try (ChildLoader loader = new ChildLoader(jar, name, changed)) {
            loader.loadClass(name).getDeclaredMethods();
        }
    }

    @Test public void realArrayPoolPreservesArraysAcrossLargeSmallRequestsAndPauseTransitions() throws Exception {
        File jar = jar("ice.otg.jar");
        String name = "com.pg85.otg.generator.biome.ArraysCache";
        byte[] original = read(jar, name);
        byte[] changed = new OtgArrayClearAdapter().transform(name, original, null);
        try (ChildLoader loader = new ChildLoader(jar, name, changed)) {
            Class<?> type = loader.loadClass(name);
            Object pool = type.newInstance();
            Method get = type.getMethod("getArray", int.class), release = type.getDeclaredMethod("release");
            release.setAccessible(true);
            Random random = new Random(523785L);
            for (int round = 0; round < 60; round++) {
                List<int[]> borrowed = new ArrayList<int[]>();
                for (int group = 0; group < 4; group++) for (int i = 0; i < 24; i++) {
                    int length = group == 0 ? 1 + random.nextInt(256) : group == 1 ? 257 + random.nextInt(256)
                        : group == 2 ? 513 + random.nextInt(512) : round == 0 ? 100000 + i * 5 : 1025 + random.nextInt(2048);
                    int[] array = (int[]) get.invoke(pool, length);
                    for (int value : array) assertEquals(0, value);
                    Arrays.fill(array, 0, length, round + 1);
                    borrowed.add(array);
                }
                OptimizerRegistry.setRuntimePaused(round % 3 == 1);
                release.invoke(pool);
                for (int[] array : borrowed) for (int value : array) assertEquals(0, value);
                OptimizerRegistry.setRuntimePaused(false);
            }
        }
    }

    @Test public void actualBiomeLayerChainsMatchAcrossSeedsCoordinatesAndPoolReuse() throws Exception {
        File jar = jar("ice.otg.jar");
        String name = "com.pg85.otg.generator.biome.ArraysCache";
        byte[] changed = new OtgArrayClearAdapter().transform(name, read(jar, name), null);
        try (ChildLoader original = new ChildLoader(jar, null, null); ChildLoader optimized = new ChildLoader(jar, name, changed)) {
            for (long seed : new long[] { 0, 1, -1, Long.MIN_VALUE, 123918L }) {
                LayerRunner expected = new LayerRunner(original, seed), actual = new LayerRunner(optimized, seed);
                Random random = new Random(seed);
                for (int sample = 0; sample < 35; sample++) {
                    int x = random.nextInt(10000) - 5000, z = random.nextInt(10000) - 5000;
                    int width = sample == 0 ? 257 : 1 + random.nextInt(80);
                    int height = sample == 0 ? 129 : 1 + random.nextInt(80);
                    OptimizerRegistry.setRuntimePaused(sample % 7 == 0);
                    assertArrayEquals("seed=" + seed + " sample=" + sample,
                        expected.generate(x, z, width, height), actual.generate(x, z, width, height));
                }
            }
        }
    }

    @Test public void recurrentAdapterOnlyChangesFieldConstructionAndOneCoordinateQuery() throws Exception {
        File jar = jar("ice.recurrent.jar");
        String name = "ivorius.reccomplex.world.gen.feature.structure.generic.transformers.TransformerAbstractCloud";
        byte[] original = read(jar, name), changed = new RecurrentCloudFieldAdapter().transform(name, original, null);
        assertUnchangedExcept(original, changed, "buildCloud", "lambda$buildCloud$1");
        ClassNode n = node(changed);
        int creates = 0, query = 0, randomAdds = 0, allocations = 0;
        for (MethodNode m : n.methods) for (AbstractInsnNode i : m.instructions.toArray()) {
            if (m.name.equals("lambda$buildCloud$1") && i.getOpcode() == Opcodes.NEWARRAY) allocations++;
            if (i instanceof TypeInsnNode && i.getOpcode() == Opcodes.NEW && ((TypeInsnNode)i).desc.equals(RecurrentCloudFieldAdapter.CACHED)) creates++;
            if (i instanceof MethodInsnNode) {
                MethodInsnNode c = (MethodInsnNode)i;
                if (c.name.equals("ice$getCloudValue") && c.desc.equals("(III)D")) query++;
                if (c.owner.equals(RecurrentCloudFieldAdapter.FIELD) && c.name.equals("addValue")) randomAdds++;
            }
        }
        assertEquals(1, creates); assertEquals(1, query); assertEquals(1, randomAdds); assertEquals(0, allocations);
        try (ChildLoader loader = new ChildLoader(jar, name, changed)) {
            loader.loadClass(name).getDeclaredMethods();
        }
    }

    private static final class LayerRunner {
        final Object layer, cache; final Method get, release;
        LayerRunner(ClassLoader loader, long seed) throws Exception {
            Class<?> base = loader.loadClass("com.pg85.otg.generator.biome.layers.Layer");
            Class<?> cacheType = loader.loadClass("com.pg85.otg.generator.biome.ArraysCache");
            Object chain = construct(loader, "LayerEmpty", new Class<?>[] { long.class, int.class }, 1L, 0);
            chain = construct(loader, "LayerLand", new Class<?>[] { long.class, int.class, base, int.class }, 2L, 0, chain, 79);
            for (String stage : new String[] { "LayerZoom", "LayerSmooth", "LayerZoom", "LayerZoomVoronoi" })
                chain = construct(loader, stage, new Class<?>[] { long.class, int.class, base }, 33L, 0, chain);
            base.getMethod("initWorldGenSeed", long.class).invoke(chain, seed);
            layer = chain; cache = cacheType.newInstance();
            get = base.getMethod("getInts", loader.loadClass("com.pg85.otg.common.LocalWorld"), cacheType,
                int.class, int.class, int.class, int.class);
            release = cacheType.getDeclaredMethod("release"); release.setAccessible(true);
        }
        int[] generate(int x, int z, int width, int height) throws Exception {
            int[] data = (int[]) get.invoke(layer, null, cache, x, z, width, height);
            int[] result = Arrays.copyOf(data, width * height); release.invoke(cache); return result;
        }
        private static Object construct(ClassLoader loader, String simple, Class<?>[] args, Object... values) throws Exception {
            Constructor<?> ctor = loader.loadClass("com.pg85.otg.generator.biome.layers." + simple).getDeclaredConstructor(args);
            ctor.setAccessible(true); return ctor.newInstance(values);
        }
    }

    private static File jar(String property) {
        String value = System.getProperty(property, "");
        Assume.assumeFalse("Missing " + property, value.isEmpty());
        File result = new File(value);
        assertTrue("Invalid " + property + "=" + value, result.isFile()); return result;
    }
    private static byte[] read(File path, String name) throws IOException {
        try (JarFile jar = new JarFile(path); InputStream in = jar.getInputStream(jar.getJarEntry(name.replace('.', '/') + ".class"))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) >= 0) out.write(b, 0, n); return out.toByteArray();
        }
    }
    private static ClassNode node(byte[] bytes) {
        ClassNode result = new ClassNode(Opcodes.ASM5); new ClassReader(bytes).accept(result, 0); return result;
    }
    private static void assertUnchangedExcept(byte[] original, byte[] changed, String... allowed) {
        ClassNode before = node(original), after = node(changed); Set<String> names = new HashSet<String>(Arrays.asList(allowed));
        for (MethodNode method : before.methods) {
            if (names.contains(method.name)) continue;
            MethodNode found = null;
            for (MethodNode candidate : after.methods) if (candidate.name.equals(method.name) && candidate.desc.equals(method.desc)) found = candidate;
            assertNotNull(method.name, found);
            assertEquals(method.name, text(method), text(found));
        }
    }
    private static String text(MethodNode method) {
        org.objectweb.asm.util.Textifier text = new org.objectweb.asm.util.Textifier();
        method.accept(new org.objectweb.asm.util.TraceMethodVisitor(text));
        StringWriter output = new StringWriter(); text.print(new PrintWriter(output)); return output.toString();
    }
    private static final class ChildLoader extends URLClassLoader {
        final String replacement; final byte[] bytes;
        ChildLoader(File jar, String replacement, byte[] bytes) throws MalformedURLException {
            super(new URL[] { jar.toURI().toURL() }, WorldgenHotspotAdapterTest.class.getClassLoader());
            this.replacement = replacement; this.bytes = bytes;
        }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("com.pg85.otg.") && !name.startsWith("ivorius.reccomplex.")) return super.loadClass(name, resolve);
            Class<?> found = findLoadedClass(name);
            if (found == null) found = name.equals(replacement) ? defineClass(name, bytes, 0, bytes.length) : findClass(name);
            if (resolve) resolveClass(found); return found;
        }
    }
}
