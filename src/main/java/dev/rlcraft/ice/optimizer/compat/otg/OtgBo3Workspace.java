package dev.rlcraft.ice.optimizer.compat.otg;

import com.pg85.otg.common.LocalMaterialData;
import com.pg85.otg.common.LocalWorld;
import com.pg85.otg.customobjects.bo3.BO3Config;
import com.pg85.otg.customobjects.bo3.BO3Settings.ExtrudeMode;
import com.pg85.otg.customobjects.bo3.bo3function.BO3BlockFunction;
import com.pg85.otg.util.ChunkCoordinate;
import com.pg85.otg.util.bo3.NamedBinaryTag;
import com.pg85.otg.util.materials.MaterialSet;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Random;

/** One frame per nested BO3 attempt. Original world checks/events stay in BO3.trySpawnAt. */
public final class OtgBo3Workspace {
    private static final ThreadLocal<ArrayList<OtgBo3Workspace>> POOL = new ThreadLocal<ArrayList<OtgBo3Workspace>>() {
        @Override protected ArrayList<OtgBo3Workspace> initialValue() { return new ArrayList<OtgBo3Workspace>(); }
    };
    private static final ClassValue<Boolean> STANDARD = new ClassValue<Boolean>() {
        @Override protected Boolean computeValue(Class<?> type) {
            try { return type.getMethod("getBlocks", Integer.TYPE).getDeclaringClass() == BO3Config.class; }
            catch (NoSuchMethodException error) { throw new IllegalStateException(error); }
        }
    };
    private static final Field[] FIELDS = fields();
    private boolean active, compact;
    private BO3BlockFunction[] original;
    private byte[] xs, zs, randomCounts;
    private short[] ys;
    private LocalMaterialData[] materials;
    private NamedBinaryTag[] tags;
    private LocalMaterialData[][] randomMaterials;
    private byte[][] randomChances;
    private NamedBinaryTag[][] randomTags;
    private int current, count, retainedCount;
    private final IndexList selected = new IndexList(this);
    private final Extrusion extrusion = new Extrusion(this);

    public static OtgBo3Workspace begin(BO3Config config, int rotation) {
        OtgBo3Workspace frame = null;
        for (OtgBo3Workspace candidate : POOL.get()) if (!candidate.active) { frame = candidate; break; }
        if (frame == null) { frame = new OtgBo3Workspace(); POOL.get().add(frame); }
        frame.active = true;
        try {
            frame.compact = OptimizerBridge.isEnabled("otg-bo3-execution") && STANDARD.get(config.getClass());
            if (!frame.compact) { frame.original = config.getBlocks(rotation); frame.count = frame.original.length; }
            else {
                byte[] sourceX = ((byte[][]) FIELDS[0].get(config))[rotation];
                frame.count = sourceX.length;
                frame.ensureCapacity(frame.count);
                frame.retainedCount = frame.count;
                // getBlocks snapshots scalar fields and outer references before world callbacks.
                // The random choice arrays themselves are shared by the original functions.
                System.arraycopy(sourceX, 0, frame.xs, 0, frame.count);
                System.arraycopy(((short[][]) FIELDS[1].get(config))[rotation], 0, frame.ys, 0, frame.count);
                System.arraycopy(((byte[][]) FIELDS[2].get(config))[rotation], 0, frame.zs, 0, frame.count);
                System.arraycopy(((LocalMaterialData[][]) FIELDS[3].get(config))[rotation], 0, frame.materials, 0, frame.count);
                System.arraycopy(FIELDS[4].get(config), 0, frame.tags, 0, frame.count);
                System.arraycopy(((LocalMaterialData[][][]) FIELDS[5].get(config))[rotation], 0, frame.randomMaterials, 0, frame.count);
                System.arraycopy(FIELDS[6].get(config), 0, frame.randomChances, 0, frame.count);
                System.arraycopy(FIELDS[7].get(config), 0, frame.randomTags, 0, frame.count);
                System.arraycopy(FIELDS[8].get(config), 0, frame.randomCounts, 0, frame.count);
            }
            return frame;
        } catch (IllegalAccessException error) { release(frame); throw new IllegalStateException(error); }
          catch (RuntimeException | Error error) { release(frame); throw error; }
    }

