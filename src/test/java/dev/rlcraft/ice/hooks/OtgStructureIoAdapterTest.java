package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.ClientOptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.jar.JarFile;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/** Execute actual OTG algorithms; fixtures replace only engine logging and disk output. */
public final class OtgStructureIoAdapterTest {
    private static final String TEST = "dev/rlcraft/ice/hooks/OtgStructureIoAdapterTest";
    private boolean oldEnabled;
    private boolean oldModule;
    private File jar;
    private URLClassLoader dependencies;
    private static Method coordinateFactory;
    private static int coordinateAllocations;
    private static int listAllocations;
    private static final List<String> recorded = new ArrayList<String>();

    @Before public void setup() throws Exception {
        String configured = System.getProperty("ice.otg.jar", "");
        Assume.assumeTrue("actual OTG jar required", !configured.isEmpty());
        jar = new File(configured);
        dependencies = new URLClassLoader(new URL[] { jar.toURI().toURL() }, getClass().getClassLoader());
        oldEnabled = OptimizerConfig.settings.enabled;
        oldModule = OptimizerConfig.settings.otgSynchronousFileCache;
        enable(true);
    }
    @After public void restore() throws Exception {
        if (dependencies != null) {
            dependencies.close();
            OptimizerConfig.settings.enabled = oldEnabled;
            OptimizerConfig.settings.otgSynchronousFileCache = oldModule;
            OptimizerRegistry.configure(ClientOptimizerConfig.capture());
        }
    }
    private static void enable(boolean module) {
        OptimizerConfig.settings.enabled = true;
        OptimizerConfig.settings.otgSynchronousFileCache = module;
        OptimizerRegistry.configure(ClientOptimizerConfig.capture());
        OptimizerRegistry.breaker(OptimizationModule.OTG_SYNC_FILE_CACHE).patchInstalled("fixture", "test");
    }

    @Test public void compressionMatchesActualDefaultZlibBytesAcrossSizesAndEntropy() throws Exception {
        byte[] original = read(OtgStructureCompressionAdapter.TARGET);
        assertEquals("32a02ca984f6c053518b29721b253e567b32df22f6ce59b075933f49650e0c03",
            CoreClassFingerprint.sha256(original));
        byte[] patched = new OtgStructureCompressionAdapter().transform(
            OtgStructureCompressionAdapter.TARGET, original, null);
        assertEquals(1, countCalls(patched, "compress", "com/pg85/otg/OTG", "getPluginConfig"));
        assertEquals(1, countCalls(patched, "compress", OtgStructureCompressionAdapter.BRIDGE, "compress"));
        Method reference = compressionMethod(original);
        Method optimized = compressionMethod(patched);
        Random random = new Random(12345);
        for (int size : new int[] { 0, 1, 1023, 1024, 65536, 65537, 1_048_576 }) {
            byte[] bytes = new byte[size];
            random.nextBytes(bytes);
            assertArrayEquals((byte[]) reference.invoke(null, (Object) bytes),
                (byte[]) optimized.invoke(null, (Object) bytes));
            Arrays.fill(bytes, (byte) 0);
            assertArrayEquals((byte[]) reference.invoke(null, (Object) bytes),
                (byte[]) optimized.invoke(null, (Object) bytes));
        }
        enable(false);
        assertArrayEquals((byte[]) reference.invoke(null, (Object) new byte[30000]),
            (byte[]) optimized.invoke(null, (Object) new byte[30000]));
    }

