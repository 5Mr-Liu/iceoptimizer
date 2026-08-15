package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.ClientOptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;

/** Behavioral equivalence and copy-isolation checks for the Better Caves primitive ABI. */
public class BetterCavesPrimitiveAdapterTest {
    private static final String TUPLE =
        "com.yungnickyoung.minecraft.bettercaves.noise.NoiseTuple";
    private static final String COLUMN =
        "com.yungnickyoung.minecraft.bettercaves.noise.NoiseColumn";

    @Test
    public void primitiveTupleAndColumnPreserveValuesAndDeepCopyBoundaries() throws Exception {
        String configured = System.getProperty("ice.bettercaves.jar", "").trim();
        Assume.assumeTrue("run with -PbetterCavesJar=<jar>", !configured.isEmpty());
        File file = new File(configured);
        Assume.assumeTrue(file.isFile());
        JarFile jar = new JarFile(file);
        URLClassLoader dependencies = new URLClassLoader(
            new URL[] { file.toURI().toURL() }, getClass().getClassLoader());
        boolean oldGlobal = OptimizerConfig.settings.enabled;
        boolean oldModule = OptimizerConfig.settings.betterCavesNoisePipeline;
        try {
            byte[] tupleBytes = transform(jar, TUPLE);
            byte[] columnBytes = transform(jar, COLUMN);
            ByteLoader loader = new ByteLoader(dependencies);
            Class<?> tupleType = loader.define(TUPLE, tupleBytes);
            Class<?> columnType = loader.define(COLUMN, columnBytes);
            enable(true);

            Constructor<?> tupleConstructor = tupleType.getConstructor(double[].class);
            Method get = tupleType.getMethod("get", Integer.TYPE);
            Method set = tupleType.getMethod("set", Integer.TYPE, Double.TYPE);
            Method put = tupleType.getMethod("put", Double.TYPE);
            Method size = tupleType.getMethod("size");
            Method values = tupleType.getMethod("getNoiseValues");
            Method times = tupleType.getMethod("times", Float.TYPE);
            Method blend = tupleType.getMethod(BetterCavesNoiseTupleAdapter.BLEND_METHOD,
                tupleType, Float.TYPE, tupleType, Float.TYPE);
            Field primitiveValues = tupleType.getDeclaredField(BetterCavesNoiseTupleAdapter.VALUES_FIELD);
            primitiveValues.setAccessible(true);

            Object first = tupleConstructor.newInstance((Object) new double[] { 1.25D, -2.5D });
            assertNotNull(primitiveValues.get(first));
            assertEquals(2, ((Integer) size.invoke(first)).intValue());
            assertBits(1.25D, ((Double) get.invoke(first, Integer.valueOf(0))).doubleValue());
            set.invoke(first, Integer.valueOf(1), Double.valueOf(3.75D));
            put.invoke(first, Double.valueOf(-4.5D));
            assertEquals(3, ((Integer) size.invoke(first)).intValue());
            @SuppressWarnings("unchecked")
            List<Double> view = (List<Double>) values.invoke(first);
            assertEquals(3, view.size());
            assertBits(-4.5D, view.get(2).doubleValue());
            view.set(0, Double.valueOf(9.0D));
            assertBits(9.0D, ((Double) get.invoke(first, Integer.valueOf(0))).doubleValue());

            Object scaled = times.invoke(first, Float.valueOf(0.25F));
            assertBits(9.0D * (double) 0.25F,
                ((Double) get.invoke(scaled, Integer.valueOf(0))).doubleValue());
            Object second = tupleConstructor.newInstance((Object) new double[] { 2.0D, 4.0D, 8.0D });
            Object fused = blend.invoke(null, first, Float.valueOf(0.25F),
                second, Float.valueOf(0.75F));
            for (int index = 0; index < 3; index++) {
                double expected = ((Double) get.invoke(first, Integer.valueOf(index))).doubleValue()
                    * (double) 0.25F
                    + ((Double) get.invoke(second, Integer.valueOf(index))).doubleValue()
                    * (double) 0.75F;
                assertBits(expected, ((Double) get.invoke(fused, Integer.valueOf(index))).doubleValue());
            }

            Constructor<?> columnConstructor = columnType.getConstructor();
            Method columnPut = columnType.getMethod("put", Integer.TYPE, tupleType);
            Method columnGet = columnType.getMethod("get", Integer.TYPE);
            Method columnCopy = columnType.getMethod(BetterCavesNoiseColumnAdapter.COPY_METHOD);
            Method columnValues = columnType.getMethod("getColumnValues");
            Field array = columnType.getDeclaredField(BetterCavesNoiseColumnAdapter.VALUES_FIELD);
            array.setAccessible(true);
            Field mapField = columnType.getDeclaredField("columnValues");
            mapField.setAccessible(true);

            Object column = columnConstructor.newInstance();
            assertNotNull(array.get(column));
            assertNull(mapField.get(column));
            columnPut.invoke(column, Integer.valueOf(10), first);
            columnPut.invoke(column, Integer.valueOf(11), second);
            assertSame(first, columnGet.invoke(column, Integer.valueOf(10)));
            Object copied = columnCopy.invoke(column);
            Object copiedFirst = columnGet.invoke(copied, Integer.valueOf(10));
            assertNotSame(first, copiedFirst);
            double beforeMutation = ((Double) get.invoke(copiedFirst, Integer.valueOf(0))).doubleValue();
            set.invoke(first, Integer.valueOf(0), Double.valueOf(123.0D));
            assertBits(beforeMutation,
                ((Double) get.invoke(copiedFirst, Integer.valueOf(0))).doubleValue());
            @SuppressWarnings("unchecked")
            Map<Integer, Object> materialized = (Map<Integer, Object>) columnValues.invoke(column);
            assertEquals(2, materialized.size());
            assertSame(first, materialized.get(Integer.valueOf(10)));
            assertNotNull(mapField.get(column));

            enable(false);
            Object disabledTuple = tupleConstructor.newInstance((Object) new double[] { 1.0D });
            assertNull(primitiveValues.get(disabledTuple));
            assertTrue(values.invoke(disabledTuple) instanceof ArrayList);
            Object disabledColumn = columnConstructor.newInstance();
            assertNull(array.get(disabledColumn));
            assertTrue(mapField.get(disabledColumn) instanceof java.util.HashMap);
        } finally {
            OptimizerConfig.settings.enabled = oldGlobal;
            OptimizerConfig.settings.betterCavesNoisePipeline = oldModule;
            OptimizerRegistry.configure(ClientOptimizerConfig.capture());
            dependencies.close();
            jar.close();
        }
    }

    private static void enable(boolean enabled) {
        OptimizerConfig.settings.enabled = true;
        OptimizerConfig.settings.betterCavesNoisePipeline = enabled;
        OptimizerRegistry.configure(ClientOptimizerConfig.capture());
        if (enabled) {
            OptimizerRegistry.targetObserved("better-caves-noise", TUPLE, repeat('a', 64), true);
            OptimizerRegistry.patchInstalled("better-caves-noise", TUPLE, repeat('a', 64));
        }
    }

    private static byte[] transform(JarFile jar, String className) throws Exception {
        byte[] original = read(jar, className);
        TargetSpec target = OptimizerTargetCatalog.find(className);
        assertNotNull(target);
        OptimizerBytecodeAdapter adapter = OptimizerAdapterRegistry.find(target.adapterId);
        assertNotNull(adapter);
        return adapter.transform(className, original, target);
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

    private static void assertBits(double expected, double actual) {
        assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(actual));
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    private static final class ByteLoader extends ClassLoader {
        private ByteLoader(ClassLoader parent) { super(parent); }
        private Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
