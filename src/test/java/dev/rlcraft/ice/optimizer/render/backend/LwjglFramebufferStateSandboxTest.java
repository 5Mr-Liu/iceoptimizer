package dev.rlcraft.ice.optimizer.render.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.FatalErrors;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

public final class LwjglFramebufferStateSandboxTest {
    @Test
    public void lwjglStateQueriesAlwaysProvideTheGeneratedWrapperMinimum() {
        assertEquals(16,
            LwjglFramebufferStateSandbox.stateQueryBufferCapacity(1));
        assertEquals(16,
            LwjglFramebufferStateSandbox.stateQueryBufferCapacity(4));
        assertEquals(16,
            LwjglFramebufferStateSandbox.stateQueryBufferCapacity(16));
        assertEquals(24,
            LwjglFramebufferStateSandbox.stateQueryBufferCapacity(24));
    }

    @Test
    public void pollutedStateIsNormalizedAndRestoredExactly() {
        FakeAccess access = FakeAccess.polluted();
        LwjglFramebufferStateSandbox.Snapshot snapshot =
            LwjglFramebufferStateSandbox.capture(access);
        String before = snapshot.describe();

        LwjglFramebufferStateSandbox.establishKnownState(access, 4, 4);
        assertEquals(GL13.GL_TEXTURE0, access.activeTexture);
        assertEquals(0, access.renderbuffer);
        assertEquals(0, access.packPbo);
        assertEquals(0, access.unpackPbo);
        assertFalse(access.enabled(GL11.GL_SCISSOR_TEST));
        assertFalse(access.enabled(GL30.GL_FRAMEBUFFER_SRGB));
        assertEquals(Arrays.asList(true, true, true, true),
            booleans(access.colorMask));
        assertEquals(1, access.integer(GL11.GL_PACK_ALIGNMENT));
        assertEquals(1, access.integer(GL11.GL_UNPACK_ALIGNMENT));
        assertEquals(0, access.integer(GL11.GL_PACK_ROW_LENGTH));
        assertEquals(0, access.integer(GL11.GL_UNPACK_ROW_LENGTH));

        access.bindTexture2d(991);
        access.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 992);
        access.bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 993);
        LwjglFramebufferStateSandbox.selectColorAttachment(access);
        access.clearColor(1.0F, 0.0F, 0.0F, 1.0F);

        LwjglFramebufferStateSandbox.RestoreResult restored =
            LwjglFramebufferStateSandbox.restore(access, snapshot, null);
        assertNull(restored.getFailure());
        assertEquals("", restored.getFailedStages());
        assertEquals(before,
            LwjglFramebufferStateSandbox.capture(access).describe());
    }

    @Test
    public void restoreFailureIsReportedButLaterFieldsStillRestore() {
        FakeAccess access = FakeAccess.polluted();
        LwjglFramebufferStateSandbox.Snapshot snapshot =
            LwjglFramebufferStateSandbox.capture(access);
        LwjglFramebufferStateSandbox.establishKnownState(access, 4, 4);
        access.failOperation = "viewport";

        LwjglFramebufferStateSandbox.RestoreResult restored =
            LwjglFramebufferStateSandbox.restore(access, snapshot, null);

        assertNotNull(restored.getFailure());
        assertTrue(restored.getFailedStages().contains("restore.viewport"));
        assertEquals(GL13.GL_TEXTURE7, access.activeTexture);
        assertTrue(access.enabled(GL11.GL_SCISSOR_TEST));
        assertTrue(access.enabled(GL30.GL_FRAMEBUFFER_SRGB));
        assertEquals(31, access.renderbuffer);
    }

    @Test
    public void defaultFramebufferRestoresLegacyDrawBufferWithSingleTargetCall() {
        FakeAccess access = FakeAccess.polluted();
        access.drawFramebuffer = 0;
        access.readFramebuffer = 0;
        access.drawBuffers = new int[] {
            GL11.GL_BACK, GL11.GL_NONE, GL11.GL_NONE, GL11.GL_NONE
        };
        LwjglFramebufferStateSandbox.Snapshot snapshot =
            LwjglFramebufferStateSandbox.capture(access);
        LwjglFramebufferStateSandbox.establishKnownState(access, 4, 4);
        access.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 99);

        LwjglFramebufferStateSandbox.RestoreResult restored =
            LwjglFramebufferStateSandbox.restore(access, snapshot, null);

        assertNull(restored.getFailure());
        assertEquals(1, access.singleDrawBufferCalls);
        assertEquals(0, access.multipleDrawBufferCalls);
        assertEquals(GL11.GL_BACK, access.drawBuffers[0]);
    }

    @Test
    public void fatalRestoreFailureBecomesPrimaryWithoutSkippingRemainingRestores() {
        FakeAccess access = FakeAccess.polluted();
        LwjglFramebufferStateSandbox.Snapshot snapshot =
            LwjglFramebufferStateSandbox.capture(access);
        LwjglFramebufferStateSandbox.establishKnownState(access, 4, 4);
        access.failOperation = "renderbuffer-fatal";

        LwjglFramebufferStateSandbox.RestoreResult restored =
            LwjglFramebufferStateSandbox.restore(access, snapshot,
                new IllegalStateException("primary"));

        assertTrue(FatalErrors.findFatal(restored.getFailure()) instanceof ThreadDeath);
        assertTrue(restored.getFailedStages().contains("restore.renderbuffer"));
        assertEquals(GL13.GL_TEXTURE7, access.activeTexture);
        assertTrue(access.enabled(GL30.GL_FRAMEBUFFER_SRGB));
    }

    @Test(expected = IllegalStateException.class)
    public void captureRejectsDrawBufferCountsItCannotRestoreExactly() {
        FakeAccess access = FakeAccess.polluted();
        access.drawBuffers = new int[33];
        LwjglFramebufferStateSandbox.capture(access);
    }

    private static java.util.List<Boolean> booleans(boolean[] values) {
        java.util.ArrayList<Boolean> result = new java.util.ArrayList<Boolean>();
        for (boolean value : values) result.add(value);
        return result;
    }

    private static final class FakeAccess implements LwjglFramebufferStateSandbox.Access {
        private final Map<Integer, Integer> integers = new HashMap<Integer, Integer>();
        private final Map<Integer, Boolean> booleans = new HashMap<Integer, Boolean>();
        private final Map<Integer, Integer> textures = new HashMap<Integer, Integer>();
        private int drawFramebuffer;
        private int readFramebuffer;
        private int renderbuffer;
        private int activeTexture;
        private int packPbo;
        private int unpackPbo;
        private int[] drawBuffers = new int[] { GL11.GL_BACK };
        private int readBuffer;
        private int[] viewport = new int[4];
        private int[] scissor = new int[4];
        private boolean[] colorMask = new boolean[4];
        private float[] clearColor = new float[4];
        private boolean depthMask;
        private int stencilFrontMask;
        private int stencilBackMask;
        private int singleDrawBufferCalls;
        private int multipleDrawBufferCalls;
        private String failOperation;

        static FakeAccess polluted() {
            FakeAccess result = new FakeAccess();
            result.drawFramebuffer = 17;
            result.readFramebuffer = 19;
            result.renderbuffer = 31;
            result.activeTexture = GL13.GL_TEXTURE7;
            result.textures.put(GL13.GL_TEXTURE0, 41);
            result.textures.put(GL13.GL_TEXTURE7, 43);
            result.packPbo = 47;
            result.unpackPbo = 53;
            result.drawBuffers = new int[] {
                GL30.GL_COLOR_ATTACHMENT0 + 3,
                GL30.GL_COLOR_ATTACHMENT0 + 1,
                GL11.GL_NONE,
                GL30.GL_COLOR_ATTACHMENT0
            };
            result.readBuffer = GL11.GL_BACK;
            result.viewport = new int[] { 3, 5, 1280, 720 };
            result.scissor = new int[] { 11, 13, 640, 360 };
            result.colorMask = new boolean[] { false, true, false, true };
            result.clearColor = new float[] { 0.1F, 0.2F, 0.3F, 0.4F };
            result.depthMask = false;
            result.stencilFrontMask = 0x0F0F0F0F;
            result.stencilBackMask = 0x00FF00FF;
            result.booleans.put(GL11.GL_SCISSOR_TEST, true);
            result.booleans.put(GL30.GL_FRAMEBUFFER_SRGB, true);
            result.putPixelStore(true, true, true, 71, 73, 79, 8, 83, 89);
            result.putPixelStore(false, true, false, 97, 101, 103, 2, 107, 109);
            return result;
        }

        private void putPixelStore(boolean pack, boolean swap, boolean lsb,
                                   int row, int rows, int pixels, int alignment,
                                   int imageHeight, int images) {
            booleans.put(pack ? GL11.GL_PACK_SWAP_BYTES : GL11.GL_UNPACK_SWAP_BYTES, swap);
            booleans.put(pack ? GL11.GL_PACK_LSB_FIRST : GL11.GL_UNPACK_LSB_FIRST, lsb);
            integers.put(pack ? GL11.GL_PACK_ROW_LENGTH : GL11.GL_UNPACK_ROW_LENGTH, row);
            integers.put(pack ? GL11.GL_PACK_SKIP_ROWS : GL11.GL_UNPACK_SKIP_ROWS, rows);
            integers.put(pack ? GL11.GL_PACK_SKIP_PIXELS : GL11.GL_UNPACK_SKIP_PIXELS, pixels);
            integers.put(pack ? GL11.GL_PACK_ALIGNMENT : GL11.GL_UNPACK_ALIGNMENT, alignment);
            integers.put(pack ? GL12.GL_PACK_IMAGE_HEIGHT : GL12.GL_UNPACK_IMAGE_HEIGHT,
                imageHeight);
            integers.put(pack ? GL12.GL_PACK_SKIP_IMAGES : GL12.GL_UNPACK_SKIP_IMAGES, images);
        }

        int integer(int name) { return getInteger(name); }
        boolean enabled(int name) { return isEnabled(name); }

        @Override public int getInteger(int name) {
            if (name == GL30.GL_DRAW_FRAMEBUFFER_BINDING) return drawFramebuffer;
            if (name == GL30.GL_READ_FRAMEBUFFER_BINDING) return readFramebuffer;
            if (name == GL30.GL_RENDERBUFFER_BINDING) return renderbuffer;
            if (name == GL13.GL_ACTIVE_TEXTURE) return activeTexture;
            if (name == GL11.GL_TEXTURE_BINDING_2D) {
                Integer value = textures.get(activeTexture);
                return value == null ? 0 : value;
            }
            if (name == LwjglFramebufferStateSandbox.GL_PIXEL_PACK_BUFFER_BINDING) return packPbo;
            if (name == LwjglFramebufferStateSandbox.GL_PIXEL_UNPACK_BUFFER_BINDING) return unpackPbo;
            if (name == GL11.GL_STENCIL_WRITEMASK) return stencilFrontMask;
            if (name == GL20.GL_STENCIL_BACK_WRITEMASK) return stencilBackMask;
            if (name == GL20.GL_MAX_DRAW_BUFFERS) return drawBuffers.length;
            if (name >= GL20.GL_DRAW_BUFFER0
                && name < GL20.GL_DRAW_BUFFER0 + drawBuffers.length) {
                return drawBuffers[name - GL20.GL_DRAW_BUFFER0];
            }
            if (name == GL11.GL_READ_BUFFER) return readBuffer;
            Integer value = integers.get(name);
            return value == null ? 0 : value;
        }

        @Override public boolean getBoolean(int name) {
            if (name == GL11.GL_DEPTH_WRITEMASK) return depthMask;
            Boolean value = booleans.get(name);
            return value != null && value;
        }

        @Override public void getIntegers(int name, int[] values) {
            int[] source = name == GL11.GL_VIEWPORT ? viewport : scissor;
            System.arraycopy(source, 0, values, 0, values.length);
        }

        @Override public void getBooleans(int name, boolean[] values) {
            System.arraycopy(colorMask, 0, values, 0, values.length);
        }

        @Override public void getFloats(int name, float[] values) {
            System.arraycopy(clearColor, 0, values, 0, values.length);
        }

        @Override public boolean isEnabled(int capability) {
            Boolean value = booleans.get(capability);
            return value != null && value;
        }

        @Override public void setEnabled(int capability, boolean enabled) {
            booleans.put(capability, enabled);
        }

        @Override public void bindFramebuffer(int target, int framebuffer) {
            if (target == GL30.GL_DRAW_FRAMEBUFFER) drawFramebuffer = framebuffer;
            else if (target == GL30.GL_READ_FRAMEBUFFER) readFramebuffer = framebuffer;
            else {
                drawFramebuffer = framebuffer;
                readFramebuffer = framebuffer;
            }
        }

        @Override public void bindRenderbuffer(int value) {
            if ("renderbuffer-fatal".equals(failOperation)) {
                failOperation = null;
                throw new ThreadDeath();
            }
            renderbuffer = value;
        }

        @Override public void activeTexture(int textureUnit) { activeTexture = textureUnit; }
        @Override public void bindTexture2d(int texture) { textures.put(activeTexture, texture); }

        @Override public void viewport(int x, int y, int width, int height) {
            if ("viewport".equals(failOperation)) {
                failOperation = null;
                throw new IllegalStateException("viewport restore failed");
            }
            viewport = new int[] { x, y, width, height };
        }

        @Override public void scissor(int x, int y, int width, int height) {
            scissor = new int[] { x, y, width, height };
        }

        @Override public void colorMask(boolean red, boolean green,
                                        boolean blue, boolean alpha) {
            colorMask = new boolean[] { red, green, blue, alpha };
        }

        @Override public void depthMask(boolean enabled) { depthMask = enabled; }

        @Override public void stencilMaskSeparate(int face, int mask) {
            if (face == GL11.GL_FRONT) stencilFrontMask = mask;
            else stencilBackMask = mask;
        }

        @Override public void drawBuffer(int value) {
            singleDrawBufferCalls++;
            drawBuffers[0] = value;
            for (int i = 1; i < drawBuffers.length; i++) {
                drawBuffers[i] = GL11.GL_NONE;
            }
        }

        @Override public void drawBuffers(int[] values) {
            multipleDrawBufferCalls++;
            if (drawFramebuffer == 0) {
                for (int value : values) {
                    if (value == GL11.GL_BACK || value == GL11.GL_FRONT) {
                        throw new IllegalArgumentException(
                            "legacy default target passed to glDrawBuffers");
                    }
                }
            }
            drawBuffers = values.clone();
        }
        @Override public void readBuffer(int value) { readBuffer = value; }

        @Override public void bindBuffer(int target, int buffer) {
            if (target == LwjglFramebufferStateSandbox.GL_PIXEL_PACK_BUFFER) packPbo = buffer;
            else unpackPbo = buffer;
        }

        @Override public void pixelStore(int name, int value) {
            if (name == GL11.GL_PACK_SWAP_BYTES || name == GL11.GL_UNPACK_SWAP_BYTES
                || name == GL11.GL_PACK_LSB_FIRST || name == GL11.GL_UNPACK_LSB_FIRST) {
                booleans.put(name, value != 0);
            } else integers.put(name, value);
        }

        @Override public void clearColor(float red, float green,
                                         float blue, float alpha) {
            clearColor = new float[] { red, green, blue, alpha };
        }
    }
}
