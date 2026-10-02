import dev.rlcraft.ice.optimizer.ClientOptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import dev.rlcraft.ice.optimizer.compat.otg.OtgSynchronousIoBridge;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/** Local-file OTG admission benchmark; never opens a game save or changes a pack. */
public final class OtgIoBenchmark {
    private static final int FILES = 256;
    private static final int WARMUPS = 3;
    private static final int SAMPLES = 7;

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("<OTG jar> <fixture directory>");
        Path fixtureRoot = Paths.get(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(fixtureRoot);
        Path fixture = Files.createTempDirectory(fixtureRoot, "otg-io-benchmark-");
        URLClassLoader loader = new URLClassLoader(
            new URL[] {new File(args[0]).toURI().toURL()}, OtgIoBenchmark.class.getClassLoader());
        try {
            OptimizerConfig.settings.enabled = true;
            OptimizerConfig.settings.otgSynchronousFileCache = true;
            OptimizerRegistry.configure(ClientOptimizerConfig.capture());
            OptimizerRegistry.breaker(OptimizationModule.OTG_SYNC_FILE_CACHE)
                .patchInstalled("benchmark", "real OTG local files");
            Class<?> reader = loader.loadClass(
                "com.pg85.otg.configuration.io.FileSettingsReaderOTGPlus");
            Constructor<?> constructor = reader.getConstructor(String.class, File.class);
            Class<?> tag = loader.loadClass("com.pg85.otg.util.bo3.NamedBinaryTag");
            Path directory = fixture;
            for (int index = 0; index < 3; index++) {
                directory = Files.createDirectory(directory.resolve("structure-path-" + index));
            }
            Path[] settings = new Path[FILES];
            Path[] metadata = new Path[FILES];
            Path[] missing = new Path[FILES];
            StringBuilder source = new StringBuilder("# real OTG parse syntax\n");
            for (int index = 0; index < 128; index++) {
                source.append("Setting").append(index).append(": value-").append(index).append('\n');
                source.append("Block(0,").append(index).append(",2,minecraft:stone)\n");
            }
            byte[] settingsBytes = source.toString().getBytes(Charset.defaultCharset());
            byte[] tagBytes = metadataBytes();
            for (int index = 0; index < FILES; index++) {
                settings[index] = directory.resolve("object-" + index + ".bo3");
                metadata[index] = directory.resolve("metadata-" + index + ".nbt");
                missing[index] = directory.resolve("missing-" + index + ".nbt");
                Files.write(settings[index], settingsBytes);
                Files.write(metadata[index], tagBytes);
            }
            File absentTarget = directory.resolve("empty-reader-target.bo3").toFile();
            System.out.println("files=" + FILES + " settings_bytes=" + settingsBytes.length
                + " metadata_bytes=" + tagBytes.length + " samples=" + SAMPLES
                + " filesystem=local_warm_os_cache");
            benchmarkSettings(constructor, reader, absentTarget, settings);
            benchmarkMetadata(tag, metadata, false);
            benchmarkMetadata(tag, missing, true);
        } finally {
            loader.close();
            try (Stream<Path> paths = Files.walk(fixture)) {
                Path[] ordered = paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new);
                for (Path path : ordered) Files.delete(path);
            }
        }
    }

    private static void benchmarkSettings(Constructor<?> constructor, Class<?> reader,
                                          File absent, Path[] paths) throws Exception {
        long[] cold = new long[SAMPLES];
        long[] hot = new long[SAMPLES];
        for (int round = -WARMUPS; round < SAMPLES; round++) {
            OtgSynchronousIoBridge.advanceConfigurationGeneration();
            Object[] first = readers(constructor, absent);
            Object[] second = readers(constructor, absent);
            long start = System.nanoTime();
            for (int index = 0; index < FILES; index++) {
                if (!OtgSynchronousIoBridge.readSettings(first[index], paths[index].toFile(), reader)) {
                    throw new IllegalStateException("cold settings admission rejected at " + index);
                }
            }
            long coldElapsed = System.nanoTime() - start;
            start = System.nanoTime();
            for (int index = 0; index < FILES; index++) {
                if (!OtgSynchronousIoBridge.readSettings(second[index], paths[index].toFile(), reader)) {
                    throw new IllegalStateException("hot settings admission rejected at " + index);
                }
            }
            if (round >= 0) {
                cold[round] = coldElapsed;
                hot[round] = System.nanoTime() - start;
            }
        }
        print("settings_cold", cold);
        print("settings_hot", hot);
    }

    @SuppressWarnings("unchecked")
    private static void benchmarkMetadata(Class<?> tag, Path[] paths, boolean missing)
        throws Exception {
        Map<String, Object> cache = OtgSynchronousIoBridge.createMetadataMap(tag);
        long[] cold = new long[SAMPLES];
        long[] hot = new long[SAMPLES];
        for (int round = -WARMUPS; round < SAMPLES; round++) {
            OtgSynchronousIoBridge.advanceConfigurationGeneration();
            long start = System.nanoTime();
            for (Path path : paths) {
                String key = path.toString();
                if (cache.containsKey(key)) throw new IllegalStateException("unexpected cold hit");
                Object loaded = null;
                if (!missing) {
                    try (InputStream input = Files.newInputStream(path)) {
                        loaded = OtgSynchronousIoBridge.readNamedBinaryTag(input, true, tag);
                    }
                    if (loaded == null) throw new IllegalStateException("NBT parse failed");
                }
                cache.put(key, loaded);
            }
            long coldElapsed = System.nanoTime() - start;
            start = System.nanoTime();
            for (Path path : paths) {
                String key = path.toString();
                if (!cache.containsKey(key)) throw new IllegalStateException("expected hot hit");
                Object value = cache.get(key);
                if (!missing && value == null) throw new IllegalStateException("empty positive hit");
            }
            if (round >= 0) {
                cold[round] = coldElapsed;
                hot[round] = System.nanoTime() - start;
            }
        }
        print(missing ? "metadata_missing_cold" : "metadata_cold", cold);
        print(missing ? "metadata_missing_hot" : "metadata_hot", hot);
    }

    private static Object[] readers(Constructor<?> constructor, File absent) throws Exception {
        Object[] targets = new Object[FILES];
        for (int index = 0; index < FILES; index++) {
            targets[index] = constructor.newInstance("object", absent);
        }
        return targets;
    }

    private static byte[] metadataBytes() throws Exception {
        byte[] array = new byte[32 * 1024];
        new Random(19061002L).nextBytes(array);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(bytes))) {
            output.writeByte(10);
            output.writeUTF("root");
            output.writeByte(7);
            output.writeUTF("data");
            output.writeInt(array.length);
            output.write(array);
            output.writeByte(0);
        }
        return bytes.toByteArray();
    }

    private static void print(String label, long[] samples) {
        Arrays.sort(samples);
        System.out.printf(java.util.Locale.ROOT, "%s median_us_per_file=%.3f max_us_per_file=%.3f%n",
            label, samples[samples.length / 2] / (double) FILES / 1000.0,
            samples[samples.length - 1] / (double) FILES / 1000.0);
    }
}
