package dev.rlcraft.ice.optimizer.compat.otg;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.ClientOptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import dev.rlcraft.ice.hooks.IceClientOptimizerTransformer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.GZIPOutputStream;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/** Result and isolation proof against the exact OTG 9.7 binary used by Dregora. */
public final class OtgSynchronousIoRealJarTest {
    private boolean previousEnabled;
    private boolean previousModule;
    private Locale previousLocale;

    @Before
    public void enableModule() {
        previousEnabled = OptimizerConfig.settings.enabled;
        previousModule = OptimizerConfig.settings.otgSynchronousFileCache;
        previousLocale = Locale.getDefault();
        Locale.setDefault(Locale.US);
        OptimizerConfig.settings.enabled = true;
        OptimizerConfig.settings.otgSynchronousFileCache = true;
        OptimizerRegistry.configure(ClientOptimizerConfig.capture());
        OptimizerRegistry.breaker(OptimizationModule.OTG_SYNC_FILE_CACHE)
            .patchInstalled("synthetic", "test");
        OtgSynchronousIoBridge.advanceConfigurationGeneration();
    }

    @After
    public void restoreModule() {
        OtgSynchronousIoBridge.advanceConfigurationGeneration();
        Locale.setDefault(previousLocale);
        OptimizerConfig.settings.enabled = previousEnabled;
        OptimizerConfig.settings.otgSynchronousFileCache = previousModule;
        OptimizerRegistry.configure(ClientOptimizerConfig.capture());
    }

    @Test
    public void linearReaderMatchesRealNamedBinaryTagForRawAndGzip() throws Exception {
        File jar = realJar();
        URLClassLoader loader = new URLClassLoader(new URL[] { jar.toURI().toURL() },
            getClass().getClassLoader());
        try {
            Class<?> tagClass = loader.loadClass("com.pg85.otg.util.bo3.NamedBinaryTag");
            byte[] raw = sampleNbt();
            byte[] gzip = gzip(raw);

            Object rawReference = invokeReferenceRead(tagClass, raw, false);
            Object rawOptimized = OtgSynchronousIoBridge.readNamedBinaryTag(
                new ByteArrayInputStream(raw), false, tagClass);
            assertTagTreeEquals(rawReference, rawOptimized, tagClass);

            Object gzipReference = invokeReferenceRead(tagClass, gzip, true);
            Object gzipOptimized = OtgSynchronousIoBridge.readNamedBinaryTag(
                new ByteArrayInputStream(gzip), true, tagClass);
            assertTagTreeEquals(gzipReference, gzipOptimized, tagClass);
        } finally {
            loader.close();
        }
    }

