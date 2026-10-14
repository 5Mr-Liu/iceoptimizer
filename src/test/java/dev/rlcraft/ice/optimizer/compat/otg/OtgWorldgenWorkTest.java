package dev.rlcraft.ice.optimizer.compat.otg;

import com.pg85.otg.common.*;
import com.pg85.otg.customobjects.bo3.*;
import com.pg85.otg.customobjects.bo3.bo3function.*;
import com.pg85.otg.util.*;
import com.pg85.otg.util.bo3.NamedBinaryTag;
import com.pg85.otg.util.materials.MaterialSet;
import dev.rlcraft.ice.optimizer.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import net.minecraft.world.chunk.Chunk;
import org.junit.*;
import static org.junit.Assert.*;

public class OtgWorldgenWorkTest {
    private boolean oldEnabled, oldPaused, oldBo3, oldColumns, oldPreparation, oldFileCache;
    @Before public void enable() {
        oldEnabled = OptimizerConfig.settings.enabled; oldPaused = OptimizerRegistry.isRuntimePaused();
        oldBo3 = OptimizerConfig.settings.otgBo3Execution; oldColumns = OptimizerConfig.settings.otgColumnQueries;
        oldPreparation = OptimizerConfig.settings.otgObjectPreparation; oldFileCache = OptimizerConfig.settings.otgSynchronousFileCache;
        OptimizerConfig.settings.enabled = true; OptimizerConfig.settings.otgBo3Execution = true;
        OptimizerConfig.settings.otgColumnQueries = true; OptimizerConfig.settings.otgObjectPreparation = true;
        OptimizerConfig.settings.otgSynchronousFileCache = true; OptimizerRegistry.configure(ClientOptimizerConfig.capture());
        OptimizerRegistry.setRuntimePaused(false);
        for (OptimizationModule module : new OptimizationModule[] { OptimizationModule.OTG_BO3_EXECUTION,
                OptimizationModule.OTG_COLUMN_QUERIES, OptimizationModule.OTG_OBJECT_PREPARATION, OptimizationModule.OTG_SYNC_FILE_CACHE })
            OptimizerRegistry.breaker(module).patchInstalled("synthetic", "test");
        OtgSynchronousIoBridge.advanceConfigurationGeneration();
    }
    @After public void cleanup() {
        OtgSynchronousIoBridge.advanceConfigurationGeneration(); OptimizerConfig.settings.enabled = oldEnabled;
        OptimizerConfig.settings.otgBo3Execution = oldBo3; OptimizerConfig.settings.otgColumnQueries = oldColumns;
        OptimizerConfig.settings.otgObjectPreparation = oldPreparation; OptimizerConfig.settings.otgSynchronousFileCache = oldFileCache;
        OptimizerRegistry.configure(ClientOptimizerConfig.capture()); OptimizerRegistry.setRuntimePaused(oldPaused);
    }

