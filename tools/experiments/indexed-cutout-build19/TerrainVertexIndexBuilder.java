package dev.rlcraft.ice.optimizer.render.terrain;

import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.lwjgl.BufferUtils;

/** Bounded render-thread scratch for exact 28-byte vertex identity. */
final class TerrainVertexIndexBuilder implements AutoCloseable {
    static final int MAX_VERTICES = 131072;
    private final CacheBudget budget;
    private int[] slots, indices;
    private ByteBuffer output;
    private CacheBudget.Reservation heap, direct;

    TerrainVertexIndexBuilder(CacheBudget budget) {
        if (budget == null) throw new IllegalArgumentException("index budget");
        this.budget = budget;
    }

    /** Result bytes belong to this workspace until the next encode/close. */
    Result encode(ByteBuffer source) {
        if (source == null || source.position() != 0 || source.limit() % 112 != 0) return null;
        int bytes = source.limit(), count = bytes / 28;
        if (count < 64 || count > MAX_VERTICES || !ensureCapacity(count)) return null;
        ByteBuffer input = source.duplicate().order(ByteOrder.nativeOrder());
        int tableSize = 1;
        while (tableSize < count * 2) tableSize <<= 1;
        Arrays.fill(slots, 0, tableSize, 0);
        output.clear();
        int unique = 0;
        for (int vertex = 0; vertex < count; vertex++) {
            int offset = vertex * 28, hash = 0x811C9DC5;
            for (int word = 0; word < 7; word++)
                hash = (hash ^ input.getInt(offset + word * 4)) * 0x01000193;
            hash ^= hash >>> 16;
            int slot = hash & (tableSize - 1);
            while (slots[slot] != 0 && !equal(input, offset, (slots[slot] - 1) * 28))
                slot = (slot + 1) & (tableSize - 1);
            if (slots[slot] == 0) {
                if (unique == 65536) return null;
                int destination = unique * 28;
                for (int word = 0; word < 7; word++)
                    output.putInt(destination + word * 4, input.getInt(offset + word * 4));
                slots[slot] = ++unique;
            }
            indices[vertex] = slots[slot] - 1;
        }
        int indexOffset = unique * 28;
        int payloadBytes = indexOffset + count * 2;
        // Every allocation remains on complete-quad boundaries, including the
        // next ordinary nonindexed mesh sharing this physical buffer page.
        int allocationBytes = (payloadBytes + 111) / 112 * 112;
        if ((long) allocationBytes * 8 > (long) bytes * 7) return null;
        for (int index = 0; index < count; index++)
            output.putShort(indexOffset + index * 2, (short) indices[index]);
        for (int offset = payloadBytes; offset < allocationBytes; offset++) output.put(offset, (byte) 0);
        output.position(0); output.limit(allocationBytes);
        return new Result(output, count, unique, indexOffset, payloadBytes, allocationBytes);
    }

    private boolean equal(ByteBuffer input, int a, int b) {
        for (int word = 0; word < 7; word++)
            if (input.getInt(a + word * 4) != output.getInt(b + word * 4)) return false;
        return true;
    }

    private boolean ensureCapacity(int vertices) {
        if (indices != null && indices.length >= vertices) return true;
        int capacity = 256;
        while (capacity < vertices) capacity <<= 1;
        CacheBudget.Reservation newHeap = budget.tryReserve(BudgetKind.HEAP, capacity * 12L + 64L);
        if (newHeap == null) return false;
        CacheBudget.Reservation newDirect = budget.tryReserve(BudgetKind.DIRECT, capacity * 28L);
        if (newDirect == null) { newHeap.close(); return false; }
        int[] newSlots, newIndices;
        ByteBuffer newOutput;
        try {
            newSlots = new int[capacity * 2]; newIndices = new int[capacity];
            newOutput = BufferUtils.createByteBuffer(capacity * 28).order(ByteOrder.nativeOrder());
        } catch (Throwable failure) {
            newHeap.close(); newDirect.close(); throw failure;
        }
        CacheBudget.Reservation oldHeap = heap, oldDirect = direct;
        slots = newSlots; indices = newIndices; output = newOutput;
        heap = newHeap; direct = newDirect;
        if (oldHeap != null) oldHeap.close();
        if (oldDirect != null) oldDirect.close();
        return true;
    }

    @Override public void close() {
        slots = null; indices = null; output = null;
        if (heap != null) { heap.close(); heap = null; }
        if (direct != null) { direct.close(); direct = null; }
    }

    static final class Result {
        final ByteBuffer bytes;
        final int indexCount, uniqueVertices, indexOffset, payloadBytes, allocationBytes;
        Result(ByteBuffer bytes, int indexCount, int uniqueVertices, int indexOffset,
               int payloadBytes, int allocationBytes) {
            this.bytes = bytes; this.indexCount = indexCount; this.uniqueVertices = uniqueVertices;
            this.indexOffset = indexOffset; this.payloadBytes = payloadBytes; this.allocationBytes = allocationBytes;
        }
    }
}
