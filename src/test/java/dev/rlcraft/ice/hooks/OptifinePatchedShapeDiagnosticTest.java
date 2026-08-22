package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/** Assertive end-to-end regression for the reviewed OptiFine G5 patch shapes. */
public final class OptifinePatchedShapeDiagnosticTest {
    @Test
    public void reviewedPostPatchShapesPassTheirProductionAdapters() throws Exception {
        OptifinePatchedClassSupport support = OptifinePatchedClassSupport.openOrSkip();
        try {
            byte[] container = adapt(new ModernTerrainAdapter(
                    ModernTerrainAdapter.Part.CONTAINER_ACCESS),
                ModernTerrainAdapter.CONTAINER,
                support.patchAndRemap("bun",
                    "net.minecraft.client.renderer.ChunkRenderContainer"));
            assertShape(container, ModernTerrainAdapter.CONTAINER,
                ModernTerrainAdapter.ACCESS);

            byte[] global = adapt(new PrimitiveTerrainVisibilityAdapter(
                    PrimitiveTerrainVisibilityAdapter.Part.RENDER_GLOBAL),
                PrimitiveTerrainVisibilityAdapter.RENDER_GLOBAL,
                support.patchAndRemap("buy",
                    "net.minecraft.client.renderer.RenderGlobal"));
            assertShape(global, PrimitiveTerrainVisibilityAdapter.RENDER_GLOBAL,
                PrimitiveTerrainVisibilityAdapter.GLOBAL_ACCESS);

            byte[] info = adapt(new PrimitiveTerrainVisibilityAdapter(
                    PrimitiveTerrainVisibilityAdapter.Part.RENDER_INFO),
                PrimitiveTerrainVisibilityAdapter.RENDER_INFO,
                support.patchAndRemap("buy$a",
                    "net.minecraft.client.renderer.RenderGlobal$ContainerLocalRenderInformation"));
            assertShape(info, PrimitiveTerrainVisibilityAdapter.RENDER_INFO,
                PrimitiveTerrainVisibilityAdapter.INFO_ACCESS);

            byte[] sprite = adapt(new AnimatedTextureAdapter(
                    AnimatedTextureAdapter.Part.SPRITE),
                AnimatedTextureAdapter.SPRITE,
                support.patchAndRemap("cdq",
                    "net.minecraft.client.renderer.texture.TextureAtlasSprite"));
            assertShape(sprite, AnimatedTextureAdapter.SPRITE, null);
        } finally {
            support.close();
        }
    }

    private static byte[] adapt(OptimizerBytecodeAdapter adapter, String className,
                                byte[] original) throws Exception {
        return adapter.transform(className, original,
            new TargetSpec(className, "optifine-g5-regression", "test",
                Collections.<String>emptySet()));
    }

    private static void assertShape(byte[] bytes, String name, String expectedInterface) {
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        assertEquals(name, node.name);
        if (expectedInterface != null) assertTrue(node.interfaces.contains(expectedInterface));
    }
}