    @Test public void bo3RngCoordinatesSnapshotAndReentrantFramesMatch() throws Exception {
        BO3Config config = config(); BO3BlockFunction[] original = config.getBlocks(0);
        OtgBo3Workspace first = OtgBo3Workspace.begin(config, 0), nested = OtgBo3Workspace.begin(config, 0);
        assertNotSame(first, nested); assertNull(first.blocks());
        ((byte[][]) field("blocksX").get(config))[0][0] = 99;
        assertEquals(original[0].x, OtgBo3Workspace.x(OtgBo3Workspace.blockAt(null, 0, first), first));
        List<String> expected = new ArrayList<String>(), actual = new ArrayList<String>();
        CountingRandom left = new CountingRandom(673L), right = new CountingRandom(673L);
        LocalWorld world1 = world(expected), world2 = world(actual);
        for (BO3BlockFunction block : original) block.spawn(world1, left, block.x - 32, block.y + 80, block.z + 16, null, true);
        for (int i = 0; i < original.length; i++) {
            BO3BlockFunction block = OtgBo3Workspace.blockAt(null, i, first);
            OtgBo3Workspace.spawn(block, world2, right, OtgBo3Workspace.x(block, first) - 32,
                OtgBo3Workspace.y(block, first) + 80, OtgBo3Workspace.z(block, first) + 16, null, true, first);
        }
        assertEquals(expected, actual); assertEquals(left.calls, right.calls);
        OtgBo3Workspace.release(nested); OtgBo3Workspace.release(first);
        OtgBo3Workspace reused = OtgBo3Workspace.begin(config, 0);
        assertSame(first, reused); assertEquals(99, OtgBo3Workspace.x(OtgBo3Workspace.blockAt(null, 0, reused), reused));
        OtgBo3Workspace.release(reused);
        Field materials = OtgBo3Workspace.class.getDeclaredField("materials"); materials.setAccessible(true);
        for (Object value : (Object[]) materials.get(reused)) assertNull(value);
    }
    @Test public void extrusionMatchesOriginalForBothDirectionsAndNoMode() throws Exception {
        BO3Config config = config(); BO3BlockFunction[] blocks = config.getBlocks(0);
        Class<?> helper = Class.forName("com.pg85.otg.customobjects.bo3.ObjectExtrusionHelper");
        Constructor<?> constructor = helper.getDeclaredConstructor(BO3Settings.ExtrudeMode.class, MaterialSet.class); constructor.setAccessible(true);
        Method add = helper.getDeclaredMethod("addBlock", BO3BlockFunction.class); add.setAccessible(true);
        Method extrude = helper.getDeclaredMethod("extrude", LocalWorld.class, Random.class, int.class, int.class, int.class, ChunkCoordinate.class, boolean.class); extrude.setAccessible(true);
        for (BO3Settings.ExtrudeMode mode : BO3Settings.ExtrudeMode.values()) {
            List<String> expected = new ArrayList<String>(), actual = new ArrayList<String>();
            Object old = constructor.newInstance(mode, through()); OtgBo3Workspace frame = OtgBo3Workspace.begin(config, 0);
            OtgBo3Workspace.Extrusion work = frame.extrusion(mode, through());
            for (int i = 0; i < blocks.length; i++) { add.invoke(old, blocks[i]); work.addBlock(OtgBo3Workspace.blockAt(null, i, frame)); }
            extrude.invoke(old, world(expected), new Random(1), -32, 100, 48, null, true);
            work.extrude(world(actual), new Random(1), -32, 100, 48, null, true);
            assertEquals(mode.name(), expected, actual); OtgBo3Workspace.release(frame);
        }
    }
    @Test public void columnsUseSignedCoordinatesAndReleaseOnPreviewLifecycle() throws Exception {
        OtgColumnCache columns = new OtgColumnCache(3);
        OtgColumnCache.PreviewChunks previews = new OtgColumnCache.PreviewChunks(1, columns);
        ChunkCoordinate first = ChunkCoordinate.fromChunkCoords(-1, -2), second = ChunkCoordinate.fromChunkCoords(1, 2);
        Chunk chunk1 = (Chunk) unsafe().allocateInstance(Chunk.class), chunk2 = (Chunk) unsafe().allocateInstance(Chunk.class);
        previews.put(first, chunk1);
        LocalMaterialData[] data = new LocalMaterialData[256];
        columns.put(OtgColumnCache.key(-1, -17), data);
        assertSame(data, columns.get(OtgColumnCache.key(-1, -17)));
        assertNull(columns.get(OtgColumnCache.key(-1, 17)));
        previews.put(second, chunk2); assertNull(columns.get(OtgColumnCache.key(-1, -17)));
        columns.put(OtgColumnCache.key(17, 33), data); previews.remove(second); assertTrue(columns.isEmpty());
        columns.put(OtgColumnCache.key(17, 33), data); columns.invalidate(second); assertTrue(columns.isEmpty());
        for (int i = 0; i < 4; i++) columns.put(OtgColumnCache.key(i, i), data);
        assertEquals(3, columns.size()); assertNull(columns.get(OtgColumnCache.key(0, 0)));
        OptimizerRegistry.setRuntimePaused(true); assertNull(columns.get(OtgColumnCache.key(3, 3))); OptimizerRegistry.setRuntimePaused(false);
    }
    @Test public void registrationIsLazyAndRequestedMetadataStillHasIndependentTrees() throws Exception {
        Path fixtureRoot = Paths.get(".refactor-backups/1.0.24-worldgen/fixtures").toAbsolutePath();
        Files.createDirectories(fixtureRoot);
        Path directory = Files.createTempDirectory(fixtureRoot, "ice-otg-prepare");
        Path config = directory.resolve("root.bo3"), metadata = directory.resolve("tile.nbt");
        try {
            Files.write(config, Arrays.asList("Block(0,0,0,STONE,tile.nbt)", "Branch(0,0,0,root,NORTH,100)", "Author: fixture"), StandardCharsets.UTF_8);
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(metadata)))) {
                out.writeByte(10); out.writeUTF(""); out.writeByte(8); out.writeUTF("id"); out.writeUTF("fixture");
                out.writeByte(11); out.writeUTF("values"); out.writeInt(1); out.writeInt(27); out.writeByte(0);
            }
            Map<String, File> registered = new HashMap<String, File>(); registered.put("root", config.toFile());
            assertTrue("preparation module operational", OptimizerRegistry.isOperational(OptimizationModule.OTG_OBJECT_PREPARATION));
            OtgObjectPreparationBridge.prepare(registered);
            assertEquals(0, OtgSynchronousIoBridge.settingsCacheEntriesForTest());
            Map<?, ?> tags = OtgSynchronousIoBridge.createMetadataMap(NamedBinaryTag.class);
            String key = metadata.toFile().getPath(); assertFalse(tags.containsKey(key));
            // Preserve on-demand immutable settings and fresh mutable tags.
            OtgSynchronousIoBridge.prepareSettings(config.toFile());
            OtgSynchronousIoBridge.prepareMetadata(metadata.toFile(), NamedBinaryTag.class);
            assertEquals(1, OtgSynchronousIoBridge.settingsCacheEntriesForTest());
            assertTrue(tags.containsKey(key));
            NamedBinaryTag first = (NamedBinaryTag) tags.get(key); assertTrue(tags.containsKey(key));
            NamedBinaryTag second = (NamedBinaryTag) tags.get(key); assertNotSame(first, second);
            ((int[]) first.getTag("values").getValue())[0] = 99;
            assertEquals(27, ((int[]) second.getTag("values").getValue())[0]);
            OtgSynchronousIoBridge.advanceConfigurationGeneration(); assertFalse(tags.containsKey(key));
        } finally { Files.deleteIfExists(config); Files.deleteIfExists(metadata); Files.deleteIfExists(directory); }
    }
    private static MaterialSet through() { return new MaterialSet() { int calls; @Override public boolean contains(LocalMaterialData material) { return ++calls % 4 != 0; } }; }
    private static LocalWorld world(List<String> output) {
        return (LocalWorld) Proxy.newProxyInstance(LocalWorld.class.getClassLoader(), new Class<?>[] { LocalWorld.class }, (proxy, method, args) -> {
            if ("setBlock".equals(method.getName())) output.add(args[0] + "/" + args[1] + "/" + args[2] + "/" + args[4]);
            return null;
        });
    }
    private static BO3Config config() throws Exception {
        BO3Config config = (BO3Config) unsafe().allocateInstance(BO3Config.class); int n = 5;
        byte[][] xs = { {-17, 0, 1, 7, 32} }, zs = { {16, -1, 0, 7, -32} }; short[][] ys = { {-1, 0, 0, 2, -1} };
        field("blocksX").set(config, xs); field("blocksY").set(config, ys); field("blocksZ").set(config, zs);
        field("blocksMaterial").set(config, new LocalMaterialData[1][n]); field("blocksMetaDataTag").set(config, new NamedBinaryTag[n]);
        field("blocksMetaDataName").set(config, new String[n]);
        LocalMaterialData[][][] choices = new LocalMaterialData[1][n][]; choices[0][1] = new LocalMaterialData[2]; choices[0][3] = new LocalMaterialData[2];
        field("randomBlocksBlocks").set(config, choices);
        byte[][] chances = new byte[n][]; chances[1] = new byte[] { 20, 100 }; chances[3] = new byte[] { 0, 100 };
        field("randomBlocksBlockChances").set(config, chances);
        NamedBinaryTag[][] tags = new NamedBinaryTag[n][]; tags[1] = new NamedBinaryTag[2]; tags[3] = new NamedBinaryTag[2];
        field("randomBlocksMetaDataTags").set(config, tags); field("randomBlocksMetaDataNames").set(config, new String[n][]);
        field("randomBlocksBlockCount").set(config, new byte[] {0, 2, 0, 2, 0}); return config;
    }
    private static Field field(String name) throws Exception { Field field = BO3Config.class.getDeclaredField(name); field.setAccessible(true); return field; }
    private static sun.misc.Unsafe unsafe() throws Exception { Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true); return (sun.misc.Unsafe) field.get(null); }
    private static final class CountingRandom extends Random { int calls; CountingRandom(long seed) { super(seed); } @Override public int nextInt(int bound) { calls++; return super.nextInt(bound); } }
}
