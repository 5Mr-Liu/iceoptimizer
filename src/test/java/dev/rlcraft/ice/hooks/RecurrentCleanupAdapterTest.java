package dev.rlcraft.ice.hooks;

import dev.rlcraft.ice.optimizer.compat.recurrent.RecurrentCleanupAccess;
import dev.rlcraft.ice.optimizer.compat.recurrent.RecurrentCleanupBridge;
import dev.rlcraft.ice.optimizer.ClientOptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import net.minecraft.world.storage.WorldSavedData;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.*;
import static org.junit.Assert.*;

public class RecurrentCleanupAdapterTest {
    private static final String DATA = RecurrentCleanupAdapter.DATA.replace('/', '.');
    private static final String CLEANUP = RecurrentCleanupAdapter.CLEANUP.replace('/', '.');
    private static final java.util.concurrent.atomic.AtomicInteger STRUCTURE_WRITES = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger CUSTOM_WRITES = new java.util.concurrent.atomic.AtomicInteger();

    public static void recordWrite(boolean custom) { (custom ? CUSTOM_WRITES : STRUCTURE_WRITES).incrementAndGet(); }

    @Test public void runtimeSwitchKeepsNormalSerializationAndReadsFreshChanges() throws Exception {
        boolean oldEnabled = OptimizerConfig.settings.enabled;
        boolean oldModule = OptimizerConfig.settings.recurrentCleanupNbt;
        boolean oldPaused = OptimizerRegistry.isRuntimePaused();
        try (RuntimeJar loader = runtime()) {
            OptimizerConfig.settings.enabled = true; OptimizerConfig.settings.recurrentCleanupNbt = true;
            OptimizerRegistry.configure(ClientOptimizerConfig.capture()); OptimizerRegistry.setRuntimePaused(false);
            OptimizerRegistry.breaker(OptimizationModule.RECURRENT_CLEANUP_NBT).patchInstalled("synthetic", "test");
            assertTrue(OptimizerBridge.isEnabled("recurrent-cleanup-nbt"));
            WorldSavedData data = (WorldSavedData) loader.loadClass(DATA).newInstance();
            RecurrentCleanupAccess access = (RecurrentCleanupAccess) data;
            populate(loader, access);
            NBTTagCompound first = RecurrentCleanupBridge.serializeForCleanup(data);
            assertEquals(clean(data.serializeNBT()), first);
            Object custom = null;
            for (Object entry : access.ice$cleanupEntries().values())
                if (entry.getClass().getName().endsWith("$CustomEntry")) custom = entry;
            declared(custom.getClass(), "name").set(custom, "updated record");
            NBTTagCompound second = RecurrentCleanupBridge.serializeForCleanup(data);
            assertEquals("updated record", second.getTagList("customEntries", 10).getCompoundTagAt(0).getString("name"));
            assertEquals("custom record", first.getTagList("customEntries", 10).getCompoundTagAt(0).getString("name"));
            OptimizerConfig.settings.recurrentCleanupNbt = false;
            OptimizerRegistry.configure(ClientOptimizerConfig.capture());
            assertEquals(data.serializeNBT(), RecurrentCleanupBridge.serializeForCleanup(data));
            assertTrue(RecurrentCleanupBridge.serializeForCleanup(data).hasKey("entries"));
        } finally {
            OptimizerConfig.settings.enabled = oldEnabled; OptimizerConfig.settings.recurrentCleanupNbt = oldModule;
            OptimizerRegistry.configure(ClientOptimizerConfig.capture()); OptimizerRegistry.setRuntimePaused(oldPaused);
        }
    }

    @Test public void realWriterKeepsCustomEntriesAndOriginalSaveIsUnchanged() throws Exception {
        try (RuntimeJar loader = runtime()) {
            WorldSavedData data = (WorldSavedData) loader.loadClass(DATA).newInstance();
            RecurrentCleanupAccess access = (RecurrentCleanupAccess) data;
            populate(loader, access);
            STRUCTURE_WRITES.set(0); CUSTOM_WRITES.set(0);
            NBTTagCompound normal = data.serializeNBT();
            assertEquals(2, STRUCTURE_WRITES.get()); assertEquals(1, CUSTOM_WRITES.get());
            assertEquals(2, normal.getTagList("entries", 10).tagCount());
            assertEquals(1, normal.getTagList("customEntries", 10).tagCount());
            assertEquals(2, normal.getTagList("checkedChunks", 11).tagCount());
            assertEquals(1, normal.getTagList("checkedChunksFinal", 11).tagCount());
            assertTrue(eligible(data, access));
            NBTTagCompound expected = clean(normal.copy());
            STRUCTURE_WRITES.set(0); CUSTOM_WRITES.set(0);
            NBTTagCompound fast = access.ice$writeCleanupNbt(new NBTTagCompound());
            assertEquals(0, STRUCTURE_WRITES.get()); assertEquals(1, CUSTOM_WRITES.get());
            assertEquals(expected, fast);
            assertEquals("custom record", fast.getTagList("customEntries", 10).getCompoundTagAt(0).getString("name"));
            assertEquals(normal, data.serializeNBT());
            assertEquals(3, access.ice$cleanupEntries().size());
            assertFalse(data.isDirty());
        }
    }

