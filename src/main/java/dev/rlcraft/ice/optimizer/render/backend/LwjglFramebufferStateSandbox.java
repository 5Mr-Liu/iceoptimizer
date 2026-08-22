package dev.rlcraft.ice.optimizer.render.backend;

import dev.rlcraft.ice.optimizer.FatalErrors;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** Explicit state sandbox for the executable offscreen-framebuffer probe. */
final class LwjglFramebufferStateSandbox {
    private static final int MAX_CAPTURED_DRAW_BUFFERS = 32;
    // LWJGL 2's generated glGetInteger(IntBuffer) wrapper performs a
    // conservative 16-element capacity check even for four-value state such
    // as GL_VIEWPORT and GL_SCISSOR_BOX.
    private static final int LWJGL_STATE_QUERY_MINIMUM = 16;
    static final int GL_PIXEL_PACK_BUFFER = 0x88EB;
    static final int GL_PIXEL_UNPACK_BUFFER = 0x88EC;
    static final int GL_PIXEL_PACK_BUFFER_BINDING = 0x88ED;
    static final int GL_PIXEL_UNPACK_BUFFER_BINDING = 0x88EF;

    private LwjglFramebufferStateSandbox() {
    }

    static Snapshot capture() {
        return capture(LwjglAccess.INSTANCE);
    }

