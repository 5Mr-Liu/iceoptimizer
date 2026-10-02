package dev.rlcraft.ice.optimizer.compat.otg;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/** Real filesystem events, including watcher failure and an old alias target kept alive. */
public final class OtgFileMutationTrackerTest {
    private Path root;
    private OtgFileMutationTracker tracker;
    @Before public void open() throws Exception {
        Path base = Paths.get(".refactor-backups/1.0.13-otg-fixtures").toAbsolutePath();
        Files.createDirectories(base);
        root = Files.createTempDirectory(base, "watch-");
        tracker = new OtgFileMutationTracker();
    }
    @After public void close() throws Exception {
        ((WatchService) field(tracker, "watcher")).close();
        Files.deleteIfExists(root.resolve("alias"));
        try (Stream<Path> files = Files.walk(root)) {
            for (Path path : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
        }
    }
    @Test public void unrelatedCreationModificationAndDeletionKeepTheOriginalFileCurrent() throws Exception {
        Path original = root.resolve("original.bo4");
        Files.write(original, "Value: stable\n".getBytes(StandardCharsets.UTF_8));
        OtgFileMutationTracker.Certificate retained = tracker.observe(original, original);
        Path other = root.resolve("other.bo4");
        OtgFileMutationTracker.Certificate otherBefore = tracker.observe(other, other);
        Files.write(other, "Value: first\n".getBytes(StandardCharsets.UTF_8));
        awaitInvalidation(otherBefore);
        assertTrue(retained.current());
        otherBefore = tracker.observe(other, other);
        Files.write(other, "Value: other\n".getBytes(StandardCharsets.UTF_8));
        awaitInvalidation(otherBefore);
        assertTrue(retained.current());
        otherBefore = tracker.observe(other, other);
        Files.delete(other);
        awaitInvalidation(otherBefore);
        assertTrue(retained.current());
    }
    @Test public void directoryReplacementInvalidatesEvenIfTheOldDirectoryStaysAlive() throws Exception {
        Path child = Files.createDirectory(root.resolve("child"));
        Path file = child.resolve("object.bo4");
        Files.write(file, new byte[] { 1 });
        OtgFileMutationTracker.Certificate before = tracker.observe(file, file);
        Files.move(child, root.resolve("old-child"));
        Files.createDirectory(child);
        Files.write(file, new byte[] { 2 });
        awaitInvalidation(before);
        assertTrue(tracker.observe(file, file).current());
    }
    @Test public void overflowInvalidatesEveryFileInThatWatch() throws Exception {
        Path first = root.resolve("first.bo4");
        Path second = root.resolve("second.bo4");
        OtgFileMutationTracker.Certificate a = tracker.observe(first, first);
        OtgFileMutationTracker.Certificate b = tracker.observe(second, second);
        Object directory = field(a, "source");
        WatchKey key = (WatchKey) field(directory, "key");
        Method signal = null;
        for (Class<?> type = key.getClass(); type != null && signal == null; type = type.getSuperclass()) {
            try { signal = type.getDeclaredMethod("signalEvent", WatchEvent.Kind.class, Object.class); }
            catch (NoSuchMethodException ignored) { }
        }
        Assume.assumeTrue("actual WatchKey has no injectable overflow event", signal != null);
        signal.setAccessible(true);
        signal.invoke(key, StandardWatchEventKinds.OVERFLOW, null);
        awaitInvalidation(a);
        assertFalse(b.current());
        assertTrue(tracker.observe(first, first).current());
    }
    @Test public void cancelledWatchNeverCertifiesAHistoricalEntry() throws Exception {
        Path file = root.resolve("object.bo4");
        OtgFileMutationTracker.Certificate before = tracker.observe(file, file);
        WatchKey key = (WatchKey) field(field(before, "source"), "key");
        key.cancel();
        assertFalse(before.current());
        assertTrue(tracker.observe(file, file).current());
    }
    @Test public void retargetingAnAliasInvalidatesItsAlreadyCachedFile() throws Exception {
        Path first = Files.createDirectory(root.resolve("first"));
        Path next = Files.createDirectory(root.resolve("next"));
        Path firstFile = first.resolve("object.bo4");
        Path nextFile = next.resolve("object.bo4");
        Files.write(firstFile, new byte[] { 1 });
        Files.write(nextFile, new byte[] { 2 });
        Path alias = root.resolve("alias");
        try { OtgTestPaths.createDirectoryAlias(alias, first); }
        catch (IOException | UnsupportedOperationException unavailable) { Assume.assumeNoException(unavailable); }
        Path logical = alias.resolve("object.bo4");
        Path actual = tracker.canonicalDirectory(alias).resolve(logical.getFileName());
        OtgFileMutationTracker.Certificate before = tracker.observe(logical, actual);
        assertTrue(before.current());
        Files.delete(alias);
        OtgTestPaths.createDirectoryAlias(alias, next);
        awaitInvalidation(before);
        assertTrue(Files.isDirectory(first));
        actual = tracker.canonicalDirectory(alias).resolve(logical.getFileName());
        assertTrue(actual.equals(nextFile));
        assertTrue(tracker.observe(logical, actual).current());
    }
    private static void awaitInvalidation(OtgFileMutationTracker.Certificate before) throws Exception {
        assertTrue(before.complete);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (before.current() && System.nanoTime() < deadline) TimeUnit.MILLISECONDS.sleep(1);
        assertFalse("event must be delivered to the original certificate", before.current());
    }
    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
}
