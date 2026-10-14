package dev.rlcraft.ice.hooks;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.spongepowered.asm.mixin.transformer.ClassInfo;
import org.spongepowered.asm.mixin.transformer.ext.Extensions;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;

public final class SrpWorldOptimizationAdapterTest {
    @Test public void realMobCapHandlerKeepsOriginalBranchesAndWritesWithOneSharedTraversal() throws Exception {
        byte[] original = read("srpmixins/handlers/WorldMobCapHandler");
        byte[] changed = new SrpMobCapAdapter().transform("srpmixins.handlers.WorldMobCapHandler", original, null);
        verify(changed);
        ClassNode before = node(original);
        ClassNode after = node(changed);
        MethodNode oldTick = find(before, "onWorldServerTick");
        MethodNode newTick = find(after, "onWorldServerTick");
        assertEquals(7, calls(oldTick, "net/minecraft/world/World", "func_72907_a"));
        assertEquals(0, calls(newTick, "net/minecraft/world/World", "func_72907_a"));
        assertEquals(1, calls(newTick, SrpMobCapAdapter.BRIDGE, "begin"));
        assertEquals(7, calls(newTick, SrpMobCapAdapter.BRIDGE, "count"));
        int categoryIndex = 0;
        for (AbstractInsnNode instruction : newTick.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (SrpMobCapAdapter.BRIDGE.equals(call.owner) && "count".equals(call.name)) {
                assertEquals("(Lnet/minecraft/world/World;Ljava/lang/Class;Ljava/lang/Object;I)I", call.desc);
                assertEquals(categoryIndex++, ((IntInsnNode) call.getPrevious()).operand);
            }
        }
        assertEquals(7, categoryIndex);
        assertEquals(calls(oldTick, "java/util/Map", "put"), calls(newTick, "java/util/Map", "put"));
        assertEquals(jumps(oldTick), jumps(newTick));
        assertEquals(calls(oldTick, "srpmixins/compat/SRPExtraCompat", "countWaterParasites"),
            calls(newTick, "srpmixins/compat/SRPExtraCompat", "countWaterParasites"));
    }

    @Test public void mergedRealInfestationHelpersKeepWorldChecksAndWhitelistBranches() throws Exception {
        ClassNode merged = new ClassNode(Opcodes.ASM5);
        merged.version = Opcodes.V1_8;
        merged.access = Opcodes.ACC_PUBLIC;
        merged.name = SrpInfestationCacheAdapter.TARGET;
        merged.superName = "java/lang/Object";
        ClassNode infestation = node(read("srpmixins/mixin/deterrenttweaks/InfestationOverhaul"));
        ClassNode biome = node(read("srpmixins/mixin/parabiome/BiomeSpreadOverhaul"));
        merged.methods.add(find(infestation, "srpmixins$blockIsBlacklistedForInfestation"));
        merged.methods.add(find(biome, "srpmixins$blockIsBlacklistedForBiome"));
        // Dispatch through Mixin's real extension registry, after merged helpers exist.
        Extensions extensions = new Extensions(null);
        extensions.add(new SrpPostMixinExtension());
        extensions.select(null);
        extensions.postApply(new ITargetClassContext() {
            @Override public ClassInfo getClassInfo() { return null; }
            @Override public ClassNode getClassNode() { return merged; }
        });
        for (MethodNode method : merged.methods)
            assertEquals(1, calls(method, SrpInfestationCacheAdapter.BRIDGE, "matches"));
        // A second postApply does not wrap or duplicate the installed call sites.
        extensions.postApply(new ITargetClassContext() {
            @Override public ClassInfo getClassInfo() { return null; }
            @Override public ClassNode getClassNode() { return merged; }
        });
        ClassWriter writer = new ClassWriter(0);
        merged.accept(writer);
        byte[] changed = writer.toByteArray();
        verify(changed);
        ClassNode result = node(changed);
        for (MethodNode method : result.methods) {
            MethodNode old = method.name.contains("ForInfestation")
                ? find(node(read("srpmixins/mixin/deterrenttweaks/InfestationOverhaul")), method.name)
                : find(node(read("srpmixins/mixin/parabiome/BiomeSpreadOverhaul")), method.name);
            assertEquals(1, calls(method, SrpInfestationCacheAdapter.BRIDGE, "matches"));
            assertEquals(0, calls(method, "java/util/stream/Stream", "anyMatch"));
            assertEquals(jumps(old), jumps(method));
            assertEquals(fields(old), fields(method));
            assertEquals(calls(old, "java/util/Set", "add"), calls(method, "java/util/Set", "add"));
            assertEquals(calls(old, "net/minecraft/block/state/IBlockState", "func_185887_b"),
                calls(method, "net/minecraft/block/state/IBlockState", "func_185887_b"));
        }
    }

    @Test public void colonyHelperCloneHasValidFramesAndRetainsOriginalLazyBranches() throws Exception {
        byte[] original = read("srpmixins/handlers/SpawnPotentialsHandler");
        byte[] changed = new SrpSpawnFilterAdapter().transform("srpmixins.handlers.SpawnPotentialsHandler", original, null);
        verify(changed);
        ClassNode result = node(changed);
        MethodNode originalHelper = find(result, "isColonyLocked");
        MethodNode cachedHelper = find(result, "ice$isColonyLocked");
        assertEquals(jumps(originalHelper), jumps(cachedHelper));
        assertEquals(fields(originalHelper), fields(cachedHelper));
        assertEquals(1, calls(cachedHelper, SrpSpawnFilterAdapter.BRIDGE, "colonyPoints"));
        assertEquals(0, calls(cachedHelper, SrpSpawnFilterAdapter.WORLD_DATA, "totalColonyPoints"));
        assertEquals(1, calls(originalHelper, SrpSpawnFilterAdapter.WORLD_DATA, "totalColonyPoints"));
    }

    private static byte[] read(String name) throws Exception {
        String configured = System.getProperty("ice.srp.mixins.jar", "").trim();
        Assume.assumeTrue("requires reviewed SRPMixins jar", !configured.isEmpty());
        try (JarFile jar = new JarFile(new File(configured));
             InputStream input = jar.getInputStream(jar.getJarEntry(name + ".class"))) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode result = new ClassNode(Opcodes.ASM5);
        new ClassReader(bytes).accept(result, ClassReader.EXPAND_FRAMES);
        return result;
    }
    private static MethodNode find(ClassNode node, String name) {
        for (MethodNode method : node.methods) if (method.name.equals(name)) return method;
        throw new AssertionError(name);
    }
    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode && ((MethodInsnNode) instruction).owner.equals(owner)
                && ((MethodInsnNode) instruction).name.equals(name)) count++;
        }
        return count;
    }
    private static int jumps(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof JumpInsnNode) count++;
        return count;
    }
    private static int fields(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof FieldInsnNode) count++;
        return count;
    }
    private static void verify(byte[] bytes) throws Exception {
        ClassNode node = node(bytes);
        for (MethodNode method : node.methods)
            new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
    }
}