    static Snapshot capture(Access access) {
        if (access == null) throw new IllegalArgumentException("access");
        int activeTexture = access.getInteger(GL13.GL_ACTIVE_TEXTURE);
        int texture2dUnit0;
        Throwable captureFailure = null;
        try {
            access.activeTexture(GL13.GL_TEXTURE0);
            texture2dUnit0 = access.getInteger(GL11.GL_TEXTURE_BINDING_2D);
        } catch (Throwable error) {
            captureFailure = error;
            texture2dUnit0 = 0;
        } finally {
            try {
                access.activeTexture(activeTexture);
            } catch (Throwable error) {
                captureFailure = append(captureFailure, error);
            }
        }
        if (captureFailure != null) throw rethrow(captureFailure);

        int[] viewport = new int[4];
        int[] scissorBox = new int[4];
        boolean[] colorMask = new boolean[4];
        float[] clearColor = new float[4];
        access.getIntegers(GL11.GL_VIEWPORT, viewport);
        access.getIntegers(GL11.GL_SCISSOR_BOX, scissorBox);
        access.getBooleans(GL11.GL_COLOR_WRITEMASK, colorMask);
        access.getFloats(GL11.GL_COLOR_CLEAR_VALUE, clearColor);
        int maximumDrawBuffers = access.getInteger(GL20.GL_MAX_DRAW_BUFFERS);
        if (maximumDrawBuffers < 1
            || maximumDrawBuffers > MAX_CAPTURED_DRAW_BUFFERS) {
            throw new IllegalStateException(
                "framebuffer draw-buffer count outside sandbox limit: "
                    + maximumDrawBuffers);
        }
        int[] drawBuffers = new int[maximumDrawBuffers];
        for (int i = 0; i < drawBuffers.length; i++) {
            drawBuffers[i] = access.getInteger(GL20.GL_DRAW_BUFFER0 + i);
        }
        return new Snapshot(
            access.getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),
            access.getInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),
            access.getInteger(GL30.GL_RENDERBUFFER_BINDING),
            activeTexture, texture2dUnit0, viewport,
            access.isEnabled(GL11.GL_SCISSOR_TEST), scissorBox, colorMask,
            access.getBoolean(GL11.GL_DEPTH_WRITEMASK),
            access.getInteger(GL11.GL_STENCIL_WRITEMASK),
            access.getInteger(GL20.GL_STENCIL_BACK_WRITEMASK),
            drawBuffers,
            access.getInteger(GL11.GL_READ_BUFFER),
            access.getInteger(GL_PIXEL_PACK_BUFFER_BINDING),
            access.getInteger(GL_PIXEL_UNPACK_BUFFER_BINDING),
            PixelStore.capture(access, true), PixelStore.capture(access, false),
            clearColor, access.isEnabled(GL30.GL_FRAMEBUFFER_SRGB));
    }

    static void establishKnownState(int width, int height) {
        establishKnownState(LwjglAccess.INSTANCE, width, height);
    }

    static void establishKnownState(Access access, int width, int height) {
        if (access == null || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("framebuffer sandbox dimensions");
        }
        access.activeTexture(GL13.GL_TEXTURE0);
        access.bindRenderbuffer(0);
        access.bindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        access.bindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        access.setEnabled(GL11.GL_SCISSOR_TEST, false);
        access.setEnabled(GL30.GL_FRAMEBUFFER_SRGB, false);
        access.viewport(0, 0, width, height);
        access.scissor(0, 0, width, height);
        access.colorMask(true, true, true, true);
        access.depthMask(true);
        access.stencilMaskSeparate(GL11.GL_FRONT, -1);
        access.stencilMaskSeparate(GL11.GL_BACK, -1);
        PixelStore.known(access, true);
        PixelStore.known(access, false);
        access.clearColor(0.0F, 0.0F, 0.0F, 0.0F);
    }

    static void selectColorAttachment() {
        selectColorAttachment(LwjglAccess.INSTANCE);
    }

    static void selectColorAttachment(Access access) {
        access.drawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        access.readBuffer(GL30.GL_COLOR_ATTACHMENT0);
    }

    static RestoreResult restore(Snapshot snapshot, Throwable prior) {
        return restore(LwjglAccess.INSTANCE, snapshot, prior);
    }

    static RestoreResult restore(Access access, Snapshot snapshot,
                                 Throwable prior) {
        RestoreResult result = new RestoreResult(prior);
        if (access == null || snapshot == null) return result;
        attempt(result, "restore.texture_unit_0", new Action() {
            @Override public void run() {
                access.activeTexture(GL13.GL_TEXTURE0);
                access.bindTexture2d(snapshot.texture2dUnit0);
            }
        });
        attempt(result, "restore.pixel_pack_buffer", new Action() {
            @Override public void run() {
                access.bindBuffer(GL_PIXEL_PACK_BUFFER, snapshot.pixelPackBuffer);
            }
        });
        attempt(result, "restore.pixel_unpack_buffer", new Action() {
            @Override public void run() {
                access.bindBuffer(GL_PIXEL_UNPACK_BUFFER, snapshot.pixelUnpackBuffer);
            }
        });
        snapshot.pack.restore(access, true, result);
        snapshot.unpack.restore(access, false, result);
        attempt(result, "restore.draw_framebuffer", new Action() {
            @Override public void run() {
                access.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,
                    snapshot.drawFramebuffer);
                // GL_BACK/GL_FRONT are valid for glDrawBuffer on the default
                // framebuffer but are not valid entries for glDrawBuffers.
                // OptiFine FBOs still need their complete attachment vector.
                if (snapshot.drawFramebuffer == 0) {
                    access.drawBuffer(snapshot.drawBuffers[0]);
                } else {
                    access.drawBuffers(snapshot.drawBuffers);
                }
            }
        });
        attempt(result, "restore.read_framebuffer", new Action() {
            @Override public void run() {
                access.bindFramebuffer(GL30.GL_READ_FRAMEBUFFER,
                    snapshot.readFramebuffer);
                access.readBuffer(snapshot.readBuffer);
            }
        });
        attempt(result, "restore.renderbuffer", new Action() {
            @Override public void run() { access.bindRenderbuffer(snapshot.renderbuffer); }
        });
        attempt(result, "restore.viewport", new Action() {
            @Override public void run() {
                access.viewport(snapshot.viewport[0], snapshot.viewport[1],
                    snapshot.viewport[2], snapshot.viewport[3]);
            }
        });
        attempt(result, "restore.scissor_box", new Action() {
            @Override public void run() {
                access.scissor(snapshot.scissorBox[0], snapshot.scissorBox[1],
                    snapshot.scissorBox[2], snapshot.scissorBox[3]);
            }
        });
        attempt(result, "restore.scissor_enable", new Action() {
            @Override public void run() {
                access.setEnabled(GL11.GL_SCISSOR_TEST, snapshot.scissorEnabled);
            }
        });
        attempt(result, "restore.color_mask", new Action() {
            @Override public void run() {
                access.colorMask(snapshot.colorMask[0], snapshot.colorMask[1],
                    snapshot.colorMask[2], snapshot.colorMask[3]);
            }
        });
        attempt(result, "restore.depth_mask", new Action() {
            @Override public void run() { access.depthMask(snapshot.depthMask); }
        });
        attempt(result, "restore.stencil_front_mask", new Action() {
            @Override public void run() {
                access.stencilMaskSeparate(GL11.GL_FRONT, snapshot.stencilFrontMask);
            }
        });
        attempt(result, "restore.stencil_back_mask", new Action() {
            @Override public void run() {
                access.stencilMaskSeparate(GL11.GL_BACK, snapshot.stencilBackMask);
            }
        });
        attempt(result, "restore.clear_color", new Action() {
            @Override public void run() {
                access.clearColor(snapshot.clearColor[0], snapshot.clearColor[1],
                    snapshot.clearColor[2], snapshot.clearColor[3]);
            }
        });
        attempt(result, "restore.framebuffer_srgb", new Action() {
            @Override public void run() {
                access.setEnabled(GL30.GL_FRAMEBUFFER_SRGB,
                    snapshot.framebufferSrgbEnabled);
            }
        });
        attempt(result, "restore.active_texture", new Action() {
            @Override public void run() { access.activeTexture(snapshot.activeTexture); }
        });
        return result;
    }

    static final class Snapshot {
        private final int drawFramebuffer;
        private final int readFramebuffer;
        private final int renderbuffer;
        private final int activeTexture;
        private final int texture2dUnit0;
        private final int[] viewport;
        private final boolean scissorEnabled;
        private final int[] scissorBox;
        private final boolean[] colorMask;
        private final boolean depthMask;
        private final int stencilFrontMask;
        private final int stencilBackMask;
        private final int[] drawBuffers;
        private final int readBuffer;
        private final int pixelPackBuffer;
        private final int pixelUnpackBuffer;
        private final PixelStore pack;
        private final PixelStore unpack;
        private final float[] clearColor;
        private final boolean framebufferSrgbEnabled;

        private Snapshot(int drawFramebuffer, int readFramebuffer,
                         int renderbuffer, int activeTexture,
                         int texture2dUnit0, int[] viewport,
                         boolean scissorEnabled, int[] scissorBox,
                         boolean[] colorMask, boolean depthMask,
                         int stencilFrontMask, int stencilBackMask,
                         int[] drawBuffers, int readBuffer, int pixelPackBuffer,
                         int pixelUnpackBuffer, PixelStore pack,
                         PixelStore unpack, float[] clearColor,
                         boolean framebufferSrgbEnabled) {
            this.drawFramebuffer = drawFramebuffer;
            this.readFramebuffer = readFramebuffer;
            this.renderbuffer = renderbuffer;
            this.activeTexture = activeTexture;
            this.texture2dUnit0 = texture2dUnit0;
            this.viewport = viewport.clone();
            this.scissorEnabled = scissorEnabled;
            this.scissorBox = scissorBox.clone();
            this.colorMask = colorMask.clone();
            this.depthMask = depthMask;
            this.stencilFrontMask = stencilFrontMask;
            this.stencilBackMask = stencilBackMask;
            this.drawBuffers = drawBuffers.clone();
            this.readBuffer = readBuffer;
            this.pixelPackBuffer = pixelPackBuffer;
            this.pixelUnpackBuffer = pixelUnpackBuffer;
            this.pack = pack;
            this.unpack = unpack;
            this.clearColor = clearColor.clone();
            this.framebufferSrgbEnabled = framebufferSrgbEnabled;
        }

        String describe() {
            return "draw_fbo=" + drawFramebuffer
                + ",read_fbo=" + readFramebuffer
                + ",renderbuffer=" + renderbuffer
                + ",active_texture=" + hex(activeTexture)
                + ",texture_2d_unit0=" + texture2dUnit0
                + ",viewport=" + Arrays.toString(viewport)
                + ",scissor=" + scissorEnabled + Arrays.toString(scissorBox)
                + ",color_mask=" + Arrays.toString(colorMask)
                + ",depth_mask=" + depthMask
                + ",stencil_masks=[" + hex(stencilFrontMask) + ','
                + hex(stencilBackMask) + ']'
                + ",draw_buffers=" + hex(drawBuffers)
                + ",read_buffer=" + hex(readBuffer)
                + ",pack_pbo=" + pixelPackBuffer
                + ",unpack_pbo=" + pixelUnpackBuffer
                + ",pack={" + pack.describe() + "}"
                + ",unpack={" + unpack.describe() + "}"
                + ",clear_color=" + Arrays.toString(clearColor)
                + ",framebuffer_srgb=" + framebufferSrgbEnabled;
        }
    }

    static final class RestoreResult {
        private Throwable failure;
        private final StringBuilder stages = new StringBuilder();

        private RestoreResult(Throwable prior) { failure = prior; }

        Throwable getFailure() { return failure; }
        String getFailedStages() { return stages.toString(); }

        private void failed(String stage, Throwable error) {
            failure = append(failure, error);
            if (stages.length() > 0) stages.append(',');
            stages.append(stage);
        }
    }

    interface Access {
        int getInteger(int name);
        boolean getBoolean(int name);
        void getIntegers(int name, int[] values);
        void getBooleans(int name, boolean[] values);
        void getFloats(int name, float[] values);
        boolean isEnabled(int capability);
        void setEnabled(int capability, boolean enabled);
        void bindFramebuffer(int target, int framebuffer);
        void bindRenderbuffer(int renderbuffer);
        void activeTexture(int textureUnit);
        void bindTexture2d(int texture);
        void viewport(int x, int y, int width, int height);
        void scissor(int x, int y, int width, int height);
        void colorMask(boolean red, boolean green, boolean blue, boolean alpha);
        void depthMask(boolean enabled);
        void stencilMaskSeparate(int face, int mask);
        void drawBuffer(int buffer);
        void drawBuffers(int[] buffers);
        void readBuffer(int buffer);
        void bindBuffer(int target, int buffer);
        void pixelStore(int name, int value);
        void clearColor(float red, float green, float blue, float alpha);
    }

    private static final class PixelStore {
        private final boolean swapBytes;
        private final boolean lsbFirst;
        private final int rowLength;
        private final int skipRows;
        private final int skipPixels;
        private final int alignment;
        private final int imageHeight;
        private final int skipImages;

        private PixelStore(boolean swapBytes, boolean lsbFirst, int rowLength,
                           int skipRows, int skipPixels, int alignment,
                           int imageHeight, int skipImages) {
            this.swapBytes = swapBytes;
            this.lsbFirst = lsbFirst;
            this.rowLength = rowLength;
            this.skipRows = skipRows;
            this.skipPixels = skipPixels;
            this.alignment = alignment;
            this.imageHeight = imageHeight;
            this.skipImages = skipImages;
        }

        private static PixelStore capture(Access access, boolean pack) {
            return new PixelStore(
                access.getBoolean(pack ? GL11.GL_PACK_SWAP_BYTES : GL11.GL_UNPACK_SWAP_BYTES),
                access.getBoolean(pack ? GL11.GL_PACK_LSB_FIRST : GL11.GL_UNPACK_LSB_FIRST),
                access.getInteger(pack ? GL11.GL_PACK_ROW_LENGTH : GL11.GL_UNPACK_ROW_LENGTH),
                access.getInteger(pack ? GL11.GL_PACK_SKIP_ROWS : GL11.GL_UNPACK_SKIP_ROWS),
                access.getInteger(pack ? GL11.GL_PACK_SKIP_PIXELS : GL11.GL_UNPACK_SKIP_PIXELS),
                access.getInteger(pack ? GL11.GL_PACK_ALIGNMENT : GL11.GL_UNPACK_ALIGNMENT),
                access.getInteger(pack ? GL12.GL_PACK_IMAGE_HEIGHT : GL12.GL_UNPACK_IMAGE_HEIGHT),
                access.getInteger(pack ? GL12.GL_PACK_SKIP_IMAGES : GL12.GL_UNPACK_SKIP_IMAGES));
        }

        private static void known(Access access, boolean pack) {
            set(access, pack, false, false, 0, 0, 0, 1, 0, 0);
        }

        private void restore(final Access access, final boolean pack,
                             RestoreResult result) {
            attempt(result, pack ? "restore.pack_pixel_store"
                : "restore.unpack_pixel_store", new Action() {
                @Override public void run() {
                    set(access, pack, swapBytes, lsbFirst, rowLength, skipRows,
                        skipPixels, alignment, imageHeight, skipImages);
                }
            });
        }

        private static void set(Access access, boolean pack, boolean swapBytes,
                                boolean lsbFirst, int rowLength, int skipRows,
                                int skipPixels, int alignment, int imageHeight,
                                int skipImages) {
            access.pixelStore(pack ? GL11.GL_PACK_SWAP_BYTES : GL11.GL_UNPACK_SWAP_BYTES,
                swapBytes ? 1 : 0);
            access.pixelStore(pack ? GL11.GL_PACK_LSB_FIRST : GL11.GL_UNPACK_LSB_FIRST,
                lsbFirst ? 1 : 0);
            access.pixelStore(pack ? GL11.GL_PACK_ROW_LENGTH : GL11.GL_UNPACK_ROW_LENGTH,
                rowLength);
            access.pixelStore(pack ? GL11.GL_PACK_SKIP_ROWS : GL11.GL_UNPACK_SKIP_ROWS,
                skipRows);
            access.pixelStore(pack ? GL11.GL_PACK_SKIP_PIXELS : GL11.GL_UNPACK_SKIP_PIXELS,
                skipPixels);
            access.pixelStore(pack ? GL11.GL_PACK_ALIGNMENT : GL11.GL_UNPACK_ALIGNMENT,
                alignment);
            access.pixelStore(pack ? GL12.GL_PACK_IMAGE_HEIGHT : GL12.GL_UNPACK_IMAGE_HEIGHT,
                imageHeight);
            access.pixelStore(pack ? GL12.GL_PACK_SKIP_IMAGES : GL12.GL_UNPACK_SKIP_IMAGES,
                skipImages);
        }

        private String describe() {
            return "swap=" + swapBytes + ",lsb=" + lsbFirst
                + ",row=" + rowLength + ",skip_rows=" + skipRows
                + ",skip_pixels=" + skipPixels + ",alignment=" + alignment
                + ",image_height=" + imageHeight + ",skip_images=" + skipImages;
        }
    }

    private interface Action { void run() throws Throwable; }

    private static void attempt(RestoreResult result, String stage, Action action) {
        try {
            action.run();
        } catch (Throwable error) {
            result.failed(stage, error);
        }
    }

    private static Throwable append(Throwable first, Throwable next) {
        if (first == null) return next;
        Throwable nextFatal = FatalErrors.findFatal(next);
        if (nextFatal != null && FatalErrors.findFatal(first) == null) {
            if (nextFatal != first) nextFatal.addSuppressed(first);
            return nextFatal;
        }
        if (next != null && first != next) first.addSuppressed(next);
        return first;
    }

    private static RuntimeException rethrow(Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        if (error instanceof RuntimeException) return (RuntimeException) error;
        if (error instanceof Error) throw (Error) error;
        return new IllegalStateException("framebuffer state capture failed", error);
    }

    private static String hex(int value) {
        return "0x" + Integer.toHexString(value).toUpperCase(java.util.Locale.ROOT);
    }

    private static String hex(int[] values) {
        StringBuilder result = new StringBuilder(values.length * 11 + 2);
        result.append('[');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) result.append(',');
            result.append(hex(values[i]));
        }
        return result.append(']').toString();
    }

    static int stateQueryBufferCapacity(int requestedValues) {
        if (requestedValues <= 0) throw new IllegalArgumentException(
            "requestedValues");
        return Math.max(LWJGL_STATE_QUERY_MINIMUM, requestedValues);
    }

    private enum LwjglAccess implements Access {
        INSTANCE;

        @Override public int getInteger(int name) { return GL11.glGetInteger(name); }
        @Override public boolean getBoolean(int name) { return GL11.glGetBoolean(name); }
        @Override public void getIntegers(int name, int[] values) {
            IntBuffer buffer = BufferUtils.createIntBuffer(
                stateQueryBufferCapacity(values.length));
            GL11.glGetInteger(name, buffer);
            for (int i = 0; i < values.length; i++) values[i] = buffer.get(i);
        }
        @Override public void getBooleans(int name, boolean[] values) {
            ByteBuffer buffer = BufferUtils.createByteBuffer(
                stateQueryBufferCapacity(values.length));
            GL11.glGetBoolean(name, buffer);
            for (int i = 0; i < values.length; i++) values[i] = buffer.get(i) != 0;
        }
        @Override public void getFloats(int name, float[] values) {
            FloatBuffer buffer = BufferUtils.createFloatBuffer(
                stateQueryBufferCapacity(values.length));
            GL11.glGetFloat(name, buffer);
            for (int i = 0; i < values.length; i++) values[i] = buffer.get(i);
        }
        @Override public boolean isEnabled(int capability) {
            return GL11.glIsEnabled(capability);
        }
        @Override public void setEnabled(int capability, boolean enabled) {
            if (enabled) GL11.glEnable(capability);
            else GL11.glDisable(capability);
        }
        @Override public void bindFramebuffer(int target, int framebuffer) {
            GL30.glBindFramebuffer(target, framebuffer);
        }
        @Override public void bindRenderbuffer(int renderbuffer) {
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, renderbuffer);
        }
        @Override public void activeTexture(int textureUnit) {
            GL13.glActiveTexture(textureUnit);
        }
        @Override public void bindTexture2d(int texture) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        }
        @Override public void viewport(int x, int y, int width, int height) {
            GL11.glViewport(x, y, width, height);
        }
        @Override public void scissor(int x, int y, int width, int height) {
            GL11.glScissor(x, y, width, height);
        }
        @Override public void colorMask(boolean red, boolean green,
                                        boolean blue, boolean alpha) {
            GL11.glColorMask(red, green, blue, alpha);
        }
        @Override public void depthMask(boolean enabled) { GL11.glDepthMask(enabled); }
        @Override public void stencilMaskSeparate(int face, int mask) {
            GL20.glStencilMaskSeparate(face, mask);
        }
        @Override public void drawBuffer(int buffer) { GL11.glDrawBuffer(buffer); }
        @Override public void drawBuffers(int[] buffers) {
            IntBuffer values = BufferUtils.createIntBuffer(buffers.length);
            values.put(buffers).flip();
            GL20.glDrawBuffers(values);
        }
        @Override public void readBuffer(int buffer) { GL11.glReadBuffer(buffer); }
        @Override public void bindBuffer(int target, int buffer) {
            GL15.glBindBuffer(target, buffer);
        }
        @Override public void pixelStore(int name, int value) {
            GL11.glPixelStorei(name, value);
        }
        @Override public void clearColor(float red, float green,
                                         float blue, float alpha) {
            GL11.glClearColor(red, green, blue, alpha);
        }
    }
}
