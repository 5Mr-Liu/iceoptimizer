package dev.rlcraft.ice.hooks;

import static org.junit.Assert.*;

import dev.rlcraft.ice.optimizer.compat.model.ModelStateBatchSubclassAccess;
import dev.rlcraft.ice.optimizer.compat.mobends.MoBendsStateBatchTransformAccess;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import dev.rlcraft.ice.optimizer.compat.mobends.MoBendsRenderBridge;
import java.io.DataInputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import sun.misc.Unsafe;

public final class ModelSubclassStateBatchAdapterTest {
    @Test public void llibraryKeepsOpacityAndTransformsAndUsesItsPrivateDisplayList() throws Exception {
        File file = jar("ice.llibrary.jar");
        try (JarFile jar = new JarFile(file);
             ByteLoader loader = new ByteLoader(file)) {
            byte[] original = read(jar, AdvancedModelRendererVboAdapter.TARGET);
            byte[] adapted = new AdvancedModelRendererVboAdapter().transform(
                AdvancedModelRendererVboAdapter.TARGET, original, target());
            ClassNode before = node(original);
            ClassNode after = node(adapted);
            assertTrue(after.interfaces.contains(ModelSubclassStateBatchAdapter.ACCESS));
            MethodNode source = ModelStateBatchMethodProof.method(before, "func_78785_a", "(F)V");
            MethodNode retained = ModelStateBatchMethodProof.method(after,
                "rlcraftIce$func_78785_aSubclassBatchOriginal", "(F)V");
            // Only the original call-list target changes. Opacity, matrix and child calls survive.
            assertEquals(source.instructions.size(), retained.instructions.size());
            verify(after);
            Class<?> type = loader.define(AdvancedModelRendererVboAdapter.TARGET, adapted);
            Object model = unsafe().allocateInstance(type);
            ModelStateBatchSubclassAccess access = (ModelStateBatchSubclassAccess) model;
            assertTrue(access.ice$stateBatchRenderClassReady());
            assertFalse(access.ice$stateBatchNodeReady());
            set(type, model, "compiled", Boolean.TRUE);
            set(type, model, "displayList", Integer.valueOf(912));
            assertTrue(access.ice$stateBatchNodeReady());
            assertEquals(912, access.ice$stateBatchDisplayList());
        }
    }

    @Test public void changedLlibraryCallbackGraphKeepsImmediateCaptureWithoutScope() throws Exception {
        try (JarFile jar = new JarFile(jar("ice.llibrary.jar"))) {
            ClassNode source = node(read(jar, AdvancedModelRendererVboAdapter.TARGET));
            ModelStateBatchMethodProof.method(source, "func_78785_a", "(F)V")
                .instructions.insert(new InsnNode(Opcodes.NOP));
            byte[] adapted = new AdvancedModelRendererVboAdapter().transform(
                AdvancedModelRendererVboAdapter.TARGET, write(source), target());
            assertFalse(node(adapted).interfaces.contains(ModelSubclassStateBatchAdapter.ACCESS));
            assertNotNull(ModelStateBatchMethodProof.method(node(adapted),
                AdvancedModelRendererVboAdapter.ORIGINAL, "(F)V"));
            verify(node(adapted));
        }
    }

