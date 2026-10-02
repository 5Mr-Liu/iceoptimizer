package dev.rlcraft.ice.optimizer.render.particle;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import org.junit.Test;

public final class LwjglFbpCubeRendererTest {
    @Test public void acceptsReviewed31ByteNormalLayoutButNotBlockOrItem() {
        assertTrue(LwjglFbpCubeRenderer.isExactFormat(format()));
        assertFalse(LwjglFbpCubeRenderer.isExactFormat(DefaultVertexFormats.BLOCK));
        assertFalse(LwjglFbpCubeRenderer.isExactFormat(DefaultVertexFormats.ITEM));
    }

    @Test public void seesSameSizePublicFormatMutationImmediately() {
        VertexFormat format = format();
        assertTrue(LwjglFbpCubeRenderer.isExactFormat(format));
        format.getElements().set(4, new VertexFormatElement(0,
            VertexFormatElement.EnumType.UBYTE, VertexFormatElement.EnumUsage.NORMAL, 3));
        assertFalse(LwjglFbpCubeRenderer.isExactFormat(format));
    }

    @Test public void retainsCallbacksForCustomFormatOrElementSubclasses() {
        VertexFormat custom = new VertexFormat(format()) { };
        assertFalse(LwjglFbpCubeRenderer.isExactFormat(custom));
        VertexFormat format = format();
        format.getElements().set(4, new VertexFormatElement(0,
            VertexFormatElement.EnumType.BYTE, VertexFormatElement.EnumUsage.NORMAL, 3) { });
        assertFalse(LwjglFbpCubeRenderer.isExactFormat(format));
    }

    private static VertexFormat format() {
        return new VertexFormat().addElement(DefaultVertexFormats.POSITION_3F)
            .addElement(DefaultVertexFormats.TEX_2F).addElement(DefaultVertexFormats.COLOR_4UB)
            .addElement(DefaultVertexFormats.TEX_2S).addElement(DefaultVertexFormats.NORMAL_3B);
    }
}
