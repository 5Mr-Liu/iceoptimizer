package dev.rlcraft.ice.optimizer.compat.otg;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Weigher;
import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.ModuleCircuitBreaker;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import dev.rlcraft.ice.optimizer.compat.otg.OtgSettingsSnapshot.LineRecord;
import dev.rlcraft.ice.optimizer.compat.otg.OtgSettingsSnapshot.SettingRecord;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;

/**
 * Synchronous, result-preserving OTG file acceleration without an OTG linkage.
 *
 * <p>Only stable, completely read files are published. Configuration cache
 * entries contain strings and line numbers only. BO3 metadata entries contain
 * immutable tag blueprints; every hit builds a fresh tag tree and fresh mutable
 * arrays. World generation, RNG use and downstream object construction remain
 * on the calling thread.</p>
 */
public final class OtgSynchronousIoBridge {
    private static final OptimizationModule MODULE = OptimizationModule.OTG_SYNC_FILE_CACHE;
    private static final int IO_BUFFER_BYTES = 64 * 1024;
    private static final int MAX_SINGLE_SETTINGS_BYTES = 64 * 1024 * 1024;
    private static final long SETTINGS_CACHE_BYTES = 96L * 1024L * 1024L;
    private static final long METADATA_CACHE_BYTES = 64L * 1024L * 1024L;

    private static final AtomicLong CONFIGURATION_GENERATION = new AtomicLong(1L);
    private static final AtomicLong FILE_AUTHENTICATION_PROBES = new AtomicLong();
    private static final CopyOnWriteArrayList<WeakReference<MetadataMap>> METADATA_MAPS =
        new CopyOnWriteArrayList<WeakReference<MetadataMap>>();
    private static final ThreadLocal<ParsedTag> LAST_PARSED_TAG = new ThreadLocal<ParsedTag>();
    private static final OtgFileMutationTracker FILE_MUTATIONS =
        new OtgFileMutationTracker();

    private static final Cache<SettingsLookupKey, SettingsEntry> SETTINGS =
        Caffeine.newBuilder()
            .maximumWeight(SETTINGS_CACHE_BYTES)
            .weigher(new Weigher<SettingsLookupKey, SettingsEntry>() {
                @Override public int weigh(SettingsLookupKey key, SettingsEntry value) {
                    return value.snapshot.weight;
                }
            })
            .build();

    private static final Cache<Path, MetadataEntry> PREPARED_METADATA = Caffeine.newBuilder()
        .maximumWeight(METADATA_CACHE_BYTES)
        .weigher(new Weigher<Path, MetadataEntry>() {
            @Override public int weigh(Path key, MetadataEntry value) { return value.weight; }
        }).build();

    private static final ClassValue<TagAccess> TAG_ACCESS = new ClassValue<TagAccess>() {
        @Override protected TagAccess computeValue(Class<?> type) {
            try {
                return new TagAccess(type);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Unsupported OTG NamedBinaryTag ABI", error);
            }
        }
    };

    private static final ClassValue<SettingsAccess> SETTINGS_ACCESS =
        new ClassValue<SettingsAccess>() {
            @Override protected SettingsAccess computeValue(Class<?> type) {
                try {
                    return new SettingsAccess(type);
                } catch (ReflectiveOperationException error) {
                    throw new IllegalStateException(
                        "Unsupported OTG FileSettingsReaderOTGPlus ABI", error);
                }
            }
        };

    private OtgSynchronousIoBridge() {
    }

    /** Called from the transformed FileSettingsReaderOTGPlus.readSettings. */
    public static boolean readSettings(Object reader, File file, Class<?> readerClass) {
        if (!enabled() || reader == null || file == null || readerClass == null
            || reader.getClass() != readerClass) return false;

        final SettingsAccess access;
        try {
            access = SETTINGS_ACCESS.get(readerClass);
        } catch (LinkageError | RuntimeException error) {
            fail(error);
            return false;
        }

        final SettingsLookupKey key;
        try {
            key = SettingsLookupKey.capture(file);
        } catch (IOException | RuntimeException error) {
            FatalErrors.rethrowIfFatal(error);
            return false;
        }

        try {
            SettingsEntry cached = SETTINGS.getIfPresent(key);
            if (cached != null) {
                // One observation at the mutation boundary is sufficient for
                // a hit. A stale entry still reloads within this same call.
                if (populateSettings(reader, access, cached, false)) return true;
                SETTINGS.asMap().remove(key, cached);
            }
            final boolean[] loaded = new boolean[1];
            SettingsEntry entry = SETTINGS.get(key, ignored -> {
                loaded[0] = true;
                return loadSettingsEntry(key);
            });
            if (populateSettings(reader, access, entry, loaded[0])) return true;
            SETTINGS.asMap().remove(key, entry);
            return false;
        } catch (SourceFallback failure) {
            return false;
        } catch (LinkageError | RuntimeException error) {
            fail(error);
            return false;
        }

    }