    @Test(expected = ThreadDeath.class)
    public void reflectedFatalTagConstructionIsNeverConvertedToFallback()
        throws Exception {
        OtgSynchronousIoBridge.readNamedBinaryTag(
            new ByteArrayInputStream(new byte[] { 0 }), false, FatalTag.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void metadataCacheRevalidatesAndDeepCopiesEveryMutableTagValue() throws Exception {
        File jar = realJar();
        URLClassLoader loader = new URLClassLoader(new URL[] { jar.toURI().toURL() },
            getClass().getClassLoader());
        Path directory = Files.createTempDirectory("ice-otg-nbt-");
        try {
            Class<?> tagClass = loader.loadClass("com.pg85.otg.util.bo3.NamedBinaryTag");
            Path file = directory.resolve("tile.nbt");
            Files.write(file, gzip(sampleNbt()));
            Map<String, Object> cache = (Map<String, Object>)
                OtgSynchronousIoBridge.createMetadataMap(tagClass);

            String path = file.toFile().getCanonicalPath();
            OtgSynchronousIoBridge.resetFileDigestReadsForTest();
            OtgSynchronousIoBridge.resetFileAuthenticationProbesForTest();
            assertFalse(cache.containsKey(path));
            Object loaded;
            InputStream input = Files.newInputStream(file);
            try {
                loaded = OtgSynchronousIoBridge.readNamedBinaryTag(input, true, tagClass);
            } finally {
                input.close();
            }
            cache.put(path, loaded);
            long publicationDigestReads =
                OtgSynchronousIoBridge.fileDigestReadsForTest();
            long publicationAuthenticationProbes =
                OtgSynchronousIoBridge.fileAuthenticationProbesForTest();
            assertEquals("metadata publication fingerprints the file once",
                1L, publicationDigestReads);
            assertTrue(cache.containsKey(path));
            Object first = cache.get(path);
            assertTrue(cache.containsKey(path));
            Object second = cache.get(path);
            for (int index = 0; index < 16; index++) {
                assertTrue(cache.containsKey(path));
                assertNotNull(cache.get(path));
            }
            assertEquals("hot metadata hits must reuse the authenticated digest",
                publicationDigestReads,
                OtgSynchronousIoBridge.fileDigestReadsForTest());
            assertEquals("hot metadata hits must perform zero filesystem authentication",
                publicationAuthenticationProbes,
                OtgSynchronousIoBridge.fileAuthenticationProbesForTest());

            String normalizedAlias = directory.resolve(".").resolve("tile.nbt").toString();
            assertTrue(cache.containsKey(normalizedAlias));
            assertNotNull(cache.get(normalizedAlias));
            assertEquals("a normalized alias must share the CPU-only hot lookup",
                publicationAuthenticationProbes,
                OtgSynchronousIoBridge.fileAuthenticationProbesForTest());
            assertTagTreeEquals(loaded, first, tagClass);
            assertTagTreeEquals(loaded, second, tagClass);
            assertTagTreeDistinct(first, second, tagClass);

            Method getTag = tagClass.getMethod("getTag", String.class);
            Method getValue = tagClass.getMethod("getValue");
            byte[] firstBytes = (byte[]) getValue.invoke(getTag.invoke(first, "bytes"));
            int[] firstInts = (int[]) getValue.invoke(getTag.invoke(first, "ints"));
            firstBytes[0] ^= 0x7f;
            firstInts[0] ^= 0x7fffffff;
            assertArrayEquals(new byte[] { 1, 2, 3, 4 },
                (byte[]) getValue.invoke(getTag.invoke(second, "bytes")));
            assertArrayEquals(new int[] { 7, -9, 1234567 },
                (int[]) getValue.invoke(getTag.invoke(second, "ints")));

            // Preserve path, file key, size and mtime while changing bytes: the
            // SHA-256 component must still reject the old blueprint.
            FileTime originalModified = Files.getLastModifiedTime(file);
            byte[] sameSizedMutation = Files.readAllBytes(file);
            sameSizedMutation[sameSizedMutation.length / 2] ^= 0x55;
            assertTrue(cache.containsKey(path));
            Files.write(file, sameSizedMutation);
            Files.setLastModifiedTime(file, originalModified);
            assertEquals(originalModified, Files.getLastModifiedTime(file));
            Object coherentPair = cache.get(path);
            assertTagTreeEquals(loaded, coherentPair, tagClass);
            assertFalse(cache.containsKey(path));

            Path missing = directory.resolve("missing.nbt");
            assertFalse(cache.containsKey(missing.toString()));
            cache.put(missing.toString(), null);
            long negativeAuthenticationProbes =
                OtgSynchronousIoBridge.fileAuthenticationProbesForTest();
            assertTrue(cache.containsKey(missing.toString()));
            assertEquals(null, cache.get(missing.toString()));
            assertTrue(cache.containsKey(missing.toString()));
            assertEquals("a stable negative entry must not probe the missing path again",
                negativeAuthenticationProbes,
                OtgSynchronousIoBridge.fileAuthenticationProbesForTest());
            Files.write(missing, gzip(sampleNbt()));
            assertFalse(cache.containsKey(missing.toString()));

            Files.write(file, gzip(sampleNbt()));
            assertFalse(cache.containsKey(path));
            InputStream secondInput = Files.newInputStream(file);
            Object reloaded;
            try {
                reloaded = OtgSynchronousIoBridge.readNamedBinaryTag(
                    secondInput, true, tagClass);
            } finally {
                secondInput.close();
            }
            cache.put(path, reloaded);
            assertTrue(cache.containsKey(path));
            OtgSynchronousIoBridge.advanceConfigurationGeneration();
            assertEquals(null, cache.get(path));
            assertFalse(cache.containsKey(path));
        } finally {
            deleteTree(directory);
            loader.close();
        }
    }

    public static final class FatalTag {
        public enum Type {
            TAG_End, TAG_Byte, TAG_Short, TAG_Int, TAG_Long, TAG_Float,
            TAG_Double, TAG_Byte_Array, TAG_String, TAG_List,
            TAG_Compound, TAG_Int_Array
        }

        public FatalTag(Type type, String name, Object value) {
            throw new ThreadDeath();
        }

        public Type getType() { return Type.TAG_End; }
        public String getName() { return null; }
        public Object getValue() { return null; }
        public Type getListType() { return Type.TAG_End; }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void metadataPublicationIsGenerationAtomicAndIoFailureUsesOriginalMap()
        throws Exception {
        File jar = realJar();
        URLClassLoader loader = new URLClassLoader(new URL[] { jar.toURI().toURL() },
            getClass().getClassLoader());
        Path directory = Files.createTempDirectory("ice-otg-publication-");
        try {
            Class<?> tagClass = loader.loadClass("com.pg85.otg.util.bo3.NamedBinaryTag");
            Path file = directory.resolve("tile.nbt");
            Files.write(file, gzip(sampleNbt()));
            Map<String, Object> cache = (Map<String, Object>)
                OtgSynchronousIoBridge.createMetadataMap(tagClass);
            String path = file.toFile().getCanonicalPath();

            assertFalse(cache.containsKey(path));
            Object loaded = OtgSynchronousIoBridge.readNamedBinaryTag(
                Files.newInputStream(file), true, tagClass);

            // The lookup and parse belong to the old configuration. A reload
            // before registerMetadata must prevent the stale blueprint from
            // being published into the new generation.
            OtgSynchronousIoBridge.advanceConfigurationGeneration();
            cache.put(path, loaded);
            assertFalse(cache.containsKey(path));

            // A source that becomes un-fingerprintable after containsKey must
            // retain the target HashMap's behavior rather than lose the value.
            assertFalse(cache.containsKey(path));
            Object fallbackValue = invokeReferenceRead(tagClass, sampleNbt(), false);
            Files.delete(file);
            Files.createDirectory(file);
            cache.put(path, fallbackValue);
            assertTrue(cache.containsKey(path));
            assertSame(fallbackValue, cache.get(path));
        } finally {
            deleteTree(directory);
            loader.close();
        }
    }

    @Test
    public void transformedBo3LoaderExecutesTheCertifiedCacheAndParserWiring() throws Exception {
        File jar = realJar();
        URLClassLoader dependencies = new URLClassLoader(
            new URL[] { jar.toURI().toURL() }, getClass().getClassLoader());
        Path directory = Files.createTempDirectory("ice-otg-loader-");
        JarFile source = new JarFile(jar);
        try {
            String className = "com.pg85.otg.customobjects.bo3.BO3Loader";
            JarEntry entry = source.getJarEntry(className.replace('.', '/') + ".class");
            assertNotNull(entry);
            byte[] original;
            InputStream classInput = source.getInputStream(entry);
            try {
                original = readFully(classInput);
            } finally {
                classInput.close();
            }
            byte[] transformed = new IceClientOptimizerTransformer().transform(
                className, className, original);
            Class<?> loaderClass = new ByteLoader(dependencies).define(className, transformed);

            Path nbt = directory.resolve("tile.nbt");
            Files.write(nbt, gzip(sampleNbt()));
            File bo3 = directory.resolve("object.bo3").toFile();
            Method load = loaderClass.getMethod("loadMetadata", String.class, File.class);
            Object first = load.invoke(null, "tile.nbt", bo3);
            Object second = load.invoke(null, "tile.nbt", bo3);
            assertNotNull(first);
            assertNotNull(second);
            assertNotSame(first, second);
            Class<?> tagClass = dependencies.loadClass("com.pg85.otg.util.bo3.NamedBinaryTag");
            assertTagTreeEquals(first, second, tagClass);

            Object firstId = tagClass.getMethod("getTag", String.class).invoke(first, "id");
            tagClass.getMethod("setValue", Object.class).invoke(firstId, "mutated");
            Object third = load.invoke(null, "tile.nbt", bo3);
            Object thirdId = tagClass.getMethod("getTag", String.class).invoke(third, "id");
            assertEquals("minecraft:chest", tagClass.getMethod("getValue").invoke(thirdId));
        } finally {
            source.close();
            deleteTree(directory);
            dependencies.close();
        }
    }

    @Test
    public void settingsSnapshotMatchesRealReaderAndPublishesIndependently() throws Exception {
        File jar = realJar();
        URLClassLoader loader = new URLClassLoader(new URL[] { jar.toURI().toURL() },
            getClass().getClassLoader());
        Path directory = Files.createTempDirectory("ice-otg-settings-");
        try {
            Class<?> readerClass = loader.loadClass(
                "com.pg85.otg.configuration.io.FileSettingsReaderOTGPlus");
            Constructor<?> constructor = readerClass.getConstructor(String.class, File.class);
            Path file = directory.resolve("object.bo3");

            // Construct cache targets while the file is absent, matching the
            // target class's normal empty-map state without Unsafe.
            Object first = constructor.newInstance("object", file.toFile());
            Object second = constructor.newInstance("object", file.toFile());
            Object aliasTarget = constructor.newInstance("object", file.toFile());
            Object afterMutation = constructor.newInstance("object", file.toFile());
            List<Object> concurrent = new ArrayList<Object>();
            for (int index = 0; index < 12; index++) {
                concurrent.add(constructor.newInstance("object", file.toFile()));
            }

            Files.write(file, sampleSettings().getBytes(Charset.defaultCharset()));
            Object reference = constructor.newInstance("object", file.toFile());
            ReaderSnapshot expected = snapshotReader(reference, readerClass);

            OtgSynchronousIoBridge.resetFileDigestReadsForTest();
            OtgSynchronousIoBridge.resetFileAuthenticationProbesForTest();
            assertTrue(OtgSynchronousIoBridge.readSettings(first, file.toFile(), readerClass));
            long initialAuthenticationProbes =
                OtgSynchronousIoBridge.fileAuthenticationProbesForTest();
            assertEquals("settings parse hashes its in-memory source, not the path",
                0L, OtgSynchronousIoBridge.fileDigestReadsForTest());
            assertTrue(OtgSynchronousIoBridge.readSettings(second, file.toFile(), readerClass));
            assertEquals("settings cache hits must not fingerprint the path",
                0L, OtgSynchronousIoBridge.fileDigestReadsForTest());
            assertEquals("hot settings hits must perform zero filesystem authentication",
                initialAuthenticationProbes,
                OtgSynchronousIoBridge.fileAuthenticationProbesForTest());

            File normalizedAlias = directory.resolve(".").resolve("object.bo3").toFile();
            assertTrue(OtgSynchronousIoBridge.readSettings(
                aliasTarget, normalizedAlias, readerClass));
            assertEquals("a normalized settings alias must stay on the hot path",
                initialAuthenticationProbes,
                OtgSynchronousIoBridge.fileAuthenticationProbesForTest());
            assertEquals(expected, snapshotReader(aliasTarget, readerClass));
            assertEquals(expected, snapshotReader(first, readerClass));
            assertEquals(expected, snapshotReader(second, readerClass));
            assertIndependentReaderContainers(first, second, readerClass);

            ExecutorService executor = Executors.newFixedThreadPool(4);
            try {
                List<Future<ReaderSnapshot>> results =
                    new ArrayList<Future<ReaderSnapshot>>(concurrent.size());
                for (final Object target : concurrent) {
                    results.add(executor.submit(new Callable<ReaderSnapshot>() {
                        @Override public ReaderSnapshot call() throws Exception {
                            assertTrue(OtgSynchronousIoBridge.readSettings(
                                target, file.toFile(), readerClass));
                            return snapshotReader(target, readerClass);
                        }
                    }));
                }
                for (Future<ReaderSnapshot> result : results) assertEquals(expected, result.get());
            } finally {
                executor.shutdownNow();
            }
            assertEquals("concurrent settings hits must remain filesystem-free",
                initialAuthenticationProbes,
                OtgSynchronousIoBridge.fileAuthenticationProbesForTest());

            // Container mutation in one returned reader cannot alter a later
            // result built from the immutable cached blueprint.
            clearReader(first, readerClass);
            assertEquals(expected, snapshotReader(second, readerClass));

            String changed = sampleSettings() + "\nChangedSetting: new-value\n";
            Files.write(file, changed.getBytes(Charset.defaultCharset()));
            Object changedReference = constructor.newInstance("object", file.toFile());
            assertTrue(OtgSynchronousIoBridge.readSettings(
                afterMutation, file.toFile(), readerClass));
            assertTrue("a watched mutation must force fresh authentication",
                OtgSynchronousIoBridge.fileAuthenticationProbesForTest()
                    > initialAuthenticationProbes);
            assertEquals(snapshotReader(changedReference, readerClass),
                snapshotReader(afterMutation, readerClass));
        } finally {
            deleteTree(directory);
            loader.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void cachedInputsProduceIdenticalSynchronousWorldResultAndRngState()
        throws Exception {
        File jar = realJar();
        URLClassLoader loader = new URLClassLoader(new URL[] { jar.toURI().toURL() },
            getClass().getClassLoader());
        Path directory = Files.createTempDirectory("ice-otg-world-result-");
        try {
            Class<?> readerClass = loader.loadClass(
                "com.pg85.otg.configuration.io.FileSettingsReaderOTGPlus");
            Class<?> tagClass = loader.loadClass("com.pg85.otg.util.bo3.NamedBinaryTag");
            Constructor<?> constructor = readerClass.getConstructor(String.class, File.class);
            Path settingsFile = directory.resolve("result.bo3");
            Object optimizedReader = constructor.newInstance("result", settingsFile.toFile());
            Files.write(settingsFile, sampleSettings().getBytes(Charset.defaultCharset()));
            Object referenceReader = constructor.newInstance("result", settingsFile.toFile());
            assertTrue(OtgSynchronousIoBridge.readSettings(
                optimizedReader, settingsFile.toFile(), readerClass));

            Path nbtFile = directory.resolve("result.nbt");
            Files.write(nbtFile, gzip(sampleNbt()));
            Object referenceTag = invokeReferenceRead(tagClass, gzip(sampleNbt()), true);
            Map<String, Object> metadata = (Map<String, Object>)
                OtgSynchronousIoBridge.createMetadataMap(tagClass);
            String nbtPath = nbtFile.toFile().getCanonicalPath();
            assertFalse(metadata.containsKey(nbtPath));
            InputStream input = Files.newInputStream(nbtFile);
            Object firstTag;
            try {
                firstTag = OtgSynchronousIoBridge.readNamedBinaryTag(input, true, tagClass);
            } finally {
                input.close();
            }
            metadata.put(nbtPath, firstTag);
            assertTrue(metadata.containsKey(nbtPath));
            Object cachedTag = metadata.get(nbtPath);

            WorldResult reference = buildWorldResult(
                snapshotReader(referenceReader, readerClass), referenceTag, tagClass, 0x51ceL);
            WorldResult optimized = buildWorldResult(
                snapshotReader(optimizedReader, readerClass), cachedTag, tagClass, 0x51ceL);
            assertEquals(reference, optimized);
        } finally {
            deleteTree(directory);
            loader.close();
        }
    }

    private static File realJar() {
        String configured = System.getProperty("ice.otg.jar", "").trim();
        Assume.assumeTrue("run with -PotgJar=<OpenTerrainGenerator-1.12.2-v9.7.jar>",
            !configured.isEmpty());
        File jar = new File(configured);
        Assume.assumeTrue(jar.isFile());
        return jar;
    }

    private static Object invokeReferenceRead(Class<?> tagClass, byte[] bytes,
                                              boolean compressed) throws Exception {
        return tagClass.getMethod("readFrom", InputStream.class, boolean.class)
            .invoke(null, new ByteArrayInputStream(bytes), Boolean.valueOf(compressed));
    }

    private static byte[] sampleNbt() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeByte(10);
        output.writeUTF("root");
        writeString(output, "id", "minecraft:chest");
        output.writeByte(1); output.writeUTF("byte"); output.writeByte(-12);
        output.writeByte(2); output.writeUTF("short"); output.writeShort(32001);
        output.writeByte(3); output.writeUTF("int"); output.writeInt(-123456789);
        output.writeByte(4); output.writeUTF("long"); output.writeLong(0x1020304050607080L);
        output.writeByte(5); output.writeUTF("float"); output.writeFloat(-13.25F);
        output.writeByte(6); output.writeUTF("double"); output.writeDouble(12345.125D);
        output.writeByte(7); output.writeUTF("bytes"); output.writeInt(4);
        output.write(new byte[] { 1, 2, 3, 4 });
        output.writeByte(9); output.writeUTF("emptyList"); output.writeByte(3); output.writeInt(0);
        output.writeByte(9); output.writeUTF("intList"); output.writeByte(3); output.writeInt(3);
        output.writeInt(11); output.writeInt(-22); output.writeInt(33);
        output.writeByte(10); output.writeUTF("nested");
        writeString(output, "name", "tile-data");
        output.writeByte(0);
        output.writeByte(11); output.writeUTF("ints"); output.writeInt(3);
        output.writeInt(7); output.writeInt(-9); output.writeInt(1234567);
        output.writeByte(0);
        output.close();
        return bytes.toByteArray();
    }

    private static void writeString(DataOutputStream output, String name, String value)
        throws Exception {
        output.writeByte(8);
        output.writeUTF(name);
        output.writeUTF(value);
    }

    private static byte[] gzip(byte[] raw) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        GZIPOutputStream gzip = new GZIPOutputStream(bytes);
        gzip.write(raw);
        gzip.close();
        return bytes.toByteArray();
    }

    private static byte[] readFully(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static void assertTagTreeEquals(Object expected, Object actual,
                                            Class<?> tagClass) throws Exception {
        assertNotNull(expected);
        assertNotNull(actual);
        Method getType = tagClass.getMethod("getType");
        Method getName = tagClass.getMethod("getName");
        Method getValue = tagClass.getMethod("getValue");
        Method getListType = tagClass.getMethod("getListType");
        int expectedType = ((Enum<?>) getType.invoke(expected)).ordinal();
        assertEquals(expectedType, ((Enum<?>) getType.invoke(actual)).ordinal());
        assertEquals(getName.invoke(expected), getName.invoke(actual));
        if (expectedType == 9) {
            assertEquals(((Enum<?>) getListType.invoke(expected)).ordinal(),
                ((Enum<?>) getListType.invoke(actual)).ordinal());
        }
        Object expectedValue = getValue.invoke(expected);
        Object actualValue = getValue.invoke(actual);
        if (expectedValue instanceof byte[]) {
            assertArrayEquals((byte[]) expectedValue, (byte[]) actualValue);
        } else if (expectedValue instanceof int[]) {
            assertArrayEquals((int[]) expectedValue, (int[]) actualValue);
        } else if (expectedValue != null && expectedValue.getClass().isArray()) {
            assertEquals(Array.getLength(expectedValue), Array.getLength(actualValue));
            for (int index = 0; index < Array.getLength(expectedValue); index++) {
                assertTagTreeEquals(Array.get(expectedValue, index),
                    Array.get(actualValue, index), tagClass);
            }
        } else {
            assertEquals(expectedValue, actualValue);
        }
    }

    private static void assertTagTreeDistinct(Object first, Object second,
                                              Class<?> tagClass) throws Exception {
        assertNotSame(first, second);
        Object firstValue = tagClass.getMethod("getValue").invoke(first);
        Object secondValue = tagClass.getMethod("getValue").invoke(second);
        if (firstValue != null && firstValue.getClass().isArray()) {
            assertNotSame(firstValue, secondValue);
            if (!(firstValue instanceof byte[]) && !(firstValue instanceof int[])) {
                for (int index = 0; index < Array.getLength(firstValue); index++) {
                    assertTagTreeDistinct(Array.get(firstValue, index),
                        Array.get(secondValue, index), tagClass);
                }
            }
        }
    }

    private static String sampleSettings() {
        return "# comment\n"
            + "<legacy marker>\n"
            + "Author: ICE\n"
            + "Frequency=7\n"
            + "Block(0,1,0,STONE)\n"
            + "Branch(1,2,3,Child,50)\n"
            + "Duplicate: first\n"
            + "Duplicate: second\n"
            + "  # indented comment remains ignored by the original grammar\n"
            + "NameWithParen: value(test)\n";
    }

    @SuppressWarnings("unchecked")
    private static ReaderSnapshot snapshotReader(Object reader, Class<?> readerClass)
        throws Exception {
        Field settingsField = readerClass.getDeclaredField("settingsCache");
        Field functionsField = readerClass.getDeclaredField("configFunctions");
        settingsField.setAccessible(true);
        functionsField.setAccessible(true);
        Map<String, Object> rawSettings = (Map<String, Object>) settingsField.get(reader);
        List<Object> rawFunctions = (List<Object>) functionsField.get(reader);
        Map<String, LineValue> settings = new HashMap<String, LineValue>();
        for (Map.Entry<String, Object> entry : rawSettings.entrySet()) {
            settings.put(entry.getKey(), lineValue(entry.getValue()));
        }
        List<LineValue> functions = new ArrayList<LineValue>();
        for (Object function : rawFunctions) functions.add(lineValue(function));
        return new ReaderSnapshot(settings, functions);
    }

    private static LineValue lineValue(Object value) throws Exception {
        Field string = value.getClass().getDeclaredField("string");
        Field line = value.getClass().getDeclaredField("line");
        string.setAccessible(true);
        line.setAccessible(true);
        return new LineValue((String) string.get(value), line.getInt(value));
    }

    private static void assertIndependentReaderContainers(Object first, Object second,
                                                          Class<?> readerClass)
        throws Exception {
        for (String fieldName : Arrays.asList("settingsCache", "configFunctions")) {
            Field field = readerClass.getDeclaredField(fieldName);
            field.setAccessible(true);
            assertNotSame(field.get(first), field.get(second));
        }
    }

    private static void clearReader(Object reader, Class<?> readerClass) throws Exception {
        for (String fieldName : Arrays.asList("settingsCache", "configFunctions")) {
            Field field = readerClass.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(reader);
            if (value instanceof Map) ((Map<?, ?>) value).clear();
            if (value instanceof List) ((List<?>) value).clear();
        }
    }

    private static WorldResult buildWorldResult(ReaderSnapshot settings, Object metadata,
                                                Class<?> tagClass, long seed)
        throws Exception {
        int[] blocks = new int[16 * 16 * 16];
        Random random = new Random(seed);
        int writes = 0;
        for (LineValue function : settings.functions) {
            int index = random.nextInt(blocks.length);
            blocks[index] = 31 * function.value.hashCode() + random.nextInt();
            writes++;
        }
        List<String> keys = new ArrayList<String>(settings.settings.keySet());
        Collections.sort(keys);
        int configHash = 1;
        for (String key : keys) {
            LineValue value = settings.settings.get(key);
            configHash = 31 * configHash + key.hashCode();
            configHash = 31 * configHash + value.hashCode();
        }
        int tileHash = tagHash(metadata, tagClass);
        long rngAfter = random.nextLong();
        return new WorldResult(blocks, writes, configHash, tileHash, rngAfter);
    }

    private static int tagHash(Object tag, Class<?> tagClass) throws Exception {
        Method getType = tagClass.getMethod("getType");
        Method getName = tagClass.getMethod("getName");
        Method getValue = tagClass.getMethod("getValue");
        Method getListType = tagClass.getMethod("getListType");
        int type = ((Enum<?>) getType.invoke(tag)).ordinal();
        int result = 31 + type;
        Object name = getName.invoke(tag);
        result = 31 * result + (name == null ? 0 : name.hashCode());
        if (type == 9) result = 31 * result + ((Enum<?>) getListType.invoke(tag)).ordinal();
        Object value = getValue.invoke(tag);
        if (value instanceof byte[]) return 31 * result + Arrays.hashCode((byte[]) value);
        if (value instanceof int[]) return 31 * result + Arrays.hashCode((int[]) value);
        if (value != null && value.getClass().isArray()) {
            for (int index = 0; index < Array.getLength(value); index++) {
                result = 31 * result + tagHash(Array.get(value, index), tagClass);
            }
            return result;
        }
        return 31 * result + (value == null ? 0 : value.hashCode());
    }

    private static void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        List<Path> paths = new ArrayList<Path>();
        Files.walk(root).forEach(paths::add);
        Collections.sort(paths, Collections.reverseOrder());
        for (Path path : paths) Files.deleteIfExists(path);
    }

    private static final class LineValue {
        private final String value;
        private final int line;

        private LineValue(String value, int line) {
            this.value = value;
            this.line = line;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof LineValue)) return false;
            LineValue value = (LineValue) other;
            return line == value.line && this.value.equals(value.value);
        }

        @Override public int hashCode() {
            return 31 * value.hashCode() + line;
        }

        @Override public String toString() {
            return line + ":" + value;
        }
    }

    private static final class ReaderSnapshot {
        private final Map<String, LineValue> settings;
        private final List<LineValue> functions;

        private ReaderSnapshot(Map<String, LineValue> settings, List<LineValue> functions) {
            this.settings = Collections.unmodifiableMap(new HashMap<String, LineValue>(settings));
            this.functions = Collections.unmodifiableList(new ArrayList<LineValue>(functions));
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof ReaderSnapshot)) return false;
            ReaderSnapshot value = (ReaderSnapshot) other;
            return settings.equals(value.settings) && functions.equals(value.functions);
        }

