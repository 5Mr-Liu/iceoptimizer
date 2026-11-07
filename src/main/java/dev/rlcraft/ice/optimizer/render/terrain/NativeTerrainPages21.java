package dev.rlcraft.ice.optimizer.render.terrain;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.TreeMap;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

/** Fixed paired VBO/index pages. Existing pages are never grown or copied. */
final class NativeTerrainPages21 {
    static final int PAGE_VERTICES = NativeTerrainAllocation.PART_VERTICES * 2;
    private final int stride;
    private Page[] pages = new Page[16];
    private int nextId, livePages;
    private final ArrayDeque<Integer> vacantIds = new ArrayDeque<Integer>();
    private final TreeMap<Integer, LinkedHashSet<Page>> available = new TreeMap<Integer, LinkedHashSet<Page>>();

    NativeTerrainPages21(int stride) { this.stride = stride; }

    NativeTerrainAllocation upload(ByteBuffer source, int count) {
        if (count <= 0 || (count & 3) != 0 || source.remaining() != count * (long) stride)
            throw new IllegalArgumentException("Invalid terrain page upload");
        NativeTerrainAllocator.Range[] parts = new NativeTerrainAllocator.Range[(count - 1) / NativeTerrainAllocation.PART_VERTICES + 1];
        int binding = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int position = source.position(), limit = source.limit();
        boolean committed = false;
        try {
            for (int part = 0, first = 0; part < parts.length; part++) {
                int vertices = Math.min(NativeTerrainAllocation.PART_VERTICES, count - first);
                NativeTerrainAllocator.Range range = allocate(vertices);
                parts[part] = range;
                Page page = page(range.first);
                source.limit(limit); source.position(position + first * stride);
                source.limit(source.position() + vertices * stride);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, page.vertices);
                GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, local(range.first) * (long) stride, source);
                first += vertices;
            }
            NativeTerrainAllocation result = new NativeTerrainAllocation(parts, count);
            committed = true;
            return result;
        } finally {
            if (!committed) for (NativeTerrainAllocator.Range range : parts) if (range != null) free(range);
            source.limit(limit); source.position(position);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, binding);
        }
    }

    private NativeTerrainAllocator.Range allocate(int count) {
        Map.Entry<Integer, LinkedHashSet<Page>> entry = available.ceilingEntry(count);
        Page page = entry == null ? createPage() : entry.getValue().iterator().next();
        removeAvailable(page);
        NativeTerrainAllocator.Range local = page.ranges.tryAllocate(count, PAGE_VERTICES);
        if (local == null) throw new IllegalStateException("Terrain page allocator lost free space");
        page.allocations++;
        addAvailable(page);
        return new NativeTerrainAllocator.Range(page.id * PAGE_VERTICES + local.first, count);
    }

    private Page createPage() {
        boolean fresh = vacantIds.isEmpty();
        int id = fresh ? nextId : vacantIds.peekFirst();
        if (id >= Integer.MAX_VALUE / PAGE_VERTICES) throw new IllegalStateException("ICE terrain page address space exhausted");
        if (id >= pages.length) pages = Arrays.copyOf(pages, pages.length * 2);
        Page page = new Page(id);
        boolean committed = false;
        try {
            page.vertices = createBuffer(Math.multiplyExact(PAGE_VERTICES, stride));
            page.indices = createBuffer(PAGE_VERTICES * 2);
            pages[id] = page;
            if (fresh) nextId++; else vacantIds.removeFirst();
            livePages++;
            addAvailable(page);
            committed = true;
            return page;
        } finally {
            if (!committed) {
                if (page.vertices != 0) GL15.glDeleteBuffers(page.vertices);
                if (page.indices != 0) GL15.glDeleteBuffers(page.indices);
            }
        }
    }

    private static int createBuffer(int bytes) {
        int buffer = GL15.glGenBuffers();
        if (buffer == 0) throw new IllegalStateException("ICE terrain page creation failed");
        boolean committed = false;
        try {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) bytes, GL15.GL_STATIC_DRAW);
            int allocated = GL15.glGetBufferParameteri(GL15.GL_ARRAY_BUFFER, GL15.GL_BUFFER_SIZE);
            if (allocated != bytes) throw new IllegalStateException(
                "ICE terrain page size mismatch: requested=" + bytes + ", allocated=" + allocated);
            committed = true;
            return buffer;
        } finally { if (!committed) GL15.glDeleteBuffers(buffer); }
    }

    void uploadIndices(ByteBuffer source, int[] copies, int count) {
        int binding = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int position = source.position(), limit = source.limit(), bound = -1;
        try {
            for (int slot = 0; slot < count * 3; slot += 3) {
                int from = copies[slot], address = copies[slot + 1], bytes = copies[slot + 2];
                Page page = page(address);
                int first = local(address);
                if (bytes < 0 || first + bytes / 2 > PAGE_VERTICES || (bytes & 1) != 0)
                    throw new IllegalArgumentException("Terrain indices cross page boundary");
                source.limit(limit); source.position(from); source.limit(from + bytes);
                if (bound != page.indices) {
                    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, page.indices); bound = page.indices;
                }
                GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, first * 2L, source);
            }
        } finally {
            source.limit(limit); source.position(position);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, binding);
        }
    }

    void free(NativeTerrainAllocation mesh) {
        if (mesh.pages == null) free(mesh.vertices);
        else for (NativeTerrainAllocator.Range range : mesh.pages) free(range);
    }

    private void free(NativeTerrainAllocator.Range range) {
        Page page = page(range.first);
        removeAvailable(page);
        page.ranges.free(new NativeTerrainAllocator.Range(local(range.first), range.count));
        if (--page.allocations != 0) addAvailable(page);
        else {
            pages[page.id] = null; livePages--; vacantIds.addFirst(page.id);
            GL15.glDeleteBuffers(page.vertices); GL15.glDeleteBuffers(page.indices);
        }
    }

    private void addAvailable(Page page) {
        page.space = page.ranges.available(PAGE_VERTICES);
        if (page.space == 0) return;
        LinkedHashSet<Page> bucket = available.get(page.space);
        if (bucket == null) available.put(page.space, bucket = new LinkedHashSet<Page>());
        bucket.add(page);
    }

    private void removeAvailable(Page page) {
        if (page.space == 0) return;
        LinkedHashSet<Page> bucket = available.get(page.space);
        bucket.remove(page);
        if (bucket.isEmpty()) available.remove(page.space);
    }

    Page page(int address) {
        int id = address / PAGE_VERTICES;
        if (address < 0 || id >= pages.length || pages[id] == null)
            throw new IllegalStateException("Retired terrain page address: " + address);
        return pages[id];
    }

    static int local(int address) { return address % PAGE_VERTICES; }
    long geometryBytes() { return livePages * (long) PAGE_VERTICES * stride; }
    long capacityBytes() { return livePages * (long) PAGE_VERTICES * (stride + 2); }
    int pageCount() { return livePages; }

    void delete() {
        for (Page page : pages) if (page != null) {
            GL15.glDeleteBuffers(page.vertices); GL15.glDeleteBuffers(page.indices);
        }
        Arrays.fill(pages, null); available.clear(); vacantIds.clear(); nextId = livePages = 0;
    }

    static final class Page {
        final int id;
        final NativeTerrainAllocator ranges = new NativeTerrainAllocator();
        int vertices, indices, allocations, space;
        Page(int id) { this.id = id; }
    }
}