    private static boolean populateSettings(Object reader, SettingsAccess access,
                                            SettingsEntry entry,
                                            boolean authenticatedOnThisThread) {
        synchronized (OtgSynchronousIoBridge.class) {
            if (entry.stamp.generation != CONFIGURATION_GENERATION.get()) return false;
            if (entry.stamp.watched()) {
                if (!entry.stamp.reusableNow()) return false;
            } else if (!authenticatedOnThisThread) {
                return false;
            }
            access.populate(reader, entry.snapshot);
            success();
            return true;
        }
    }

    /** Replaces BO3Loader's process-global HashMap while retaining its Map ABI. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Map createMetadataMap(Class<?> tagClass) {
        if (tagClass == null) return new HashMap();
        try {
            MetadataMap map = new MetadataMap(tagClass);
            METADATA_MAPS.add(new WeakReference<MetadataMap>(map));
            return map;
        } catch (LinkageError | RuntimeException error) {
            FatalErrors.rethrowIfFatal(error);
            return new HashMap();
        }
    }

    /**
     * Linear compound/list parser used at BO3Loader's two original readFrom
     * call sites. It deliberately preserves the original stream-close and
     * IndexOutOfBounds behavior so BO3Loader's GZIP/IOException fallback and
     * logging exception table remain authoritative.
     */
    public static Object readNamedBinaryTag(InputStream input, boolean compressed,
                                            Class<?> tagClass) throws IOException {
        LAST_PARSED_TAG.remove();
        if (!enabled()) return invokeOriginalReadFrom(input, compressed, tagClass);

        final TagAccess access;
        try {
            // Resolve every reflective member before consuming one input byte.
            access = TAG_ACCESS.get(tagClass);
        } catch (LinkageError | RuntimeException error) {
            fail(error);
            return invokeOriginalReadFrom(input, compressed, tagClass);
        }

        DataInputStream data;
        InputStream buffered = input instanceof BufferedInputStream
            ? input : new BufferedInputStream(input, IO_BUFFER_BYTES);
        if (compressed) {
            data = new DataInputStream(new GZIPInputStream(buffered, IO_BUFFER_BYTES));
        } else {
            data = new DataInputStream(buffered);
        }

        byte rootType = data.readByte();
        Node blueprint = null;
        Object result = null;
        try {
            if (rootType == 0) {
                blueprint = new Node(0, null, -1, null, null);
            } else {
                blueprint = parseNode(data, rootType, data.readUTF(), access);
            }
            result = access.materialize(blueprint);
        } catch (IndexOutOfBoundsException ignored) {
            // NamedBinaryTag.readFrom returns null for an invalid type ordinal.
        } catch (ReflectiveOperationException error) {
            FatalErrors.rethrowIfFatal(error);
            throw new IllegalStateException("Unable to materialize OTG NBT", error);
        }
        data.close();

        if (result == null) {
            LAST_PARSED_TAG.remove();
        } else {
            LAST_PARSED_TAG.set(new ParsedTag(result, blueprint));
        }
        success();
        return result;
    }

    /** Invalidate both kinds of cache at OTG reload/shutdown boundaries. */
    public static synchronized void advanceConfigurationGeneration() {
        long current = CONFIGURATION_GENERATION.get();
        CONFIGURATION_GENERATION.set(current == Long.MAX_VALUE ? 1L : current + 1L);
        SETTINGS.invalidateAll();
        PREPARED_METADATA.invalidateAll();
        for (WeakReference<MetadataMap> reference : METADATA_MAPS) {
            MetadataMap map = reference.get();
            if (map == null) {
                METADATA_MAPS.remove(reference);
            } else {
                map.invalidateSafeEntries();
            }
        }
    }

    /** Pure parsing at object registration; does not construct resources or run world callbacks. */
    static OtgSettingsSnapshot prepareSettings(File file) throws IOException {
        SettingsLookupKey key = SettingsLookupKey.capture(file);
        SettingsEntry entry = SETTINGS.getIfPresent(key);
        if (entry != null && entry.stamp.reusableNow()) return entry.snapshot;
        entry = loadSettingsEntry(key);
        SETTINGS.put(key, entry);
        return entry.snapshot;
    }