    @Test public void originalReaderRebuildsAllIndexesAndRetainsDirtyAndCustomData() throws Exception {
        try (RuntimeJar loader = runtime()) {
            WorldSavedData source = (WorldSavedData) loader.loadClass(DATA).newInstance();
            RecurrentCleanupAccess access = (RecurrentCleanupAccess) source;
            populate(loader, access);
            WorldSavedData baseline = (WorldSavedData) loader.loadClass(DATA).newInstance();
            WorldSavedData optimized = (WorldSavedData) loader.loadClass(DATA).newInstance();
            baseline.readFromNBT(clean(source.serializeNBT()));
            optimized.readFromNBT(access.ice$writeCleanupNbt(new NBTTagCompound()));
            assertEquals(baseline.serializeNBT(), optimized.serializeNBT());
            assertEquals(baseline.isDirty(), optimized.isDirty());
            for (String field : new String[] {"entryMap", "chunkMap", "instanceMap", "checkedChunks", "checkedChunksFinal"}) {
                Object left = field(baseline, field), right = field(optimized, field);
                if (left instanceof Map) assertEquals(((Map<?, ?>) left).size(), ((Map<?, ?>) right).size());
                else if (left instanceof Set) assertEquals(left, right);
                else assertEquals(((com.google.common.collect.Multimap<?, ?>) left).size(),
                    ((com.google.common.collect.Multimap<?, ?>) right).size());
            }
            assertEquals(1, ((RecurrentCleanupAccess) optimized).ice$cleanupEntries().size());
            assertFalse(((com.google.common.collect.Multimap<?, ?>) field(optimized, "chunkMap")).isEmpty());
            assertTrue(((com.google.common.collect.Multimap<?, ?>) field(optimized, "instanceMap")).isEmpty());
        }
    }

    @Test public void runtimeGuardsRejectUnknownEntriesCollectionsAndInvalidRecords() throws Exception {
        try (RuntimeJar loader = runtime()) {
            WorldSavedData data = (WorldSavedData) loader.loadClass(DATA).newInstance();
            RecurrentCleanupAccess access = (RecurrentCleanupAccess) data;
            populate(loader, access);
            Map<Object, Object> entries = entries(access);
            UUID extra = UUID.randomUUID(); entries.put(extra, new Object());
            assertFalse(eligible(data, access)); entries.remove(extra);
            Object structure = null;
            for (Object entry : entries.values()) if (entry.getClass().getName().endsWith("$StructureEntry")) structure = entry;
            Field transform = declared(structure.getClass(), "transform"); Object original = transform.get(structure);
            transform.set(structure, null); assertFalse(eligible(data, access)); transform.set(structure, original);
            assertTrue(eligible(data, access));
            Field map = declared(data.getClass(), "entryMap"); map.set(data, new java.util.LinkedHashMap<Object, Object>(entries));
            assertFalse(eligible(data, access)); map.set(data, entries);
            @SuppressWarnings("unchecked") Set<Object> chunks = (Set<Object>) access.ice$cleanupCheckedChunks();
            chunks.add(null); assertFalse(eligible(data, access)); chunks.remove(null);
            assertTrue(eligible(data, access));
        }
    }

    @Test public void rltweakerRewritePreservesReadbackAndMonitorHandlers() throws Exception {
        byte[] source = WorldgenRemainingAdaptersTest.read("ice.rltweaker.jar", CLEANUP);
        byte[] result = new RecurrentCleanupAdapter().transform(CLEANUP, source, null);
        WorldgenRemainingAdaptersTest.verify(result);
        assertEquals(1, WorldgenRemainingAdaptersTest.calls(result, "serializeForCleanup"));
        assertEquals(0, WorldgenRemainingAdaptersTest.calls(result, "serializeNBT"));
        assertEquals(WorldgenRemainingAdaptersTest.calls(source, "func_76184_a"), WorldgenRemainingAdaptersTest.calls(result, "func_76184_a"));
        assertEquals(WorldgenRemainingAdaptersTest.calls(source, "func_75745_a"), WorldgenRemainingAdaptersTest.calls(result, "func_75745_a"));
        assertEquals(WorldgenRemainingAdaptersTest.calls(source, "func_82580_o"), WorldgenRemainingAdaptersTest.calls(result, "func_82580_o"));
        ClassNode before = node(source), after = node(result);
        for (int i = 0; i < before.methods.size(); i++) {
            assertEquals(before.methods.get(i).tryCatchBlocks.size(), after.methods.get(i).tryCatchBlocks.size());
            assertEquals(frames(before.methods.get(i)), frames(after.methods.get(i)));
        }
    }

