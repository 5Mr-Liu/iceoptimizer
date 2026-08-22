package dev.rlcraft.ice.optimizer.render.texture;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.memory.RetainedHeap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded, order-preserving PBO candidate stream with exact horizontal merge. */
public final class TextureUploadStream implements AutoCloseable {
    private final int maximumCommands;
    private final long maximumBytes;
    private TextureUpload[] commands;
    private CacheBudget.Reservation heapReservation;
    private int size;
    private long bytes;
    private long rejected;
    private long stale;
    private long merged;

    public TextureUploadStream(int maximumCommands, long maximumBytes) {
        this(maximumCommands, maximumBytes, null);
    }

    /** Production constructor which charges the fixed command reference array. */
    public TextureUploadStream(int maximumCommands, long maximumBytes,
                               CacheBudget budget) {
        this.maximumCommands = Math.max(16, maximumCommands);
        this.maximumBytes = Math.max(4096L, maximumBytes);
        CacheBudget.Reservation reservation = RetainedHeap.reserve(budget,
            heapBytesForCapacity(this.maximumCommands), "texture upload stream");
        try {
            commands = new TextureUpload[this.maximumCommands];
            heapReservation = reservation;
        } catch (RuntimeException | Error failure) {
            reservation.close();
            throw failure;
        }
    }

    public boolean offer(TextureUpload upload) {
        checkOpen();
        if (upload == null) return false;
        if (size != 0) {
            TextureUpload previous = commands[size - 1];
            if (previous.canMergeRight(upload)) {
                TextureUpload replacement;
                try { replacement = previous.mergeRight(upload); }
                catch (ArithmeticException overflow) {
                    rejected++;
                    return false;
                }
                long nextBytes = bytes - previous.getByteCount()
                    + replacement.getByteCount();
                if (nextBytes > maximumBytes) {
                    rejected++;
                    return false;
                }
                commands[size - 1] = replacement;
                bytes = nextBytes;
                merged++;
                return true;
            }
        }
        if (size >= maximumCommands
            || upload.getByteCount() > maximumBytes - bytes) {
            rejected++;
            return false;
        }
        commands[size++] = upload;
        bytes += upload.getByteCount();
        return true;
    }

    public List<TextureUpload> drain(long resourceGeneration,
                                     long atlasGeneration) {
        checkOpen();
        ArrayList<TextureUpload> result = new ArrayList<TextureUpload>(size);
        for (int index = 0; index < size; index++) {
            TextureUpload upload = commands[index];
            if (upload.getResourceGeneration() == resourceGeneration
                && upload.getAtlasGeneration() == atlasGeneration) {
                result.add(upload);
            } else {
                stale++;
            }
            commands[index] = null;
        }
        size = 0;
        bytes = 0L;
        return Collections.unmodifiableList(result);
    }

    public int size() { return size; }
    public long getBytes() { return bytes; }
    public long getRejected() { return rejected; }
    public long getStale() { return stale; }
    public long getMerged() { return merged; }
    public boolean isClosed() { return commands == null; }

    @Override public void close() {
        TextureUpload[] owned = commands;
        if (owned == null) return;
        for (int index = 0; index < size; index++) owned[index] = null;
        commands = null;
        size = 0;
        bytes = 0L;
        CacheBudget.Reservation reservation = heapReservation;
        heapReservation = null;
        if (reservation != null) reservation.close();
    }

    public static long heapBytesForCapacity(int requestedCapacity) {
        return RetainedHeap.referenceArray(Math.max(16, requestedCapacity));
    }

    private void checkOpen() {
        if (commands == null) throw new IllegalStateException(
            "texture upload stream is closed");
    }
}