    @Test public void mobendsParentProofTracksTopologyAndRejectsUnreviewedSubclasses() throws Exception {
        File file = jar("ice.mobends.jar");
        try (JarFile jar = new JarFile(file);
             ByteLoader loader = new ByteLoader(file);
             EnabledMoBends module = new EnabledMoBends()) {
            byte[] parentBytes = new MoBendsTransformStateBatchAdapter().transform(
                MoBendsTransformStateBatchAdapter.TARGET,
                read(jar, MoBendsTransformStateBatchAdapter.TARGET), target());
            verify(node(parentBytes));
            Class<?> parentType = loader.define(MoBendsTransformStateBatchAdapter.TARGET, parentBytes);
            Object parent = unsafe().allocateInstance(parentType);
            assertTrue(((MoBendsStateBatchTransformAccess) parent).ice$stateBatchTransformClassReady());
            byte[] partBytes = new MoBendsModelPartAdapter().transform(
                MoBendsModelPartAdapter.TARGET, read(jar, MoBendsModelPartAdapter.TARGET), target());
            ClassNode partNode = node(partBytes);
            assertTrue(partNode.interfaces.contains(ModelSubclassStateBatchAdapter.ACCESS));
            MethodNode compiler = ModelStateBatchMethodProof.method(partNode,
                "func_78788_d", "(F)V");
            assertNotNull(ModelStateBatchMethodProof.method(partNode,
                "rlcraftIce$compileMoBendsDisplayListOriginal", "(F)V"));
            assertEquals(1, compiler.tryCatchBlocks.size());
            verify(partNode);
            // Tests use mapped Minecraft; expose just these SRG aliases to execute the accessors.
            partNode.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "field_78812_q", "Z", null, null));
            partNode.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "field_78811_r", "I", null, null));
            Class<?> partType = loader.define(MoBendsModelPartAdapter.TARGET, write(partNode));
            Object part = unsafe().allocateInstance(partType);
            ModelStateBatchSubclassAccess access = (ModelStateBatchSubclassAccess) part;
            assertTrue(access.ice$stateBatchRenderClassReady());
            assertFalse(access.ice$stateBatchNodeReady());
            set(partType, part, "field_78812_q", Boolean.TRUE);
            set(partType, part, "field_78811_r", Integer.valueOf(347));
            set(partType, part, "parent", parent);
            assertTrue(access.ice$stateBatchNodeReady());
            assertEquals(347, access.ice$stateBatchDisplayList());
            Object prepared = get(partType, part, MoBendsModelPartAdapter.CHAIN_FIELD);
            assertTrue(access.ice$stateBatchNodeReady());
            assertSame(prepared, get(partType, part, MoBendsModelPartAdapter.CHAIN_FIELD));
            Class<?> extended = loader.loadClass("goblinbob.mobends.core.client.model.ModelPartExtended");
            Object unknown = unsafe().allocateInstance(extended);
            assertFalse(((ModelStateBatchSubclassAccess) unknown).ice$stateBatchRenderClassReady());
            set(partType, part, "parent", unknown);
            assertFalse(access.ice$stateBatchNodeReady());
            set(partType, part, "parent", null);
            assertTrue(access.ice$stateBatchNodeReady());
            module.disable();
            assertFalse(access.ice$stateBatchNodeReady());
        }
    }

    @Test public void changedTransformParentCannotAcquireProofMarker() throws Exception {
        try (JarFile jar = new JarFile(jar("ice.mobends.jar"))) {
            ClassNode source = node(read(jar, MoBendsTransformStateBatchAdapter.TARGET));
            ModelStateBatchMethodProof.method(source, "applyLocalTransform", "(F)V")
                .instructions.insert(new InsnNode(Opcodes.NOP));
            try {
                new MoBendsTransformStateBatchAdapter().transform(
                    MoBendsTransformStateBatchAdapter.TARGET, write(source), target());
                fail("unreviewed transform accepted");
            } catch (OptimizerAdapterSkippedException expected) { }
        }
    }

    private static File jar(String property) {
        File file = new File(System.getProperty(property, ""));
        Assume.assumeTrue("configure " + property, file.isFile());
        return file;
    }
    private static TargetSpec target() {
        return new TargetSpec("test", "mobends-model-render", "test", Collections.<String>emptySet());
    }
    private static byte[] read(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name + ".class");
        byte[] bytes = new byte[(int) entry.getSize()];
        try (DataInputStream input = new DataInputStream(jar.getInputStream(entry))) { input.readFully(bytes); }
        return bytes;
    }
    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }
    private static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
    private static void verify(ClassNode node) throws Exception {
        for (MethodNode method : node.methods) {
            new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
        }
    }
    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
    private static void set(Class<?> type, Object object, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
    private static Object get(Class<?> type, Object object, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
    private static final class ByteLoader extends URLClassLoader {
        private final JarFile jar;
        ByteLoader(File file) throws Exception {
            super(new URL[] { file.toURI().toURL() }, ModelSubclassStateBatchAdapterTest.class.getClassLoader());
            jar = new JarFile(file);
        }
        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!name.startsWith("net.ilexiconn.llibrary.")) return super.findClass(name);
            try { return define(name, read(jar, name.replace('.', '/'))); }
            catch (Exception failure) { throw new ClassNotFoundException(name, failure); }
        }
        @Override public void close() throws java.io.IOException { try { super.close(); } finally { jar.close(); } }
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name.replace('/', '.'), bytes, 0, bytes.length);
        }
    }
    private static final class EnabledMoBends implements AutoCloseable {
        private final Field mask = OptimizerRegistry.class.getDeclaredField("operationalMask");
        private final Field activated = MoBendsRenderBridge.class.getDeclaredField("modelActivated");
        private final long originalMask;
        private final boolean originalActivated;
        EnabledMoBends() throws Exception {
            mask.setAccessible(true);
            activated.setAccessible(true);
            originalMask = mask.getLong(null);
            originalActivated = activated.getBoolean(null);
            activated.setBoolean(null, true);
            mask.setLong(null, originalMask | (1L << OptimizationModule.MOBENDS_MODEL_RENDER.ordinal()));
        }
        void disable() throws Exception {
            mask.setLong(null, originalMask & ~(1L << OptimizationModule.MOBENDS_MODEL_RENDER.ordinal()));
        }
        @Override public void close() throws Exception {
            mask.setLong(null, originalMask);
            activated.setBoolean(null, originalActivated);
        }
    }
}