    @Test public void modifiedWriterOrCleanupContractFailsClosed() throws Exception {
        byte[] source = WorldgenRemainingAdaptersTest.read("ice.recurrent.jar", DATA);
        ClassNode modified = node(source);
        for (MethodNode method : modified.methods) if ("func_189551_b".equals(method.name))
            method.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, "example/Custom", "callback", "()V", false));
        rejects(DATA, bytes(modified));
        modified = node(source);
        MethodNode override = new MethodNode(Opcodes.ACC_PUBLIC, "serializeNBT", "()Lnet/minecraft/nbt/NBTTagCompound;", null, null);
        override.instructions.add(new InsnNode(Opcodes.ACONST_NULL)); override.instructions.add(new InsnNode(Opcodes.ARETURN));
        modified.methods.add(override); rejects(DATA, bytes(modified));
        modified = node(WorldgenRemainingAdaptersTest.read("ice.rltweaker.jar", CLEANUP));
        for (MethodNode method : modified.methods) if ("wipeRecurrentData".equals(method.name))
            for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof LdcInsnNode
                && "entries".equals(((LdcInsnNode) instruction).cst)) ((LdcInsnNode) instruction).cst = "differentEntries";
        rejects(CLEANUP, bytes(modified));
    }

    private static NBTTagCompound clean(NBTTagCompound tag) {
        tag.removeTag("entries"); tag.removeTag("checkedChunks"); tag.removeTag("checkedChunksFinal"); return tag;
    }
    private static boolean eligible(WorldSavedData data, RecurrentCleanupAccess access) throws Exception {
        Method guard = RecurrentCleanupBridge.class.getDeclaredMethod("canSkipDiscardedTags", Class.class, RecurrentCleanupAccess.class);
        guard.setAccessible(true); return (Boolean) guard.invoke(null, data.getClass(), access);
    }
    private static Object field(Object object, String name) throws Exception { return declared(object.getClass(), name).get(object); }
    private static Field declared(Class<?> type, String name) throws Exception { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
    @SuppressWarnings("unchecked") private static Map<Object, Object> entries(RecurrentCleanupAccess access) { return (Map<Object, Object>) access.ice$cleanupEntries(); }
    @SuppressWarnings("unchecked") private static void populate(ClassLoader loader, RecurrentCleanupAccess access) throws Exception {
        Class<?> custom = loader.loadClass(DATA + "$CustomEntry");
        UUID id = UUID.randomUUID();
        entries(access).put(id, custom.getConstructor(UUID.class, StructureBoundingBox.class, String.class)
            .newInstance(id, new StructureBoundingBox(-20, 0, -20, 20, 30, 20), "custom record"));
        Class<?> structure = loader.loadClass(DATA + "$StructureEntry");
        Class<?> transform = loader.loadClass("ivorius.ivtoolkit.math.AxisAlignedTransform2D");
        Object rotation = transform.getConstructor(int.class, boolean.class).newInstance(1, true);
        for (int i = 0; i < 2; i++) {
            id = UUID.randomUUID();
            Object entry = structure.getConstructor(UUID.class, StructureBoundingBox.class, String.class, String.class, transform, boolean.class)
                .newInstance(id, new StructureBoundingBox(i * 32, 0, 0, i * 32 + 15, 10, 15), "structure" + i, "generation", rotation, false);
            NBTTagCompound big = new NBTTagCompound(); big.setByteArray("large", new byte[65536]);
            declared(structure, "instanceData").set(entry, big); entries(access).put(id, entry);
        }
        ((Set<ChunkPos>) access.ice$cleanupCheckedChunks()).add(new ChunkPos(-1, 0));
        ((Set<ChunkPos>) access.ice$cleanupCheckedChunks()).add(new ChunkPos(1, 0));
        ((Set<ChunkPos>) access.ice$cleanupCheckedChunksFinal()).add(new ChunkPos(0, -1));
    }
    private static void rejects(String name, byte[] bytes) {
        try { new RecurrentCleanupAdapter().transform(name, bytes, null); fail("Must reject unknown shape"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().startsWith("RC cleanup:")); }
    }
    private static ClassNode node(byte[] bytes) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node; }
    private static byte[] bytes(ClassNode node) { ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray(); }
    private static List<String> frames(MethodNode method) {
        List<String> result = new ArrayList<String>();
        for (AbstractInsnNode n : method.instructions.toArray()) if (n instanceof FrameNode) {
            FrameNode f = (FrameNode) n; result.add(String.valueOf(f.local) + "/" + f.stack);
        }
        return result;
    }

    private static RuntimeJar runtime() throws Exception {
        String rc = System.getProperty("ice.recurrent.jar", ""), iv = System.getProperty("ice.ivtoolkit.jar", "");
        Assume.assumeTrue(!rc.isEmpty() && !iv.isEmpty());
        return new RuntimeJar(new URL[] {new File(rc).toURI().toURL(), new File(iv).toURI().toURL()});
    }

    /** Execute the installed SRG mod classes against the test runtime's MCP Minecraft classes. */
    private static final class RuntimeJar extends URLClassLoader {
        RuntimeJar(URL[] urls) { super(urls, RecurrentCleanupAdapterTest.class.getClassLoader()); }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("ivorius.")) return super.loadClass(name, resolve);
            Class<?> result = findLoadedClass(name);
            if (result == null) {
                URL resource = findResource(name.replace('.', '/') + ".class");
                if (resource == null) throw new ClassNotFoundException(name);
                try (InputStream in = resource.openStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int n; while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
                    byte[] source = out.toByteArray();
                    if (DATA.equals(name)) {
                        source = new RecurrentCleanupAdapter().transform(name, source, null);
                        WorldgenRemainingAdaptersTest.verify(source);
                    }
                    if ((DATA + "$StructureEntry").equals(name) || (DATA + "$CustomEntry").equals(name)) {
                        ClassNode counted = node(source);
                        for (MethodNode method : counted.methods) if ("writeToNBT".equals(method.name)) {
                            InsnList count = new InsnList();
                            count.add(new InsnNode(name.endsWith("$CustomEntry") ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
                            count.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/rlcraft/ice/hooks/RecurrentCleanupAdapterTest", "recordWrite", "(Z)V", false));
                            method.instructions.insert(count);
                        }
                        source = bytes(counted);
                    }
                    ClassWriter writer = new ClassWriter(0);
                    new ClassReader(source).accept(new ClassRemapper(writer, new Remapper() {
                        @Override public String mapMethodName(String owner, String name, String descriptor) { return names().getOrDefault(name, name); }
                        @Override public String mapFieldName(String owner, String name, String descriptor) { return names().getOrDefault(name, name); }
                    }), 0);
                    byte[] mapped = writer.toByteArray(); result = defineClass(name, mapped, 0, mapped.length);
                } catch (Exception e) { throw new ClassNotFoundException(name, e); }
            }
            if (resolve) resolveClass(result); return result;
        }
        private static Map<String, String> names() { return NAMES; }
        private static final Map<String, String> NAMES = new HashMap<String, String>();
        static {
            String[][] names = {
                {"func_189551_b", "writeToNBT"}, {"func_76184_a", "readFromNBT"}, {"func_76185_a", "markDirty"},
                {"func_74782_a", "setTag"}, {"func_74778_a", "setString"}, {"func_74772_a", "setLong"},
                {"func_74757_a", "setBoolean"}, {"func_74768_a", "setInteger"}, {"func_74742_a", "appendTag"},
                {"func_151535_h", "toNBTTagIntArray"}, {"func_74775_l", "getCompoundTag"},
                {"func_150295_c", "getTagList"}, {"func_74745_c", "tagCount"}, {"func_150305_b", "getCompoundTagAt"},
                {"func_74779_i", "getString"}, {"func_74763_f", "getLong"}, {"func_74767_n", "getBoolean"},
                {"func_74762_e", "getInteger"}, {"func_74764_b", "hasKey"}, {"func_150297_b", "hasKey"}, {"func_74781_a", "getTag"},
                {"func_74759_k", "getIntArray"}, {"func_150306_c", "getIntArrayAt"},
                {"field_77276_a", "x"}, {"field_77275_b", "z"}, {"field_76190_i", "mapName"},
                {"field_78897_a", "minX"}, {"field_78895_b", "minY"}, {"field_78896_c", "minZ"},
                {"field_78893_d", "maxX"}, {"field_78894_e", "maxY"}, {"field_78892_f", "maxZ"}
            };
            for (String[] pair : names) NAMES.put(pair[0], pair[1]);
        }
    }
}
