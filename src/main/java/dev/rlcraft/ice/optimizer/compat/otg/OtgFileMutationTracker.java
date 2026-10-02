package dev.rlcraft.ice.optimizer.compat.otg;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One watcher; ordinary file changes invalidate only certificates for that file. */
final class OtgFileMutationTracker {
    private final WatchService watcher;
    private final Map<Path, DirectoryWatch> directories = new HashMap<Path, DirectoryWatch>();
    // Registering aliases of the same directory can return the same WatchKey.
    private final Map<WatchKey, List<DirectoryWatch>> keys =
        new HashMap<WatchKey, List<DirectoryWatch>>();
    private long sequence;

    OtgFileMutationTracker() {
        WatchService created = null;
        try { created = FileSystems.getDefault().newWatchService(); }
        catch (IOException | RuntimeException unavailable) { /* Unwatched reads never reuse. */ }
        watcher = created;
    }

    synchronized Certificate observe(Path source, Path target) {
        drain();
        if (watcher == null || source == null || target == null) return Certificate.incomplete();
        Path sourceParent = source.getParent();
        Path targetParent = target.getParent();
        DirectoryWatch sourceWatch = register(sourceParent, false);
        DirectoryWatch targetWatch = sourceParent.equals(targetParent)
            ? sourceWatch : register(targetParent, false);
        drain();
        return active(sourceWatch) && (sourceWatch == targetWatch || active(targetWatch))
            ? new Certificate(this, sourceWatch, token(sourceWatch, source.getFileName()),
                targetWatch, token(targetWatch, target.getFileName()))
            : Certificate.incomplete();
    }

    synchronized Certificate observeKnown(Certificate expected) {
        drain();
        if (expected == null || !expected.complete
            || !active(expected.source) || expected.target != expected.source && !active(expected.target)) {
            return Certificate.incomplete();
        }
        return new Certificate(this, expected.source, expected.sourceFile,
            expected.target, expected.targetFile);
    }

    synchronized boolean isCurrent(Certificate expected) {
        drain();
        return current(expected);
    }

    private boolean current(Certificate expected) {
        return expected != null && expected.complete
            && active(expected.source) && (expected.source == expected.target || active(expected.target))
            && expected.sourceSequence == expected.source.sequence
            && expected.targetSequence == expected.target.sequence
            && expected.sourceFileSequence == expected.sourceFile.contentSequence
            && expected.targetFileSequence == expected.targetFile.contentSequence;
    }

    synchronized Path canonicalDirectory(Path directory) throws IOException {
        drain();
        DirectoryWatch source = register(directory, false);
        if (active(source) && source.canonicalPath != null
            && source.canonicalSequence == source.sequence) return source.canonicalPath;
        Path canonical = directory.toRealPath();
        boolean alias = !directory.equals(canonical);
        if (alias) {
            // Authenticate only the alias-dependent suffix. Do not upgrade a
            // normal common ancestor and invalidate unrelated cached folders.
            Path parent = directory;
            Path actual = canonical;
            while (parent.getParent() != null && !parent.equals(actual)) {
                register(parent, true);
                parent = parent.getParent();
                actual = parent.toRealPath();
            }
            source = register(directory, true);
        }
        drain();
        // An alias is resolved on every cold lookup; its parent chain also
        // authenticates hot certificates when an existing target stays alive.
        if (!alias && active(source)) {
            source.canonicalPath = canonical;
            source.canonicalSequence = source.sequence;
        }
        return canonical;
    }