    @Test public void reusedCompressionScratchIsIndependentAcrossThreadsAndCalls() throws Exception {
        Method reference = compressionMethod(read(OtgStructureCompressionAdapter.TARGET));
        final Method optimized = compressionMethod(new OtgStructureCompressionAdapter().transform(
            OtgStructureCompressionAdapter.TARGET, read(OtgStructureCompressionAdapter.TARGET), null));
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<byte[]>> results = new ArrayList<Future<byte[]>>();
            List<byte[]> expected = new ArrayList<byte[]>();
            for (int index = 0; index < 16; index++) {
                final byte[] input = new byte[18000 + index * 101];
                new Random(index).nextBytes(input);
                expected.add((byte[]) reference.invoke(null, (Object) input));
                results.add(executor.submit(new Callable<byte[]>() {
                    @Override public byte[] call() throws Exception {
                        return (byte[]) optimized.invoke(null, (Object) input);
                    }
                }));
            }
            for (int index = 0; index < results.size(); index++) assertArrayEquals(expected.get(index), results.get(index).get());
        } finally { executor.shutdownNow(); }
    }

    @Test public void saveGroupingOrderDirtyFlagsAndCoordinatesMatchWithFewerAllocations() throws Exception {
        byte[] original = read(OtgStructureSaveAllocationAdapter.TARGET);
        assertEquals("bde1ef06441acb565528d14f1f4f237853eb0bd916d048ebb19764e33eff4141",
            CoreClassFingerprint.sha256(original));
        byte[] patched = new OtgStructureSaveAllocationAdapter().transform(
            OtgStructureSaveAllocationAdapter.TARGET, original, null);
        // The private disk writer and backup/compression sequence are not edited.
        assertEquals(methodInstructions(original, "saveStructuresRegionFile"),
            methodInstructions(patched, "saveStructuresRegionFile"));
        Class<?> coordinate = dependencies.loadClass("com.pg85.otg.util.ChunkCoordinate");
        coordinateFactory = coordinate.getMethod("fromChunkCoords", int.class, int.class);
        Class<?> region = dependencies.loadClass("com.pg85.otg.customobjects.structures.StructureDataRegion");
        Class<?> structure = dependencies.loadClass("com.pg85.otg.customobjects.structures.CustomStructure");
        Class<?> localWorld = dependencies.loadClass("com.pg85.otg.common.LocalWorld");
        Object world = Proxy.newProxyInstance(dependencies, new Class<?>[] { localWorld },
            (proxy, method, arguments) -> method.getName().equals("getDimensionId") ? 0 : null);
        ByteLoader fixtures = new ByteLoader(dependencies);
        Class<?> dummy = fixtures.define("ice.fixture.OtgStructure", dummyStructure());
        Object a = dummy.getConstructor(int.class).newInstance(1);
        Object b = dummy.getConstructor(int.class).newInstance(2);
        Map<Object, Object> regions = new LinkedHashMap<Object, Object>();
        Method set = region.getMethod("setStructure", int.class, int.class, structure, boolean.class);
        for (int index = 0; index < 3; index++) {
            Object data = region.getConstructor().newInstance();
            if (index != 1) {
                for (int x = 0; x < 100; x++) for (int z = 0; z < 100; z++) {
                    if (index == 2 || x == 7 && z < 3) set.invoke(data, x, z, ((x + z) & 1) == 0 ? a : b, true);
                }
            }
            regions.put(coordinateFactory.invoke(null, index - 1, index + 2), data);
        }
        Method reference = saveMethod(original, localWorld);
        Method optimized = saveMethod(patched, localWorld);
        markDirty(regions, region);
        coordinateAllocations = 0; listAllocations = 0; recorded.clear();
        reference.invoke(null, regions, world);
        int originalCoordinates = coordinateAllocations;
        int originalLists = listAllocations;
        List<String> expected = new ArrayList<String>(recorded);
        markDirty(regions, region);
        coordinateAllocations = 0; listAllocations = 0; recorded.clear();
        optimized.invoke(null, regions, world);
        assertEquals(expected, recorded);
        assertEquals("all three dirty regions are visited even if empty", 30000, originalCoordinates);
        assertEquals("only occupied cells allocate coordinates", 10003, coordinateAllocations);
        assertEquals("one list per structure per populated region", 4, listAllocations);
        assertTrue(originalLists > 10000);
        for (Object data : regions.values()) assertEquals(false, region.getMethod("requiresSave").invoke(data));
        enable(false);
        markDirty(regions, region);
        coordinateAllocations = 0; recorded.clear();
        optimized.invoke(null, regions, world);
        assertEquals(expected, recorded);
        assertEquals(originalCoordinates, coordinateAllocations);
    }

    public static Object newCoordinate(int x, int z) throws Exception {
        coordinateAllocations++;
        return coordinateFactory.invoke(null, x, z);
    }
    public static final class CountingList extends ArrayList<Object> {
        public CountingList() { listAllocations++; }
    }
    @SuppressWarnings("unchecked")
    public static void capture(Object world, int dimension, Object region, Object groups) throws Exception {
        StringBuilder snapshot = new StringBuilder(dimension + ":" + region + ":");
        for (Map.Entry<String, Map<Object, List<Object>>> group : ((Map<String, Map<Object, List<Object>>>) groups).entrySet()) {
            snapshot.append(group.getKey()).append('=');
            for (Map.Entry<Object, List<Object>> item : group.getValue().entrySet()) {
                snapshot.append(item.getKey().getClass().getField("id").getInt(item.getKey())).append('[');
                for (Object chunk : item.getValue()) snapshot.append(chunk).append(';');
                snapshot.append(']');
            }
        }
        recorded.add(snapshot.toString());
    }
    private static void markDirty(Map<Object, Object> regions, Class<?> region) throws Exception {
        for (Object data : regions.values()) region.getMethod("markSaveRequired").invoke(data);
    }

    private Method compressionMethod(byte[] bytes) throws Exception {
        Class<?> type = new ByteLoader(dependencies).define(OtgStructureCompressionAdapter.TARGET.replace('/', '.'), fixture(bytes, false));
        return type.getMethod("compress", byte[].class);
    }
    private Method saveMethod(byte[] bytes, Class<?> localWorld) throws Exception {
        Class<?> type = new ByteLoader(dependencies).define(OtgStructureSaveAllocationAdapter.TARGET.replace('/', '.'), fixture(bytes, true));
        Method method = type.getDeclaredMethod("saveStructureData", Map.class, localWorld);
        method.setAccessible(true);
        return method;
    }
    private static byte[] fixture(byte[] bytes, boolean saving) {
        ClassNode node = new ClassNode(Opcodes.ASM5);
        ClassReader reader = new ClassReader(bytes);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        List<MethodNode> edited = new ArrayList<MethodNode>();
        for (MethodNode method : node.methods) {
            if (!(saving ? method.name.startsWith("saveStructureData") || method.name.equals(OtgStructureSaveAllocationAdapter.ORIGINAL)
                : method.name.equals("compress"))) continue;
            edited.add(method);
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (call.owner.equals("com/pg85/otg/OTG") && call.name.equals("getPluginConfig")) {
                        AbstractInsnNode field = nextCode(call);
                        assertTrue(field instanceof FieldInsnNode && ((FieldInsnNode) field).name.equals("spawnLog"));
                        method.instructions.set(call, new InsnNode(Opcodes.ICONST_0));
                        method.instructions.remove(field);
                    } else if (call.owner.equals("com/pg85/otg/OTG") && call.name.equals("log")) {
                        method.instructions.insertBefore(call, new InsnNode(Opcodes.POP));
                        method.instructions.insertBefore(call, new InsnNode(Opcodes.POP));
                        method.instructions.insertBefore(call, new InsnNode(Opcodes.POP));
                        method.instructions.remove(call);
                    } else if (saving && call.owner.equals(OtgStructureSaveAllocationAdapter.TARGET)
                        && call.name.equals("saveStructuresRegionFile")) {
                        call.owner = TEST; call.name = "capture";
                        call.desc = "(Ljava/lang/Object;ILjava/lang/Object;Ljava/lang/Object;)V";
                    } else if (saving && call.owner.equals("com/pg85/otg/util/ChunkCoordinate") && call.name.equals("fromChunkCoords")) {
                        call.owner = TEST; call.name = "newCoordinate"; call.desc = "(II)Ljava/lang/Object;";
                        method.instructions.insert(call, new TypeInsnNode(Opcodes.CHECKCAST, "com/pg85/otg/util/ChunkCoordinate"));
                    } else if (saving && call.owner.equals("java/util/ArrayList") && call.name.equals("<init>")) {
                        call.owner = TEST + "$CountingList";
                    }
                } else if (saving && instruction instanceof TypeInsnNode && instruction.getOpcode() == Opcodes.NEW
                    && ((TypeInsnNode) instruction).desc.equals("java/util/ArrayList")) {
                    ((TypeInsnNode) instruction).desc = TEST + "$CountingList";
                }
            }
        }
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String left, String right) {
                if (left.equals(TEST + "$CountingList") && right.equals("java/util/ArrayList")
                    || right.equals(TEST + "$CountingList") && left.equals("java/util/ArrayList")) return "java/util/ArrayList";
                return "java/lang/Object";
            }
        };
        return OtgAsmMethodWriter.write(reader, writer, edited.toArray(new MethodNode[edited.size()]));
    }
    private static byte[] dummyStructure() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "ice/fixture/OtgStructure", null,
            "com/pg85/otg/customobjects/structures/CustomStructure", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "id", "I", null, null).visitEnd();
        org.objectweb.asm.MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(I)V", null, null);
        constructor.visitCode(); constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/pg85/otg/customobjects/structures/CustomStructure", "<init>", "()V", false);
        constructor.visitVarInsn(Opcodes.ALOAD, 0); constructor.visitVarInsn(Opcodes.ILOAD, 1);
        constructor.visitFieldInsn(Opcodes.PUTFIELD, "ice/fixture/OtgStructure", "id", "I");
        constructor.visitInsn(Opcodes.RETURN); constructor.visitMaxs(0, 0); constructor.visitEnd();
        org.objectweb.asm.MethodVisitor hash = writer.visitMethod(Opcodes.ACC_PUBLIC, "hashCode", "()I", null, null);
        hash.visitCode(); hash.visitVarInsn(Opcodes.ALOAD, 0); hash.visitFieldInsn(Opcodes.GETFIELD, "ice/fixture/OtgStructure", "id", "I");
        hash.visitInsn(Opcodes.IRETURN); hash.visitMaxs(0, 0); hash.visitEnd();
        org.objectweb.asm.MethodVisitor equals = writer.visitMethod(Opcodes.ACC_PUBLIC, "equals", "(Ljava/lang/Object;)Z", null, null);
        equals.visitCode(); equals.visitVarInsn(Opcodes.ALOAD, 0); equals.visitVarInsn(Opcodes.ALOAD, 1);
        equals.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "equals", "(Ljava/lang/Object;)Z", false);
        equals.visitInsn(Opcodes.IRETURN); equals.visitMaxs(0, 0); equals.visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }
    private byte[] read(String target) throws Exception {
        try (JarFile source = new JarFile(jar); InputStream input = source.getInputStream(source.getJarEntry(target + ".class"))) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
            return bytes.toByteArray();
        }
    }
    private static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
        do { instruction = instruction.getNext(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }
    private static int countCalls(byte[] bytes, String methodName, String owner, String name) {
        int count = 0; ClassNode node = new ClassNode(Opcodes.ASM5); new ClassReader(bytes).accept(node, 0);
        for (MethodNode method : node.methods) if (method.name.equals(methodName)) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.owner.equals(owner) && call.name.equals(name)) count++;
            }
        }
        return count;
    }
    private static List<String> methodInstructions(byte[] bytes, String name) {
        ClassNode node = new ClassNode(Opcodes.ASM5); new ClassReader(bytes).accept(node, 0);
        for (MethodNode method : node.methods) if (method.name.equals(name)) {
            List<String> result = new ArrayList<String>();
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction.getOpcode() < 0) continue;
                String text = Integer.toString(instruction.getOpcode());
                if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction; text += call.owner + call.name + call.desc;
                } else if (instruction instanceof FieldInsnNode) {
                    FieldInsnNode field = (FieldInsnNode) instruction; text += field.owner + field.name + field.desc;
                } else if (instruction instanceof TypeInsnNode) text += ((TypeInsnNode) instruction).desc;
                result.add(text);
            }
            return result;
        }
        return Collections.emptyList();
    }
    private static final class ByteLoader extends ClassLoader {
        ByteLoader(ClassLoader parent) { super(parent); }
        Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
    }
}
