package dev.rlcraft.ice.hooks;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.Assert.*;

/** JVM verification is required: ASM BasicVerifier treats references as one generic value. */
public class RecurrentReflectionAdapterTest {
    private static final String MAP = "ivorius.reccomplex.world.gen.feature.structure.MapGenStructureHook";
    private static final String EVENTS = "ivorius.reccomplex.events.handlers.RCTerrainGenEventHandler";

    @Test public void bothRealClassesVerifyAllMethodsWithoutInitialization() throws Exception {
        for (String name : new String[] { MAP, EVENTS }) {
            byte[] original = WorldgenRemainingAdaptersTest.read("ice.recurrent.jar", name);
            byte[] changed = new RecurrentReflectionAdapter().transform(name, original, null);
            verifyRealClass(name, original);
            verifyRealClass(name, changed);
            assertEquals(WorldgenRemainingAdaptersTest.calls(original, "invoke"),
                WorldgenRemainingAdaptersTest.calls(changed, "invoke"));
            assertEquals(0, WorldgenRemainingAdaptersTest.calls(changed, "findMethod"));
            assertEquals(0, WorldgenRemainingAdaptersTest.calls(changed, "getDeclaredMethod"));
        }
    }

    @Test public void originalHierarchyFramesSurviveAndOnlyEntryFramesAreAdded() throws Exception {
        for (String name : new String[] { MAP, EVENTS }) {
            byte[] original = WorldgenRemainingAdaptersTest.read("ice.recurrent.jar", name);
            ClassNode before = expanded(original);
            ClassNode after = expanded(new RecurrentReflectionAdapter().transform(name, original, null));
            for (int index = 0; index < before.methods.size(); index++) {
                MethodNode left = before.methods.get(index), right = after.methods.get(index);
                List<String> actual = frames(right);
                if (name.equals(EVENTS) && (left.name.equals("hasAmountData") || left.name.equals("getModifiedAmount")
                    || left.name.equals("setModifiedAmount"))) actual.remove(0);
                assertEquals(name + "." + left.name + left.desc, frames(left), actual);
            }
        }
    }

    @Test public void oldObjectFrameRecomputationReproducesReportedVerifyError() throws Exception {
        byte[] original = WorldgenRemainingAdaptersTest.read("ice.recurrent.jar", MAP);
        byte[] changed = new RecurrentReflectionAdapter().transform(MAP, original, null);
        byte[] broken = reproduceOldFrameWriter(changed);
        // This passed the old test and is intentionally insufficient for a JVM class.
        WorldgenRemainingAdaptersTest.verify(broken);
        try {
            verifyRealClass(MAP, broken);
            fail("The 1.0.24 Object-merge writer must be rejected by the JVM");
        } catch (VerifyError expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("ReflectiveOperationException"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("java/lang/Object"));
        }
    }

    static void verifyRealClass(String name, byte[] bytes) throws Exception {
        URL jar = new File(System.getProperty("ice.recurrent.jar")).toURI().toURL();
        try (URLClassLoader loader = new URLClassLoader(new URL[] { jar }, RecurrentReflectionAdapterTest.class.getClassLoader()) {
            @Override protected synchronized Class<?> loadClass(String type, boolean resolve) throws ClassNotFoundException {
                if (!type.startsWith("ivorius.reccomplex.")) return super.loadClass(type, resolve);
                Class<?> found = findLoadedClass(type);
                if (found == null) found = type.equals(name) ? defineClass(type, bytes, 0, bytes.length) : findClass(type);
                if (resolve) resolveClass(found);
                return found;
            }
        }) {
            Class<?> target = Class.forName(name, false, loader);
            assertTrue(target.getDeclaredMethods().length > 0);
            assertTrue(target.getDeclaredConstructors().length > 0);
        }
    }

    private static byte[] reproduceOldFrameWriter(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes); ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, 0);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override protected String getCommonSuperClass(String a, String b) { return "java/lang/Object"; }
        };
        node.accept(writer); return writer.toByteArray();
    }
    private static ClassNode expanded(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM5); new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES); return node;
    }
    private static List<String> frames(MethodNode method) {
        List<String> frames = new ArrayList<String>();
        for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof FrameNode) {
            FrameNode frame = (FrameNode) instruction;
            frames.add(String.valueOf(frame.local) + " -> " + String.valueOf(frame.stack));
        }
        return frames;
    }
}
