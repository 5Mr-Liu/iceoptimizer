package dev.rlcraft.ice.hooks;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

public class EntityQueryOptimizationAdapterTest {
    @Test public void blockHelperKeepsDynamicShapeEvaluationAndComposesWithLightPublicationHook() throws Exception {
        byte[] original = resource("net/minecraft/block/Block.class");
        byte[] changed = new CollisionBoxAllocationAdapter().transform("net.minecraft.block.Block", original, null);
        changed = new SrpLightCandidateIndexAdapter().transform("net.minecraft.block.Block", changed, null);
        verify(changed);
        ClassNode before = node(original), after = node(changed);
        MethodNode helper = method(after, CollisionBoxAllocationAdapter.DESC);
        assertEquals(1, calls(helper, CollisionBoxAllocationAdapter.BRIDGE, "append"));
        assertEquals(0, calls(helper, "net/minecraft/util/math/AxisAlignedBB", "offset"));
        String dynamic = "(Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/world/World;"
            + "Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/AxisAlignedBB;"
            + "Ljava/util/List;Lnet/minecraft/entity/Entity;Z)V";
        assertEquals(method(before, dynamic).instructions.size(), method(after, dynamic).instructions.size());
        assertEquals(1, calls(method(after, "(F)Lnet/minecraft/block/Block;"),
            SrpLightCandidateIndexAdapter.BRIDGE, "blockLightChanged"));
    }

    @Test public void sectionMaintainsIndividualWritesAndDropsIndexBeforeBothBulkLoaders() throws Exception {
        byte[] changed = new SrpLightCandidateIndexAdapter().transform(
            "net.minecraft.world.chunk.BlockStateContainer",
            resource("net/minecraft/world/chunk/BlockStateContainer.class"), null);
        verify(changed);
        ClassNode node = node(changed);
        assertTrue(node.interfaces.contains(SrpLightCandidateIndexAdapter.ACCESS));
        assertEquals(1, calls(method(node, "(ILnet/minecraft/block/state/IBlockState;)V"),
            SrpLightCandidateIndexAdapter.BRIDGE, "changed"));
        int resets = 0;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("read") && !method.name.equals("func_186010_a")
                && !method.desc.equals("([BLnet/minecraft/world/chunk/NibbleArray;Lnet/minecraft/world/chunk/NibbleArray;)V")) continue;
            AbstractInsnNode first = method.instructions.getFirst();
            while (first.getOpcode() < 0) first = first.getNext();
            assertEquals(Opcodes.ALOAD, first.getOpcode());
            assertEquals(Opcodes.ACONST_NULL, first.getNext().getOpcode());
            assertEquals(Opcodes.PUTFIELD, first.getNext().getNext().getOpcode());
            resets++;
        }
        assertEquals(2, resets);
    }

    @Test public void realRltNearestQueryIsSpecializedWithoutChangingFullListApis() throws Exception {
        String jarPath = System.getProperty("ice.rltweaker.jar", "");
        Assume.assumeFalse("requires real RLTweaker jar", jarPath.isEmpty());
        byte[] original;
        try (JarFile jar = new JarFile(jarPath);
             InputStream input = jar.getInputStream(jar.getJarEntry(RltNearestEntityAdapter.TARGET + ".class"))) {
            original = bytes(input);
        }
        byte[] changed = new RltNearestEntityAdapter().transform(
            RltNearestEntityAdapter.TARGET.replace('/', '.'), original, null);
        verify(changed);
        ClassNode before = node(original), after = node(changed);
        assertEquals(1, calls(method(after, RltNearestEntityAdapter.DESC), RltNearestEntityAdapter.BRIDGE, "nearest"));
        for (int i = 0; i < before.methods.size(); i++) {
            MethodNode previous = before.methods.get(i), current = after.methods.get(i);
            if (!previous.name.equals("findNearestEntityWithinAABB"))
                assertEquals(previous.instructions.size(), current.instructions.size());
        }
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream input = EntityQueryOptimizationAdapterTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(name, input);
            return bytes(input);
        }
    }
    private static byte[] bytes(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toByteArray();
    }
    private static ClassNode node(byte[] bytes) {
        ClassNode result = new ClassNode(Opcodes.ASM5);
        new ClassReader(bytes).accept(result, 0);
        return result;
    }
    private static MethodNode method(ClassNode node, String descriptor) {
        for (MethodNode method : node.methods) {
            if (descriptor.equals(method.desc) && (method.name.equals("setLightLevel")
                || method.name.equals("func_149715_a") || !descriptor.equals("(F)Lnet/minecraft/block/Block;"))) return method;
        }
        throw new AssertionError(descriptor);
    }
    private static int calls(MethodNode method, String owner, String name) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (owner.equals(call.owner) && name.equals(call.name)) result++;
            }
        }
        return result;
    }
    private static void verify(byte[] bytes) throws Exception {
        ClassNode node = node(bytes);
        for (MethodNode method : node.methods) if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0)
            new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
    }
}