    public BO3BlockFunction[] blocks() { return original; }
    public static int length(BO3BlockFunction[] ignored, OtgBo3Workspace frame) { return frame.count; }
    public static BO3BlockFunction blockAt(BO3BlockFunction[] ignored, int index, OtgBo3Workspace frame) {
        frame.current = index;
        return frame.compact ? null : frame.original[index];
    }
    public static int x(BO3BlockFunction block, OtgBo3Workspace frame) { return frame.compact ? frame.xs[frame.current] : block.x; }
    public static short y(BO3BlockFunction block, OtgBo3Workspace frame) { return frame.compact ? frame.ys[frame.current] : block.y; }
    public static int z(BO3BlockFunction block, OtgBo3Workspace frame) { return frame.compact ? frame.zs[frame.current] : block.z; }
    public static boolean isBlock(BO3BlockFunction block, OtgBo3Workspace frame) { return frame.compact || block instanceof BO3BlockFunction; }
    public ArrayList<BO3BlockFunction> selected() { return selected; }
    public Extrusion extrusion(ExtrudeMode mode, MaterialSet through) {
        extrusion.mode = mode; extrusion.through = through; extrusion.height = mode.getStartingHeight();
        return extrusion;
    }
    public static void spawn(BO3BlockFunction block, LocalWorld world, Random random, int x, int y, int z,
                             ChunkCoordinate chunk, boolean replace, OtgBo3Workspace frame) {
        if (!frame.compact) { block.spawn(world, random, x, y, z, chunk, replace); return; }
        int i = frame.current;
        LocalMaterialData[] choices = frame.randomMaterials[i];
        if (choices == null) world.setBlock(x, y, z, frame.materials[i], frame.tags[i], chunk, replace);
        else for (int choice = 0; choice < frame.randomCounts[i]; choice++) {
            if (random.nextInt(100) < frame.randomChances[i][choice]) {
                world.setBlock(x, y, z, choices[choice], frame.randomTags[i][choice], chunk, replace);
                break;
            }
        }
    }
    public static void release(OtgBo3Workspace frame) {
        if (frame == null) return;
        frame.original = null;
        if (frame.retainedCount > 0) {
            Arrays.fill(frame.materials, 0, frame.retainedCount, null); Arrays.fill(frame.tags, 0, frame.retainedCount, null);
            Arrays.fill(frame.randomMaterials, 0, frame.retainedCount, null); Arrays.fill(frame.randomChances, 0, frame.retainedCount, null);
            Arrays.fill(frame.randomTags, 0, frame.retainedCount, null); frame.retainedCount = 0;
        }
        frame.selected.clear(); frame.extrusion.selected.clear(); frame.extrusion.through = null;
        frame.extrusion.mode = null; frame.active = false;
    }
    private void ensureCapacity(int size) {
        if (xs != null && xs.length >= size) return;
        int capacity = Math.max(size, xs == null ? 64 : xs.length * 2);
        xs = new byte[capacity]; ys = new short[capacity]; zs = new byte[capacity];
        materials = new LocalMaterialData[capacity]; tags = new NamedBinaryTag[capacity];
        randomMaterials = new LocalMaterialData[capacity][]; randomChances = new byte[capacity][];
        randomTags = new NamedBinaryTag[capacity][]; randomCounts = new byte[capacity];
    }
    private static Field[] fields() {
        String[] names = { "blocksX", "blocksY", "blocksZ", "blocksMaterial", "blocksMetaDataTag",
            "randomBlocksBlocks", "randomBlocksBlockChances", "randomBlocksMetaDataTags", "randomBlocksBlockCount" };
        Field[] fields = new Field[names.length];
        try { for (int i = 0; i < names.length; i++) { fields[i] = BO3Config.class.getDeclaredField(names[i]); fields[i].setAccessible(true); } }
        catch (ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
        return fields;
    }

    private static final class IndexList extends ArrayList<BO3BlockFunction> {
        final OtgBo3Workspace frame;
        int[] indices = new int[64];
        int size;
        IndexList(OtgBo3Workspace frame) { this.frame = frame; }
        @Override public boolean add(BO3BlockFunction block) {
            if (size == indices.length) indices = Arrays.copyOf(indices, size * 2);
            indices[size++] = frame.current;
            return true;
        }
        @Override public void clear() { size = 0; }
        @Override public int size() { return size; }
        private final IndexIterator cursor = new IndexIterator();
        @Override public Iterator<BO3BlockFunction> iterator() { cursor.position = 0; return cursor; }
        private final class IndexIterator implements Iterator<BO3BlockFunction> {
            int position;
            @Override public boolean hasNext() { return position < size; }
            @Override public BO3BlockFunction next() {
                if (!hasNext()) throw new NoSuchElementException();
                frame.current = indices[position++];
                return frame.compact ? null : frame.original[frame.current];
            }
            @Override public void remove() { throw new UnsupportedOperationException(); }
        }
    }

    public static final class Extrusion {
        final OtgBo3Workspace frame;
        final IndexList selected;
        ExtrudeMode mode;
        MaterialSet through;
        int height;
        Extrusion(OtgBo3Workspace frame) { this.frame = frame; selected = new IndexList(frame); }
        public void addBlock(BO3BlockFunction block) {
            if (mode == ExtrudeMode.None) return;
            int y = OtgBo3Workspace.y(block, frame);
            if ((mode == ExtrudeMode.BottomDown && y < height) || (mode == ExtrudeMode.TopUp && y > height)) {
                selected.clear(); height = y;
            }
            if (height == y) selected.add(block);
        }
        public void extrude(LocalWorld world, Random random, int x, int y, int z, ChunkCoordinate chunk, boolean replace) {
            for (BO3BlockFunction block : selected) {
                int bx = x + OtgBo3Workspace.x(block, frame), bz = z + OtgBo3Workspace.z(block, frame);
                int start = y + OtgBo3Workspace.y(block, frame);
                int direction = mode == ExtrudeMode.BottomDown ? -1 : 1;
                if (mode != ExtrudeMode.BottomDown && mode != ExtrudeMode.TopUp) continue;
                for (int by = start + direction; direction < 0 ? by > mode.getEndingHeight() : by < mode.getEndingHeight(); by += direction) {
                    if (!through.contains(world.getMaterial(bx, by, bz, chunk))) break;
                    LocalMaterialData material = frame.compact ? frame.materials[frame.current] : block.material;
                    NamedBinaryTag tag = frame.compact ? frame.tags[frame.current] : block.metaDataTag;
                    world.setBlock(bx, by, bz, material, tag, chunk, replace);
                }
            }
        }
    }
}
