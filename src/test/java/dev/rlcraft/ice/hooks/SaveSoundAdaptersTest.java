package dev.rlcraft.ice.hooks;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

public class SaveSoundAdaptersTest {
    @Test public void actualMinecraftInstallsOwnedBucketsAndKeepsBoundsRemoval() throws Exception {
        ClassNode node = transform("ice.minecraft.srg.jar", MinecraftSaveTickAdapter.WORLD_TARGET, new MinecraftSaveTickAdapter());
        assertEquals(1, calls(node, "<init>", MinecraftSaveTickAdapter.TREE, "<init>"));
        assertEquals(1, calls(node, "<init>", MinecraftSaveTickAdapter.LIST, "<init>"));
        assertEquals(1, calls(node, "ice$originalPendingBlockUpdates", MinecraftSaveTickAdapter.WORLD_TARGET, "func_175712_a"));
        assertEquals(0, calls(node, null, MinecraftSaveTickAdapter.BRIDGE, "begin"));
    }
    @Test public void actualAutosaveLeavesExplicitServerCallsAndUnloadWriters() throws Exception {
        ClassNode server = transform("ice.minecraft.srg.jar", MinecraftAutosaveAdapter.SERVER, new MinecraftAutosaveAdapter());
        assertEquals(1, calls(server, "func_71217_p", MinecraftAutosaveAdapter.BRIDGE, "automaticSave"));
        assertEquals(1, calls(server, "func_71260_j", MinecraftAutosaveAdapter.SERVER, "func_71267_a"));
        assertEquals(1, calls(server, "func_71267_a", MinecraftAutosaveAdapter.BRIDGE, "takeAutomatic"));
        assertEquals(1, calls(server, "func_71267_a", MinecraftAutosaveAdapter.BRIDGE, "saveWorld"));
        ClassNode world = transform("ice.minecraft.srg.jar", MinecraftAutosaveAdapter.WORLD, new MinecraftAutosaveAdapter());
        assertEquals(1, calls(world, "func_73044_a", MinecraftAutosaveAdapter.BRIDGE, "takeAutomatic"));
        assertEquals(1, calls(world, "func_73044_a", MinecraftAutosaveAdapter.BRIDGE, "saveWorldChunks"));
        assertEquals(1, calls(world, "func_73044_a", "net/minecraftforge/fml/common/eventhandler/EventBus", "post"));
        ClassNode provider = transform("ice.minecraft.srg.jar", MinecraftAutosaveAdapter.PROVIDER, new MinecraftAutosaveAdapter());
        assertEquals(1, calls(provider, "func_73156_b", MinecraftAutosaveAdapter.BRIDGE, "tick"));
        assertEquals(1, calls(provider, "func_104112_b", MinecraftAutosaveAdapter.BRIDGE, "finish"));
        assertEquals(1, calls(provider, "func_73156_b", MinecraftAutosaveAdapter.PROVIDER, "func_73242_b"));
        assertEquals(1, calls(provider, "func_73156_b", MinecraftAutosaveAdapter.PROVIDER, "func_73243_a"));
        assertEquals(1, calls(provider, "ice$originalSaveChunks", MinecraftAutosaveAdapter.PROVIDER, "func_73242_b"));
    }
    @Test public void skullItemRedirectsThePreRendererLookup() throws Exception {
        ClassNode node = transform("ice.minecraft.srg.jar", "net/minecraft/client/renderer/tileentity/TileEntityItemStackRenderer", new PlayerSkullItemAdapter());
        assertEquals(1, calls(node, "func_192838_a", "dev/rlcraft/ice/optimizer/compat/skull/SkullProfileBridge", "resolveForRenderLookup"));
        assertEquals(0, calls(node, "func_192838_a", "net/minecraft/tileentity/TileEntitySkull", "func_174884_b"));
    }
    @Test public void actualSoundFiltersUsesFrontierAndPreservesParameterCommit() throws Exception {
        String root = "com/tmtravlr/soundfilters/";
        ClassNode node = transform("ice.soundfilters.jar", root + "SoundTickHandler", new SoundFiltersSearchAdapter());
        assertEquals(6, calls(node, "ice$indexedSoundTick", "dev/rlcraft/ice/optimizer/compat/sound/SoundSearchBridge$Frontier", "contains"));
        assertEquals(6, calls(node, "ice$originalSoundTick", "java/util/LinkedList", "contains"));
        transform("ice.soundfilters.jar", root + "SoundTickHandler$ComparablePosition", new SoundFiltersSearchAdapter());
        for (String type : new String[] {"FilterLowPass", "FilterReverb"}) {
            ClassNode filter = transform("ice.soundfilters.jar", root + "filters/" + type, new SoundFilterParametersAdapter());
            assertEquals(1, calls(filter, "loadParameters", SoundFilterParametersAdapter.BRIDGE, "begin"));
            assertTrue(calls(filter, "loadFilter", SoundFilterParametersAdapter.BRIDGE, "reset") >= 1);
            if (type.equals("FilterReverb")) assertEquals(1, calls(filter, "loadParameters", SoundFilterParametersAdapter.BRIDGE, "alAuxiliaryEffectSloti"));
        }
    }
    private static ClassNode transform(String property, String owner, OptimizerBytecodeAdapter adapter) throws Exception {
        String path = System.getProperty(property, ""); Assume.assumeFalse("configure " + property, path.isEmpty());
        byte[] bytes;
        try (JarFile jar = new JarFile(path); InputStream in = jar.getInputStream(jar.getJarEntry(owner + ".class"))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) != -1;) out.write(buffer, 0, n); bytes = out.toByteArray();
        }
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(adapter.transform(owner.replace('/', '.'), bytes, null)).accept(node, ClassReader.EXPAND_FRAMES);
        for (MethodNode method : node.methods) if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0)
            new Analyzer(new BasicVerifier()).analyze(node.name, method);
        return node;
    }
    private static int calls(ClassNode node, String methodName, String owner, String name) {
        int n = 0;
        for (MethodNode method : node.methods) if (methodName == null || methodName.equals(method.name))
            for (AbstractInsnNode in : method.instructions.toArray()) if (in instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) in; if (owner.equals(call.owner) && name.equals(call.name)) n++;
            }
        return n;
    }
}