    private DirectoryWatch register(Path directory, boolean aliasSensitive) {
        if (watcher == null || directory == null) return null;
        DirectoryWatch state = directories.get(directory);
        if (state != null && (!active(state) || aliasSensitive && !state.aliasSensitive)) {
            forget(state);
            state = null;
        }
        if (state != null) return state;
        try {
            Path parent = directory.getParent();
            DirectoryWatch parentWatch = parent == null ? null : register(parent, false);
            if (parent != null && !active(parentWatch)) return null;
            WatchKey key = directory.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);
            FileToken parentName = parentWatch == null ? null
                : token(parentWatch, directory.getFileName());
            state = new DirectoryWatch(directory, key, nextSequence(), parentWatch,
                parentName, aliasSensitive);
            directories.put(directory, state);
            List<DirectoryWatch> owners = keys.get(key);
            if (owners == null) {
                owners = new ArrayList<DirectoryWatch>(1);
                keys.put(key, owners);
            }
            owners.add(state);
            return state;
        } catch (IOException | RuntimeException unavailable) { return null; }
    }

    private boolean active(DirectoryWatch state) {
        if (state == null || !state.alive || !state.key.isValid()) return false;
        if (state.parent == null) return true;
        return active(state.parent) && state.parentSequence == state.parent.sequence
            && state.parentFileSequence == state.parentFileSequenceNow();
    }

    private void forget(DirectoryWatch state) {
        state.alive = false;
        if (directories.get(state.path) == state) directories.remove(state.path);
        List<DirectoryWatch> owners = keys.get(state.key);
        if (owners != null) {
            owners.remove(state);
            if (owners.isEmpty()) keys.remove(state.key);
        }
        // Do not cancel: another alias or a subsequent registration can share
        // this live key. A key without owners is harmless and reset by drain.
    }

    private static FileToken token(DirectoryWatch directory, Path name) {
        FileToken token = directory.files.get(name);
        if (token == null) {
            token = new FileToken();
            directory.files.put(name, token);
        }
        return token;
    }

    private void drain() {
        if (watcher == null) return;
        WatchKey key;
        while ((key = watcher.poll()) != null) {
            List<DirectoryWatch> owners = keys.get(key);
            List<WatchEvent<?>> events = key.pollEvents();
            if (owners != null) {
                for (WatchEvent<?> event : events) {
                    Object context = event.context();
                    WatchEvent.Kind<?> kind = event.kind();
                    boolean known = (kind == StandardWatchEventKinds.ENTRY_CREATE
                        || kind == StandardWatchEventKinds.ENTRY_DELETE
                        || kind == StandardWatchEventKinds.ENTRY_MODIFY)
                        && context instanceof Path && !((Path) context).isAbsolute()
                        && ((Path) context).getNameCount() == 1;
                    for (DirectoryWatch owner : owners) {
                        if (!known) { owner.sequence = nextSequence(); continue; }
                        FileToken file = owner.files.get(context);
                        if (file == null) continue;
                        file.contentSequence = nextSequence();
                        if (kind != StandardWatchEventKinds.ENTRY_MODIFY) {
                            file.identitySequence = file.contentSequence;
                        }
                    }
                }
            }
            if (!key.reset() && owners != null) {
                for (DirectoryWatch owner : owners) {
                    owner.alive = false;
                    if (directories.get(owner.path) == owner) directories.remove(owner.path);
                }
                keys.remove(key);
            }
        }
    }

    private long nextSequence() { sequence = sequence == Long.MAX_VALUE ? 1L : sequence + 1L; return sequence; }

    static final class Certificate {
        private final OtgFileMutationTracker tracker;
        private final DirectoryWatch source;
        private final DirectoryWatch target;
        private final FileToken sourceFile;
        private final FileToken targetFile;
        private final long sourceSequence;
        private final long targetSequence;
        private final long sourceFileSequence;
        private final long targetFileSequence;
        final boolean complete;
        private Certificate(OtgFileMutationTracker tracker, DirectoryWatch source,
                            FileToken sourceFile, DirectoryWatch target, FileToken targetFile) {
            this.tracker = tracker;
            this.source = source;
            this.target = target;
            this.sourceFile = sourceFile;
            this.targetFile = targetFile;
            sourceSequence = source == null ? 0L : source.sequence;
            targetSequence = target == null ? 0L : target.sequence;
            sourceFileSequence = sourceFile == null ? 0L : sourceFile.contentSequence;
            targetFileSequence = targetFile == null ? 0L : targetFile.contentSequence;
            complete = tracker != null;
        }
        static Certificate incomplete() { return new Certificate(null, null, null, null, null); }
        boolean stableThrough(Certificate other) {
            if (other == null || complete != other.complete) return false;
            if (!complete) return true;
            return source == other.source && target == other.target
                && sourceFile == other.sourceFile && targetFile == other.targetFile
                && sourceSequence == other.sourceSequence && targetSequence == other.targetSequence
                && sourceFileSequence == other.sourceFileSequence
                && targetFileSequence == other.targetFileSequence;
        }
        boolean current() { return complete && tracker.isCurrent(this); }
    }

    private static final class FileToken {
        private long contentSequence;
        private long identitySequence;
    }

    private static final class DirectoryWatch {
        private final Path path;
        private final WatchKey key;
        private final DirectoryWatch parent;
        private final FileToken parentFile;
        private final boolean aliasSensitive;
        private final long parentSequence;
        private final long parentFileSequence;
        private final Map<Path, FileToken> files = new HashMap<Path, FileToken>();
        private boolean alive = true;
        private long sequence;
        private Path canonicalPath;
        private long canonicalSequence;
        DirectoryWatch(Path path, WatchKey key, long sequence, DirectoryWatch parent,
                       FileToken parentFile, boolean aliasSensitive) {
            this.path = path;
            this.key = key;
            this.sequence = sequence;
            this.parent = parent;
            this.parentFile = parentFile;
            this.aliasSensitive = aliasSensitive;
            parentSequence = parent == null ? 0L : parent.sequence;
            parentFileSequence = parentFileSequenceNow();
        }
        long parentFileSequenceNow() {
            return parentFile == null ? 0L : aliasSensitive
                ? parentFile.contentSequence : parentFile.identitySequence;
        }
    }
}