    static void prepareMetadata(File file, Class<?> tagClass) throws IOException {
        Path path = FileProbe.logicalPath(file);
        MetadataEntry cached = PREPARED_METADATA.getIfPresent(path);
        if (cached != null && cached.stamp.reusableNow()) return;
        FileProbe before = FileProbe.capture(path, true);
        TagAccess access = TAG_ACCESS.get(tagClass);
        Node root;
        try { root = readBlueprint(path, true, access); }
        catch (IOException compressedFailure) { root = readBlueprint(path, false, access); }
        if (root == null) return;
        boolean id = false;
        if (root.children != null) for (Node child : root.children) if ("id".equals(child.name)) { id = true; break; }
        if (!id) {
            if (root.children == null || root.children.length == 0) return;
            root = root.children[0];
        }
        FileStamp after = FileStamp.authenticate(before);
        synchronized (OtgSynchronousIoBridge.class) {
            if (after.generation == CONFIGURATION_GENERATION.get() && after.watched() && after.reusableNow())
                PREPARED_METADATA.put(path, new MetadataEntry(after, root));
        }
    }

    private static Node readBlueprint(Path path, boolean compressed, TagAccess access) throws IOException {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path), IO_BUFFER_BYTES)) {
            DataInputStream data = new DataInputStream(compressed ? new GZIPInputStream(input, IO_BUFFER_BYTES) : input);
            byte type = data.readByte();
            return type == 0 ? new Node(0, null, -1, null, null) : parseNode(data, type, data.readUTF(), access);
        }
    }

    static long configurationGenerationForTest() {
        return CONFIGURATION_GENERATION.get();
    }

    static long settingsCacheEntriesForTest() {
        return SETTINGS.estimatedSize();
    }

    static long fileAuthenticationProbesForTest() {
        return FILE_AUTHENTICATION_PROBES.get();
    }

    static void resetFileAuthenticationProbesForTest() {
        FILE_AUTHENTICATION_PROBES.set(0L);
    }

    private static SettingsEntry loadSettingsEntry(SettingsLookupKey expected) {
        try {
            FileProbe probe = FileProbe.capture(expected.logicalPath, false);
            if (probe.size > MAX_SINGLE_SETTINGS_BYTES) throw SourceFallback.INSTANCE;
            OtgSettingsSnapshot.ReadResult parsed = OtgSettingsSnapshot.readAndClose(
                Files.newInputStream(probe.path), expected.charset, expected.locale,
                MAX_SINGLE_SETTINGS_BYTES);
            FileStamp stamp = FileStamp.authenticate(probe, parsed.bytes);
            if (stamp.generation != CONFIGURATION_GENERATION.get()) throw SourceFallback.INSTANCE;
            return new SettingsEntry(stamp, parsed.snapshot);
        } catch (SourceFallback fallback) {
            throw fallback;
        } catch (IOException | RuntimeException error) {
            FatalErrors.rethrowIfFatal(error);
            throw SourceFallback.INSTANCE;
        }
    }

    private static Node parseNode(DataInputStream input, byte type, String name,
                                  TagAccess access) throws IOException {
        access.requireType(type);
        switch (type) {
            case 0:
                return new Node(0, name, -1, null, null);
            case 1:
                return new Node(1, name, -1, Byte.valueOf(input.readByte()), null);
            case 2:
                return new Node(2, name, -1, Short.valueOf(input.readShort()), null);
            case 3:
                return new Node(3, name, -1, Integer.valueOf(input.readInt()), null);
            case 4:
                return new Node(4, name, -1, Long.valueOf(input.readLong()), null);
            case 5:
                return new Node(5, name, -1, Float.valueOf(input.readFloat()), null);
            case 6:
                return new Node(6, name, -1, Double.valueOf(input.readDouble()), null);
            case 7: {
                byte[] value = new byte[input.readInt()];
                input.readFully(value);
                return new Node(7, name, -1, value, null);
            }
            case 8:
                return new Node(8, name, -1, input.readUTF(), null);
            case 9: {
                byte listType = input.readByte();
                int length = input.readInt();
                Node[] children = new Node[length];
                for (int index = 0; index < length; index++) {
                    children[index] = parseNode(input, listType, null, access);
                }
                if (length == 0) access.requireType(listType);
                return new Node(9, name, listType, null, children);
            }
            case 10: {
                List<Node> children = new ArrayList<Node>();
                byte childType;
                do {
                    childType = input.readByte();
                    String childName = childType == 0 ? null : input.readUTF();
                    children.add(parseNode(input, childType, childName, access));
                } while (childType != 0);
                return new Node(10, name, -1, null,
                    children.toArray(new Node[children.size()]));
            }
            case 11: {
                int[] value = new int[input.readInt()];
                for (int index = 0; index < value.length; index++) value[index] = input.readInt();
                return new Node(11, name, -1, value, null);
            }
            default:
                return new Node(type, name, -1, null, null);
        }
    }

    private static Object invokeOriginalReadFrom(InputStream input, boolean compressed,
                                                 Class<?> tagClass) throws IOException {
        try {
            Method method = tagClass.getMethod("readFrom", InputStream.class, boolean.class);
            return method.invoke(null, input, Boolean.valueOf(compressed));
        } catch (InvocationTargetException wrapper) {
            Throwable cause = wrapper.getCause();
            FatalErrors.rethrowIfFatal(cause);
            if (cause instanceof IOException) throw (IOException) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IOException("OTG NamedBinaryTag.readFrom failed", cause);
        } catch (ReflectiveOperationException error) {
            throw new IOException("OTG NamedBinaryTag.readFrom unavailable", error);
        }
    }

    private static boolean enabled() {
        try {
            return OptimizerRegistry.isOperational(MODULE);
        } catch (LinkageError | RuntimeException error) {
            FatalErrors.rethrowIfFatal(error);
            return false;
        }
    }

    private static void success() {
        ModuleCircuitBreaker breaker = OptimizerRegistry.breaker(MODULE);
        if (breaker != null) breaker.recordSuccess();
    }

    private static void fail(Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        ModuleCircuitBreaker breaker = OptimizerRegistry.breaker(MODULE);
        if (breaker != null) breaker.recordFailure(error);
    }

    /**
     * A fully authenticated file identity. Full capture is used only for a
     * logical-path miss or after a directory watch reports a change. Hot hits
     * retain this identity and inspect only its watch sequences.
     */
    private static final class FileProbe {
        private final Path logicalPath;
        private final Path path;
        private final boolean exists;
        private final long size;
        private final FileTime modified;
        private final String fileKey;
        private final long generation;
        private final OtgFileMutationTracker.Certificate watches;

        private FileProbe(Path logicalPath, Path path, boolean exists, long size,
                          FileTime modified, String fileKey, long generation,
                          OtgFileMutationTracker.Certificate watches) {
            this.logicalPath = logicalPath;
            this.path = path;
            this.exists = exists;
            this.size = size;
            this.modified = modified;
            this.fileKey = fileKey;
            this.generation = generation;
            this.watches = watches;
        }

        private static FileProbe capture(Path requested, boolean allowMissing)
            throws IOException {
            FILE_AUTHENTICATION_PROBES.incrementAndGet();
            for (int attempt = 0; attempt < 3; attempt++) {
                long generation = CONFIGURATION_GENERATION.get();
                Path path = canonicalCandidatePath(requested);
                OtgFileMutationTracker.Certificate mutationBefore = FILE_MUTATIONS.observe(requested, path);
                final BasicFileAttributes attributes;
                try {
                    BasicFileAttributes observed = Files.readAttributes(path,
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (observed.isSymbolicLink()) {
                        path = path.toRealPath();
                        mutationBefore = FILE_MUTATIONS.observe(requested, path);
                        observed = Files.readAttributes(path, BasicFileAttributes.class);
                    }
                    attributes = observed;
                } catch (NoSuchFileException missing) {
                    if (!allowMissing) throw missing;
                    Path absent = canonicalCandidatePath(requested);
                    OtgFileMutationTracker.Certificate before = FILE_MUTATIONS.observe(requested, absent);
                    if (!isMissing(absent)) continue;
                    OtgFileMutationTracker.Certificate after = FILE_MUTATIONS.observe(requested, absent);
                    if (generation == CONFIGURATION_GENERATION.get()
                        && before.stableThrough(after)) {
                        return new FileProbe(requested, absent, false, -1L,
                            FileTime.fromMillis(0L), "<missing>", generation,
                            after);
                    }
                    continue;
                }

                OtgFileMutationTracker.Certificate mutationAfter = FILE_MUTATIONS.observe(requested, path);
                if (attributes.isRegularFile()
                    && generation == CONFIGURATION_GENERATION.get()
                    && mutationBefore.stableThrough(mutationAfter)) {
                    return new FileProbe(requested, path, true, attributes.size(),
                        attributes.lastModifiedTime(), fileKey(attributes),
                        generation, mutationAfter);
                }
            }
            throw new IOException("OTG file changed while observing: " + requested);
        }

        private FileProbe reobserve() throws IOException {
            FILE_AUTHENTICATION_PROBES.incrementAndGet();
            for (int attempt = 0; attempt < 3; attempt++) {
                long currentGeneration = CONFIGURATION_GENERATION.get();
                OtgFileMutationTracker.Certificate beforeWatch = FILE_MUTATIONS.observeKnown(watches);
                if (exists) {
                    final BasicFileAttributes attributes;
                    try {
                        attributes = Files.readAttributes(path, BasicFileAttributes.class);
                    } catch (NoSuchFileException changed) {
                        continue;
                    }
                    OtgFileMutationTracker.Certificate afterWatch = FILE_MUTATIONS.observeKnown(watches);
                    if (attributes.isRegularFile()
                        && currentGeneration == CONFIGURATION_GENERATION.get()
                        && beforeWatch.stableThrough(afterWatch)) {
                        return new FileProbe(logicalPath, path, true, attributes.size(),
                            attributes.lastModifiedTime(), fileKey(attributes),
                            currentGeneration, afterWatch);
                    }
                } else {
                    if (!isMissing(path)) continue;
                    OtgFileMutationTracker.Certificate afterWatch = FILE_MUTATIONS.observeKnown(watches);
                    if (currentGeneration == CONFIGURATION_GENERATION.get()
                        && beforeWatch.stableThrough(afterWatch)) {
                        return new FileProbe(logicalPath, path, false, -1L,
                            FileTime.fromMillis(0L), "<missing>",
                            currentGeneration, afterWatch);
                    }
                }
            }
            throw new IOException("OTG file changed while authenticating: " + path);
        }

        private static Path logicalPath(File file) throws IOException {
            if (file == null) throw new IOException("null OTG file");
            return file.toPath().toAbsolutePath().normalize();
        }

        private static Path canonicalCandidatePath(Path requested) throws IOException {
            Path parent = requested.getParent();
            Path name = requested.getFileName();
            if (parent == null || name == null) {
                throw new IOException("OTG path has no watchable parent: " + requested);
            }
            return FILE_MUTATIONS.canonicalDirectory(parent).resolve(name);
        }

        private static boolean isMissing(Path candidate) throws IOException {
            try {
                Files.readAttributes(candidate, BasicFileAttributes.class);
                return false;
            } catch (NoSuchFileException missing) {
                return true;
            }
        }

        private boolean stableThrough(FileProbe other) {
            if (other == null || !sameFastIdentity(other)) return false;
            return watches.stableThrough(other.watches);
        }

        private boolean sameFastIdentity(FileProbe other) {
            return exists == other.exists && size == other.size
                && generation == other.generation
                && logicalPath.equals(other.logicalPath) && path.equals(other.path)
                && modified.equals(other.modified) && fileKey.equals(other.fileKey);
        }

        private boolean reusableNow() {
            return generation == CONFIGURATION_GENERATION.get()
                && watches.current();
        }

        private boolean watched() {
            return watches.complete;
        }

        private static String fileKey(BasicFileAttributes attributes) {
            Object key = attributes.fileKey();
            return key == null ? "<null>" : key.toString();
        }
    }

    private static final class FileStamp {
        private final FileProbe probe;
        private final long generation;

        private FileStamp(FileProbe probe) {
            this.probe = probe;
            this.generation = probe.generation;
        }

        private static FileStamp authenticate(FileProbe before, long sourceBytes)
            throws IOException {
            if (before == null || !before.exists || before.size != sourceBytes) {
                throw new IOException("OTG settings source did not match its observation");
            }
            FileProbe after = before.reobserve();
            if (!before.stableThrough(after) || after.size != sourceBytes) {
                throw new IOException("OTG file changed while reading: " + before.path);
            }
            return new FileStamp(after);
        }

        private static FileStamp authenticate(FileProbe before) throws IOException {
            if (before == null) throw new IOException("missing OTG file observation");
            FileProbe after = before.reobserve();
            if (!before.stableThrough(after)) {
                throw new IOException("OTG file changed while reading: "
                    + before.path);
            }
            return new FileStamp(after);
        }

        private boolean reusableNow() {
            return probe.reusableNow();
        }

        private boolean watched() {
            return probe.watched();
        }

    }

    private static final class SettingsLookupKey {
        private final Path logicalPath;
        private final Locale locale;
        private final Charset charset;

        private SettingsLookupKey(Path logicalPath, Locale locale, Charset charset) {
            this.logicalPath = logicalPath;
            this.locale = locale;
            this.charset = charset;
        }

        private static SettingsLookupKey capture(File file) throws IOException {
            return new SettingsLookupKey(FileProbe.logicalPath(file),
                Locale.getDefault(), Charset.defaultCharset());
        }

        @Override public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof SettingsLookupKey)) return false;
            SettingsLookupKey other = (SettingsLookupKey) value;
            return logicalPath.equals(other.logicalPath) && locale.equals(other.locale)
                && charset.equals(other.charset);
        }

        @Override public int hashCode() {
            return 31 * (31 * logicalPath.hashCode() + locale.hashCode())
                + charset.hashCode();
        }
    }

    private static final class SettingsEntry {
        private final FileStamp stamp;
        private final OtgSettingsSnapshot snapshot;

        private SettingsEntry(FileStamp stamp, OtgSettingsSnapshot snapshot) {
            this.stamp = stamp;
            this.snapshot = snapshot;
        }
    }

    private static final class SettingsAccess {
        private final Constructor<?> lineConstructor;
        private final Field settingsField;
        private final Field functionsField;

        private SettingsAccess(Class<?> owner) throws ReflectiveOperationException {
            ClassLoader loader = owner.getClassLoader();
            Class<?> lineClass = Class.forName(owner.getName() + "$StringOnLine", false, loader);
            lineConstructor = lineClass.getDeclaredConstructor(String.class, int.class);
            lineConstructor.setAccessible(true);
            settingsField = owner.getDeclaredField("settingsCache");
            settingsField.setAccessible(true);
            functionsField = owner.getDeclaredField("configFunctions");
            functionsField.setAccessible(true);
        }

        @SuppressWarnings("unchecked")
        private void populate(Object target, OtgSettingsSnapshot snapshot) {
            try {
                List<Object> functions = new ArrayList<Object>(snapshot.functions.size());
                for (LineRecord line : snapshot.functions) functions.add(newLine(line));

                List<Map.Entry<String, Object>> settings =
                    new ArrayList<Map.Entry<String, Object>>(snapshot.settings.size());
                for (SettingRecord setting : snapshot.settings) {
                    settings.add(new SimpleImmutableEntry<String, Object>(setting.key,
                        newLine(setting)));
                }

                Object rawSettings = settingsField.get(target);
                Object rawFunctions = functionsField.get(target);
                if (rawSettings == null || rawSettings.getClass() != HashMap.class
                    || rawFunctions == null
                    || rawFunctions.getClass() != ArrayList.class) {
                    throw new IllegalStateException("OTG settings containers changed type");
                }

                // All reflection and allocation completed before either target
                // container is mutated, so a setup failure cannot create a
                // partial optimized result followed by an original retry.
                List<Object> targetFunctions = (List<Object>) rawFunctions;
                Map<String, Object> targetSettings = (Map<String, Object>) rawSettings;
                targetFunctions.addAll(functions);
                for (Map.Entry<String, Object> setting : settings) {
                    targetSettings.put(setting.getKey(), setting.getValue());
                }
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Unable to populate OTG settings", error);
            }
        }

        private Object newLine(LineRecord line) throws ReflectiveOperationException {
            return lineConstructor.newInstance(line.value, Integer.valueOf(line.line));
        }
    }

    private static final class Node {
        private final int type;
        private final String name;
        private final int listType;
        private final Object scalar;
        private final Node[] children;
        private final int weight;

        private Node(int type, String name, int listType, Object scalar, Node[] children) {
            this.type = type;
            this.name = name;
            this.listType = listType;
            this.scalar = scalar;
            this.children = children;
            long bytes = 64L + (name == null ? 0L : (long) name.length() * 2L);
            if (scalar instanceof String) bytes += (long) ((String) scalar).length() * 2L;
            if (scalar instanceof byte[]) bytes += ((byte[]) scalar).length;
            if (scalar instanceof int[]) bytes += (long) ((int[]) scalar).length * 4L;
            if (children != null) {
                bytes += (long) children.length * 8L;
                for (Node child : children) bytes += child.weight;
            }
            this.weight = (int) Math.min(Integer.MAX_VALUE, bytes);
        }
    }

    private static final class TagAccess {
        private final Class<?> tagClass;
        private final Object[] types;
        private final Constructor<?> constructor;
        private final Method getType;
        private final Method getName;
        private final Method getValue;
        private final Method getListType;

        private TagAccess(Class<?> tagClass) throws ReflectiveOperationException {
            this.tagClass = tagClass;
            Class<?> typeClass = Class.forName(tagClass.getName() + "$Type", false,
                tagClass.getClassLoader());
            types = typeClass.getEnumConstants();
            if (types == null || types.length != 12) {
                throw new NoSuchFieldException("NamedBinaryTag.Type values");
            }
            constructor = tagClass.getConstructor(typeClass, String.class, Object.class);
            getType = tagClass.getMethod("getType");
            getName = tagClass.getMethod("getName");
            getValue = tagClass.getMethod("getValue");
            getListType = tagClass.getMethod("getListType");
        }

        private Object requireType(int ordinal) {
            return types[ordinal];
        }

        private Object materialize(Node node) throws ReflectiveOperationException {
            Object payload;
            if (node.type == 9 || node.type == 10) {
                if (node.type == 9 && node.children.length == 0) {
                    payload = requireType(node.listType);
                } else {
                    Object array = Array.newInstance(tagClass, node.children.length);
                    for (int index = 0; index < node.children.length; index++) {
                        Array.set(array, index, materialize(node.children[index]));
                    }
                    payload = array;
                }
            } else if (node.type == 7) {
                payload = ((byte[]) node.scalar).clone();
            } else if (node.type == 11) {
                payload = ((int[]) node.scalar).clone();
            } else {
                payload = node.scalar;
            }
            return constructor.newInstance(requireType(node.type), node.name, payload);
        }

        private Node capture(Object tag) throws ReflectiveOperationException {
            if (tag == null || tag.getClass() != tagClass) {
                throw new IllegalArgumentException("Unexpected OTG tag type");
            }
            int type = ((Enum<?>) getType.invoke(tag)).ordinal();
            String name = (String) getName.invoke(tag);
            Object value = getValue.invoke(tag);
            switch (type) {
                case 7:
                    return new Node(type, name, -1, ((byte[]) value).clone(), null);
                case 9: {
                    int listType = ((Enum<?>) getListType.invoke(tag)).ordinal();
                    return new Node(type, name, listType, null, captureArray(value));
                }
                case 10:
                    return new Node(type, name, -1, null, captureArray(value));
                case 11:
                    return new Node(type, name, -1, ((int[]) value).clone(), null);
                default:
                    return new Node(type, name, -1, value, null);
            }
        }

        private Node[] captureArray(Object value) throws ReflectiveOperationException {
            int length = Array.getLength(value);
            Node[] result = new Node[length];
            for (int index = 0; index < length; index++) {
                result[index] = capture(Array.get(value, index));
            }
            return result;
        }
    }

    private static final class ParsedTag {
        private final Object tag;
        private final Node blueprint;

        private ParsedTag(Object tag, Node blueprint) {
            this.tag = tag;
            this.blueprint = blueprint;
        }
    }

    private static final class MetadataEntry {
        private final FileStamp stamp;
        private final Node blueprint;
        private final int weight;

        private MetadataEntry(FileStamp stamp, Node blueprint) {
            this.stamp = stamp;
            this.blueprint = blueprint;
            // A null blueprint is an intentional negative entry. It must still
            // have a non-zero bounded weight or Caffeine rejects the entry and
            // silently turns missing/failed NBT into repeated disk reads.
            this.weight = blueprint == null ? 64 : blueprint.weight;
        }
    }

    private static final class PendingMetadata {
        private final String requestedPath;
        private final Path logicalPath;
        private final FileProbe probe;
        private final MetadataEntry hit;

        private PendingMetadata(String requestedPath, Path logicalPath,
                                FileProbe probe, MetadataEntry hit) {
            this.requestedPath = requestedPath;
            this.logicalPath = logicalPath;
            this.probe = probe;
            this.hit = hit;
        }
    }

    private static final class MetadataMap extends AbstractMap<String, Object> {
        private final TagAccess access;
        private final Map<String, Object> original = new HashMap<String, Object>();
        private final Cache<Path, MetadataEntry> safe;
        private final ThreadLocal<PendingMetadata> pending = new ThreadLocal<PendingMetadata>();

        private MetadataMap(Class<?> tagClass) {
            access = TAG_ACCESS.get(tagClass);
            safe = Caffeine.newBuilder()
                .maximumWeight(METADATA_CACHE_BYTES)
                .weigher(new Weigher<Path, MetadataEntry>() {
                    @Override public int weigh(Path key, MetadataEntry value) {
                        return value.weight;
                    }
                })
                .build();
        }

        @Override public boolean containsKey(Object rawKey) {
            LAST_PARSED_TAG.remove();
            pending.remove();
            if (!enabled()) return original.containsKey(rawKey);
            if (original.containsKey(rawKey)) return true;
            if (!(rawKey instanceof String)) return false;
            String key = (String) rawKey;
            try {
                Path logicalPath = FileProbe.logicalPath(new File(key));
                MetadataEntry entry = safe.getIfPresent(logicalPath);
                if (entry == null) entry = PREPARED_METADATA.getIfPresent(logicalPath);
                if (entry != null && entry.stamp.reusableNow()) {
                    pending.set(new PendingMetadata(key, logicalPath, null, entry));
                    return true;
                }
                if (entry != null) {
                    // Conditional removal cannot evict a concurrently
                    // published replacement for the same logical path.
                    safe.asMap().remove(logicalPath, entry);
                }

                FileProbe probe = FileProbe.capture(logicalPath, true);
                pending.set(new PendingMetadata(key, logicalPath, probe, null));
                return false;
            } catch (IOException | RuntimeException error) {
                FatalErrors.rethrowIfFatal(error);
                pending.remove();
                return false;
            }
        }

        @Override public Object get(Object rawKey) {
            PendingMetadata observed = pending.get();
            pending.remove();
            boolean pairedHit = observed != null && observed.hit != null
                && observed.requestedPath.equals(rawKey);
            // A runtime pause must not split a successful containsKey/get
            // pair into a spurious null. Only that one accepted hit completes;
            // subsequent lookups use the original map until resumed.
            if (!enabled() && !pairedHit) return original.get(rawKey);
            if (original.containsKey(rawKey)) return original.get(rawKey);
            if (!(rawKey instanceof String)) return null;
            String key = (String) rawKey;
            try {
                Path logicalPath = pairedHit
                    ? observed.logicalPath : FileProbe.logicalPath(new File(key));
                MetadataEntry entry = pairedHit ? observed.hit : currentEntry(logicalPath);
                if (entry == null) return null;
                // containsKey() already gives a paired hit its filesystem
                // linearization point. A later directory event may invalidate
                // the next lookup, but must not turn this coherent contains/get
                // pair into a spurious null. Configuration reload remains a
                // stronger semantic boundary and is rechecked below.
                if (entry.stamp.generation != CONFIGURATION_GENERATION.get()) {
                    safe.asMap().remove(logicalPath, entry);
                    return null;
                }
                Object copy = entry.blueprint == null ? null
                    : access.materialize(entry.blueprint);
                synchronized (OtgSynchronousIoBridge.class) {
                    // A containsKey/get pair may straddle a configuration
                    // reload. Authenticate again under the same monitor used
                    // by generation advancement after the potentially large
                    // deep copy, giving the hit one exact linearization point
                    // without blocking reload for the duration of that copy.
                    if (entry.stamp.generation
                        != CONFIGURATION_GENERATION.get()) {
                        safe.asMap().remove(logicalPath, entry);
                        return null;
                    }
                    success();
                    return copy;
                }
            } catch (IOException error) {
                return null;
            } catch (ReflectiveOperationException | RuntimeException error) {
                fail(error);
                throw new IllegalStateException("Unable to deep-copy cached OTG metadata", error);
            }
        }

        @Override public Object put(String key, Object value) {
            PendingMetadata before = pending.get();
            pending.remove();
            ParsedTag parsed = LAST_PARSED_TAG.get();
            LAST_PARSED_TAG.remove();
            if (!enabled()) return original.put(key, value);
            if (key == null || before == null
                || before.probe == null || !key.equals(before.requestedPath)) {
                return original.put(key, value);
            }
            if (before.probe.generation != CONFIGURATION_GENERATION.get()) return null;
            try {
                Node blueprint = value == null ? null
                    : parsed != null && parsed.tag == value
                        ? parsed.blueprint : access.capture(value);
                // The optimized parser already materialized this exact node
                // shape. Values supplied externally still need that proof.
                if (blueprint != null && (parsed == null || parsed.tag != value)) {
                    access.materialize(blueprint);
                }
                FileStamp after = FileStamp.authenticate(before.probe);
                if (!after.watched()) {
                    return original.put(key, value);
                }
                synchronized (OtgSynchronousIoBridge.class) {
                    // advanceConfigurationGeneration uses this same monitor.
                    // The generation therefore cannot change between this
                    // final authentication and publication.
                    if (after.generation != CONFIGURATION_GENERATION.get()) return null;
                    if (!after.reusableNow()) return original.put(key, value);
                    safe.put(before.logicalPath, new MetadataEntry(after, blueprint));
                    success();
                    return null;
                }
            } catch (IOException error) {
                if (before.probe.generation != CONFIGURATION_GENERATION.get()) return null;
                // Authentication is an ICE-only precondition. If it cannot be
                // completed, retain the target HashMap's exact fail-open
                // behavior instead of losing the value or repeatedly reading.
                return original.put(key, value);
            } catch (ReflectiveOperationException | RuntimeException error) {
                fail(error);
                return original.put(key, value);
            }
        }

        @Override public void clear() {
            original.clear();
            safe.invalidateAll();
            PREPARED_METADATA.invalidateAll();
            pending.remove();
            LAST_PARSED_TAG.remove();
        }

        @Override public Set<Entry<String, Object>> entrySet() {
            return original.entrySet();
        }

        private MetadataEntry currentEntry(Path logicalPath) {
            try {
                MetadataEntry entry = safe.getIfPresent(logicalPath);
                if (entry == null) entry = PREPARED_METADATA.getIfPresent(logicalPath);
                if (entry == null) return null;
                if (entry.stamp.reusableNow()) return entry;
                safe.asMap().remove(logicalPath, entry);
                return null;
            } catch (RuntimeException error) {
                FatalErrors.rethrowIfFatal(error);
                return null;
            }
        }

        private void invalidateSafeEntries() {
            original.clear();
            safe.invalidateAll();
            // ThreadLocal lookup state cannot be cleared for other producer
            // threads here. Keep the current thread's state as well so put()
            // can observe its old generation and reject publication uniformly.
        }
    }

    private static final class SourceFallback extends RuntimeException {
        private static final SourceFallback INSTANCE = new SourceFallback();

        private SourceFallback() {
            super(null, null, false, false);
        }
    }
}
