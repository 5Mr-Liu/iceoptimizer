package dev.rlcraft.ice.hooks;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** Keeps the archived adapter contract and verifies the slim production route. */
public final class TrinketsSyncAdapterTest {
    private static final String WRAPPER = "net/minecraftforge/fml/common/network/simpleimpl/SimpleNetworkWrapper";

    @Test public void actualNetworkHandlerRoutesOnlyPlayerAndTrackingSendsThroughTheBridge() throws Exception {
        byte[] original = original();
        String dotted = TrinketsSyncAdapter.TARGET.replace('/', '.');
        assertTrue(OptimizerTargetCatalog.findAll(dotted).isEmpty());
        assertArrayEquals(original, new IceClientOptimizerTransformer().transform(dotted, dotted, original));
        // Exercise the retained experimental implementation without installing it.
        byte[] transformed = new TrinketsSyncAdapter().transform(dotted, original, null);
        assertFalse(Arrays.equals(original, transformed));
        ClassNode before = node(original), after = node(transformed);
        for (MethodNode method : after.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0)
                new Analyzer(new BasicVerifier()).analyze(after.name, method);
        }
        assertEquals(1, calls(after, "sendTo", TrinketsSyncAdapter.BRIDGE, "sendTo"));
        assertEquals(1, calls(after, "sendToTracking", TrinketsSyncAdapter.BRIDGE, "sendToTracking"));
        assertEquals(0, calls(after, "sendTo", WRAPPER, "sendTo"));
        assertEquals(0, calls(after, "sendToTracking", WRAPPER, "sendToAllTracking"));
        for (String method : new String[] { "sendToAll", "sendToAllAround", "sendToDimension", "sendToServer" })
            assertEquals(method, calls(before, method, WRAPPER, method), calls(after, method, WRAPPER, method));
        reject(transformed);
    }

    @Test public void changedSendingBoundaryIsRejectedBeforeAnyPartialResultIsReturned() throws Exception {
        ClassNode changed = node(original());
        for (MethodNode method : changed.methods) if ("sendToTracking".equals(method.name)) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (WRAPPER.equals(call.owner) && "sendToAllTracking".equals(call.name)) call.name = "changedRouting";
            }
        }
        ClassWriter writer = new ClassWriter(0); changed.accept(writer); reject(writer.toByteArray());
    }

    private static int calls(ClassNode owner, String methodName, String calledOwner, String calledName) {
        int count = 0;
        for (MethodNode method : owner.methods) if (methodName.equals(method.name))
            for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (calledOwner.equals(call.owner) && calledName.equals(call.name)) count++;
            }
        return count;
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM5); new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES); return node;
    }

    private static void reject(byte[] bytes) {
        try { new TrinketsSyncAdapter().transform(TrinketsSyncAdapter.TARGET, bytes, null); fail("changed routing must fail closed"); }
        catch (IllegalStateException expected) { }
    }

    private static byte[] original() throws Exception {
        String path = System.getProperty("ice.trinkets.jar", "");
        Assume.assumeFalse("configure ice.trinkets.jar", path.isEmpty());
        try (JarFile jar = new JarFile(path); InputStream input = jar.getInputStream(jar.getJarEntry(TrinketsSyncAdapter.TARGET + ".class"))) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }
}