        @Override public int hashCode() {
            return 31 * settings.hashCode() + functions.hashCode();
        }

        @Override public String toString() {
            return "settings=" + settings + ", functions=" + functions;
        }
    }

    private static final class WorldResult {
        private final int[] blocks;
        private final int writes;
        private final int configHash;
        private final int tileHash;
        private final long rngAfter;

        private WorldResult(int[] blocks, int writes, int configHash,
                            int tileHash, long rngAfter) {
            this.blocks = blocks;
            this.writes = writes;
            this.configHash = configHash;
            this.tileHash = tileHash;
            this.rngAfter = rngAfter;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof WorldResult)) return false;
            WorldResult value = (WorldResult) other;
            return writes == value.writes && configHash == value.configHash
                && tileHash == value.tileHash && rngAfter == value.rngAfter
                && Arrays.equals(blocks, value.blocks);
        }

        @Override public int hashCode() {
            int result = Arrays.hashCode(blocks);
            result = 31 * result + writes;
            result = 31 * result + configHash;
            result = 31 * result + tileHash;
            return 31 * result + (int) (rngAfter ^ (rngAfter >>> 32));
        }
    }

    private static final class ByteLoader extends ClassLoader {
        private ByteLoader(ClassLoader parent) {
            super(parent);
        }

        private Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
