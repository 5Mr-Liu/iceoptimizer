package dev.rlcraft.ice.optimizer.render.terrain;

import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.util.BlockRenderLayer;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativeTerrainSectionTest {
    private static class Compiled extends CompiledChunk {
        Compiled(BlockRenderLayer... layers) { for (BlockRenderLayer layer : layers) setLayerUsed(layer); }
    }
    @Test public void publicationReplacesLayerMaskAndCancellationClearsIt() {
        NativeTerrainRenderer.Section section = new NativeTerrainRenderer.Section(null);
        section.publish(new Compiled(BlockRenderLayer.SOLID, BlockRenderLayer.TRANSLUCENT));
        assertEquals(9, section.activeMask);
        section.publish(new Compiled(BlockRenderLayer.CUTOUT_MIPPED));
        assertEquals(2, section.activeMask);
        section.publish(CompiledChunk.DUMMY);
        assertEquals(0, section.activeMask);
    }
    @Test public void replacedMeshCannotBeDetachedByAnOlderRelease() {
        NativeTerrainRenderer.Section section = new NativeTerrainRenderer.Section(null);
        NativeTerrainRenderer.Mesh first = mesh(section, 1), replacement = mesh(section, 1), transparent = mesh(section, 3);
        section.put(first); section.put(transparent); section.put(replacement);
        section.remove(first);
        assertSame(replacement, section.layers[1]); assertEquals(10, section.residentMask);
        section.publish(new Compiled(BlockRenderLayer.TRANSLUCENT));
        assertEquals(8, section.activeMask); assertEquals(10, section.residentMask);
        section.remove(replacement); assertEquals(8, section.residentMask);
        section.remove(transparent); assertEquals(0, section.residentMask);
    }
    static NativeTerrainRenderer.Mesh mesh(NativeTerrainRenderer.Section section, int layer) {
        try {
            java.lang.reflect.Constructor<TerrainVertexSpace> constructor = TerrainVertexSpace.class
                .getDeclaredConstructor(int.class, int.class, int.class, boolean.class);
            constructor.setAccessible(true);
            NativeTerrainAllocator allocator = new NativeTerrainAllocator();
            return new NativeTerrainRenderer.Mesh(section, layer, allocator.allocate(4), allocator.allocate(4),
                4, constructor.newInstance(0, 0, 0, false), null);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
}
