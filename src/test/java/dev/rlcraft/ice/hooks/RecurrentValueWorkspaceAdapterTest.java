package dev.rlcraft.ice.hooks;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.function.Supplier;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;
import static org.junit.Assert.*;

public class RecurrentValueWorkspaceAdapterTest {
    @Test public void realRuinsOnlyReplacesTwoFieldAllocationsAndKeepsRandomWork() throws Exception {
        File jar = jar();
        byte[] original = read(jar, RecurrentValueWorkspaceAdapter.RUINS);
        assertEquals("b80bdc14384cada7356ccee649511344caff9fe746b2b8805a6fff43b5da74a6",
            CoreClassFingerprint.sha256(original));
        byte[] changed = new RecurrentValueWorkspaceAdapter().transform("ruins", original, null);
        ClassNode node = node(changed);
        int creates = 0, constructors = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof TypeInsnNode && instruction.getOpcode() == Opcodes.NEW
                    && ((TypeInsnNode) instruction).desc.equals(RecurrentValueWorkspaceAdapter.WORKSPACE)) {
                    creates++;
                    ((TypeInsnNode) instruction).desc = RecurrentValueWorkspaceAdapter.FIELD;
                } else if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (call.owner.equals(RecurrentValueWorkspaceAdapter.WORKSPACE)) {
                        assertEquals("<init>", call.name);
                        assertEquals("([I)V", call.desc);
                        constructors++;
                        call.owner = RecurrentValueWorkspaceAdapter.FIELD;
                    }
                }
            }
        }
        assertEquals(2, creates); assertEquals(2, constructors);
        assertAllMethodsEqual(node(original), node);
        verify(jar, RecurrentValueWorkspaceAdapter.RUINS, changed);
    }

    @Test public void realNbtReloadReplacesBothConstructorReferencesAndLeavesCacheAndSerializationIntact()
        throws Exception {
        File jar = jar();
        byte[] original = read(jar, RecurrentValueWorkspaceAdapter.INSTANCE);
        assertEquals("7f7272c59fac6ec5b923a38f24400808383d77b389a600d5fafd5dc79be61ac1",
            CoreClassFingerprint.sha256(original));
        byte[] changed = new RecurrentValueWorkspaceAdapter().transform("instance", original, null);
        ClassNode node = node(changed);
        int references = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof InvokeDynamicInsnNode)) continue;
                InvokeDynamicInsnNode factory = (InvokeDynamicInsnNode) instruction;
                for (int i = 0; i < factory.bsmArgs.length; i++) {
                    if (!(factory.bsmArgs[i] instanceof Handle)) continue;
                    Handle handle = (Handle) factory.bsmArgs[i];
                    if (handle.getOwner().equals(RecurrentValueWorkspaceAdapter.WORKSPACE)) {
                        assertEquals(Opcodes.H_NEWINVOKESPECIAL, handle.getTag());
                        assertEquals("()V", handle.getDesc());
                        references++;
                        factory.bsmArgs[i] = new Handle(Opcodes.H_NEWINVOKESPECIAL,
                            RecurrentValueWorkspaceAdapter.FIELD, "<init>", "()V", false);
                    }
                }
            }
        }
        assertEquals(2, references);
        assertAllMethodsEqual(node(original), node);
        verify(jar, RecurrentValueWorkspaceAdapter.INSTANCE, changed);
    }

    @Test public void finalOptimizerChainIncludesBothReviewedTargets() throws Exception {
        File jar = jar();
        for (String name : new String[] {RecurrentValueWorkspaceAdapter.RUINS,
                                         RecurrentValueWorkspaceAdapter.INSTANCE}) {
            byte[] original = read(jar, name);
            byte[] expected = new RecurrentValueWorkspaceAdapter().transform(name, original, null);
            byte[] transformed = new IceOptimizerTransformer().transform(
                name.replace('/', '.'), name.replace('/', '.'), original);
            assertFalse(Arrays.equals(original, transformed));
            assertAllMethodsEqual(node(expected), node(transformed));
        }
    }

    @Test public void actualNbtFactoryBootstrapCreatesWorkspaceFields() throws Exception {
        byte[] changed = new RecurrentValueWorkspaceAdapter().transform("instance",
            read(jar(), RecurrentValueWorkspaceAdapter.INSTANCE), null);
        ClassWriter factoryClass = new ClassWriter(0);
        String owner = "dev/rlcraft/ice/hooks/RuinsFieldFactoryFixture";
        factoryClass.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        int factories = 0;
        for (MethodNode method : node(changed).methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof InvokeDynamicInsnNode)) continue;
                InvokeDynamicInsnNode factory = (InvokeDynamicInsnNode) instruction;
                boolean workspace = false;
                for (Object argument : factory.bsmArgs)
                    if (argument instanceof Handle && ((Handle) argument).getOwner()
                        .equals(RecurrentValueWorkspaceAdapter.WORKSPACE)) workspace = true;
                if (!workspace) continue;
                MethodVisitor output = factoryClass.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "create" + factories++, "()Ljava/util/function/Supplier;", null, null);
                output.visitCode();
                output.visitInvokeDynamicInsn(factory.name, factory.desc, factory.bsm, factory.bsmArgs);
                output.visitInsn(Opcodes.ARETURN);
                output.visitMaxs(1, 0);
                output.visitEnd();
            }
        }
        assertEquals(2, factories);
        factoryClass.visitEnd();
        final byte[] bytes = factoryClass.toByteArray();
        Class<?> fixture = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
        }.define();
        for (int i = 0; i < factories; i++) {
            Supplier<?> supplier = (Supplier<?>) fixture.getMethod("create" + i).invoke(null);
            Object first = supplier.get(), second = supplier.get();
            assertEquals("dev.rlcraft.ice.optimizer.compat.recurrent.RecurrentValueField",
                first.getClass().getName());
            assertNotSame(first, second);
        }
    }

    private static void verify(File jar, final String name, final byte[] changed) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toURI().toURL()},
            RecurrentValueWorkspaceAdapterTest.class.getClassLoader()) {
            @Override protected synchronized Class<?> loadClass(String type, boolean resolve)
                throws ClassNotFoundException {
                if (!type.startsWith("ivorius.reccomplex.")) return super.loadClass(type, resolve);
                Class<?> found = findLoadedClass(type);
                if (found == null) found = type.equals(name.replace('/', '.'))
                    ? defineClass(type, changed, 0, changed.length) : findClass(type);
                if (resolve) resolveClass(found);
                return found;
            }
        }) {
            loader.loadClass(name.replace('/', '.')).getDeclaredMethods();
        }
    }

    private static File jar() {
        String value = System.getProperty("ice.recurrent.jar", "");
        Assume.assumeFalse("Missing ice.recurrent.jar", value.isEmpty());
        File file = new File(value);
        assertTrue(file.isFile());
        return file;
    }

    private static byte[] read(File path, String name) throws Exception {
        try (JarFile jar = new JarFile(path);
             InputStream input = jar.getInputStream(jar.getJarEntry(name + ".class"))) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static void assertAllMethodsEqual(ClassNode expected, ClassNode actual) {
        assertEquals(expected.fields.size(), actual.fields.size());
        assertEquals(expected.methods.size(), actual.methods.size());
        for (int i = 0; i < expected.methods.size(); i++)
            assertEquals(expected.methods.get(i).name, text(expected.methods.get(i)), text(actual.methods.get(i)));
    }

    private static String text(MethodNode method) {
        Textifier text = new Textifier();
        method.accept(new TraceMethodVisitor(text));
        StringWriter output = new StringWriter();
        text.print(new PrintWriter(output));
        return output.toString();
    }
}
