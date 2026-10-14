package dev.rlcraft.ice.optimizer.client;

import dev.rlcraft.ice.hooks.HudRenderBootstrap;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.compat.hud.HudRenderBridge;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.hud.*;
import dev.rlcraft.ice.optimizer.render.resource.*;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.*;
import java.nio.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import sun.misc.Unsafe;

/** Real FontRenderer traversal, resource glyphs, and native output. Not a game FPS test. */
public final class HudFontNativeProbe {
    private static final int W = 640, H = 128;
    private static final String[] TEXTS = {"20", "Health 20 Armor 12 Experience 1234",
        "\u4e00\u4e01\u4e03\u4e07\u4e08\u4e09\u4e0a\u4e0b\u4e2d\u4e3b\u4e0a\u4e00",
        "\u751f\u547d\u503c: 20  \u62a4\u7532: 12  \u7ecf\u9a8c\u7b49\u7ea7: 30"};
    private static Object bridgeState, scope;
    private static Field modern, failed;
    private static boolean active;
    private static LwjglHudRenderer hud;
    private static FontLayoutCache cache;
    private static FontRenderer font;
    private static volatile int sink;

    public static void main(String[] args) throws Exception {
        active = !args[0].equals("original");
        Pbuffer context = new Pbuffer(W, H, new PixelFormat(), null, null);
        try (ZipFile resources = new ZipFile(args[1])) {
            context.makeCurrent();
            PageTextures textures = new PageTextures(resources);
            Unsafe unsafe = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
            font = (FontRenderer) unsafe.allocateInstance(FontRenderer.class);
            field(FontRenderer.class, "renderEngine").set(font, textures);
            field(FontRenderer.class, "locationFontTexture").set(font, new ResourceLocation("textures/font/ascii.png"));
            field(FontRenderer.class, "fontRandom").set(font, new Random(7));
            field(FontRenderer.class, "FONT_HEIGHT").setInt(font, 9);
            field(FontRenderer.class, "colorCode").set(font, new int[32]);
            byte[] widths = new byte[65536];
            try (DataInputStream stream = new DataInputStream(resources.getInputStream(resources.getEntry("assets/minecraft/font/glyph_sizes.bin")))) {
                stream.readFully(widths);
            }
            field(FontRenderer.class, "glyphWidth").set(font, widths);
            int[] ascii = new int[256];
            BufferedImage atlas = textures.image(new ResourceLocation("textures/font/ascii.png"));
            int tile = atlas.getWidth() / 16;
            for (int ch = 0; ch < 256; ch++) {
                int right;
                outer: for (right = tile - 1; right >= 0; right--) {
                    for (int y = 0; y < tile; y++)
                        if ((atlas.getRGB(ch % 16 * tile + right, ch / 16 * tile + y) >>> 24) != 0) break outer;
                }
                ascii[ch] = (int) (.5 + (right + 1) * (8F / tile)) + 1;
            }
            field(FontRenderer.class, "charWidth").set(font, ascii);
            ResourceLedger ledger = null;
            if (active) {
                RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
                CacheBudget budget = new CacheBudget(4 << 20, 4 << 20, 4 << 20);
                ledger = new ResourceLedger(guard, budget, new LwjglResourceDestroyer(), 128);
                ClientEpochs epochs = new ClientEpochs();
                ModernRendererRuntime runtime = new ModernRendererRuntime(epochs, budget);
                runtime.components.threadGuard = guard;
                hud = new LwjglHudRenderer(guard, ledger, budget, 1024); hud.prepare(1, 1);
                cache = new FontLayoutCache(128, 8192, budget);
                runtime.components.hudRenderer = hud; runtime.components.fonts = cache;
                field(runtime.getClass(), "initialized").setBoolean(runtime, true);
                field(runtime.getClass(), "currentStamp").set(runtime, new FrameStamp(1, 1, epochs.snapshot()));
                bridgeState = ((ThreadLocal<?>) field(HudRenderBridge.class, "STATE").get(null)).get();
                scope = ((Object[]) field(bridgeState.getClass(), "hudScopes").get(bridgeState))[0];
                Method reset = scope.getClass().getDeclaredMethod("reset", long.class); reset.setAccessible(true); reset.invoke(scope, 1L);
                field(scope.getClass(), "runtime").set(scope, runtime);
                field(scope.getClass(), "renderer").set(scope, hud);
                field(scope.getClass(), "resourceGeneration").setLong(scope, 1);
                field(scope.getClass(), "contextGeneration").setLong(scope, 1);
                field(bridgeState.getClass(), "hudDepth").setInt(bridgeState, 1);
                modern = field(scope.getClass(), "modern"); failed = field(scope.getClass(), "failed");
                require(HudRenderBootstrap.install(HudRenderBridge.class), "bootstrap install");
            }
            // Compare output across original, frozen, and new JVMs. Repeated
            // text moves to fractional positions and alternates font mode.
            for (int t = 0; t < TEXTS.length; t++) {
                for (boolean unicode : new boolean[] {false, true, false}) {
                    font.setUnicodeFlag(unicode);
                    for (boolean shadow : new boolean[] {false, true}) {
                        for (int sample = 0; sample < 3; sample++) {
                            setup();
                            draw(TEXTS[t], 5.25F + sample * 6.375F, 8.5F + sample * 2.125F, shadow);
                            System.out.println("pixels," + t + ',' + unicode + ',' + shadow + ',' + sample + ',' + hash());
                        }
                    }
                }
            }
            for (int t = 0; t < TEXTS.length; t++) {
                font.setUnicodeFlag(false);
                long[] cpu = new long[9], wall = new long[9];
                for (int round = -5; round < 9; round++) {
                    setup(); GL11.glFinish(); long begin = System.nanoTime();
                    for (int repeat = 0; repeat < 1024; repeat++) draw(TEXTS[t], 5F + (repeat & 7), 8F, true);
                    long elapsed = System.nanoTime() - begin;
                    GL11.glFinish(); long finished = System.nanoTime() - begin;
                    if (round >= 0) { cpu[round] = elapsed; wall[round] = finished; }
                }
                Arrays.sort(cpu); Arrays.sort(wall);
                System.out.printf(Locale.ROOT, "cost,%d,%.1f,%.1f%n", t, cpu[4] / 1024.0, wall[4] / 1024.0);
            }
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "GL error");
            System.out.println("PASS arm=" + args[0] + " cache_hits=" + (cache == null ? 0 : cache.getHits())
                + " cache_misses=" + (cache == null ? 0 : cache.getMisses())
                + " renderer=" + GL11.glGetString(GL11.GL_RENDERER));
            if (active) { hud.close(true); cache.invalidate(); ledger.destroyAll(1); }
            textures.close();
        } finally { context.destroy(); }
    }
    private static void draw(String text, float x, float y, boolean shadow) throws Exception {
        if (active) { modern.setBoolean(scope, true); failed.setBoolean(scope, false); }
        sink = font.drawString(text, x, y, 0xb0b0c0f0, shadow);
        if (active) {
            HudRenderBridge.barrier();
            require(!failed.getBoolean(scope), "HUD cache/stream quarantined");
        }
    }
    private static void setup() {
        GL20.glUseProgram(0); GL30.glBindVertexArray(0); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL11.glViewport(0, 0, W, H); GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glLoadIdentity();
        GL11.glOrtho(0, W, H, 0, -1, 1); GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glLoadIdentity();
        GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE); GL11.glDisable(GL11.GL_FOG);
        GlStateManager.enableAlpha(); GlStateManager.alphaFunc(GL11.GL_GREATER, .01F);
        GL11.glEnable(GL11.GL_BLEND); GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL13.glActiveTexture(GL13.GL_TEXTURE0); GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
        GL11.glEnable(GL11.GL_TEXTURE_2D); GL11.glClearColor(.15F, .2F, .25F, .3F); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        EarlyGlStateTracker.beginProbe(); EarlyGlStateTracker.useProgram(0);
        EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER, 0); EarlyGlStateTracker.bindVertexArray(0);
        EarlyGlStateTracker.activeTexture(GL13.GL_TEXTURE0); EarlyGlStateTracker.clientActiveTexture(GL13.GL_TEXTURE0);
        EarlyGlStateTracker.bindTexture(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D));
        EarlyGlStateTracker.texture2dEnabled(true); EarlyGlStateTracker.viewport(0, 0, W, H);
    }
    private static String hash() throws Exception {
        ByteBuffer bytes = BufferUtils.createByteBuffer(W * H * 4);
        GL11.glReadPixels(0, 0, W, H, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, bytes);
        byte[] data = new byte[bytes.remaining()]; bytes.get(data);
        StringBuilder value = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) value.append(String.format("%02x", b & 255));
        return value.toString();
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static final class PageTextures extends TextureManager {
        private final ZipFile zip;
        private final Map<ResourceLocation, Integer> textures = new HashMap<>();
        PageTextures(ZipFile zip) { super(null); this.zip = zip; }
        BufferedImage image(ResourceLocation location) throws IOException {
            ZipEntry entry = zip.getEntry("assets/" + location.getNamespace() + '/' + location.getPath());
            try (InputStream stream = zip.getInputStream(entry)) { return ImageIO.read(stream); }
        }
        @Override public void bindTexture(ResourceLocation location) {
            Integer id = textures.get(location);
            if (id == null) {
                try {
                    BufferedImage image = image(location);
                    ByteBuffer pixels = BufferUtils.createByteBuffer(image.getWidth() * image.getHeight() * 4);
                    for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
                        int p = image.getRGB(x, y); pixels.put((byte)(p >> 16)).put((byte)(p >> 8)).put((byte)p).put((byte)(p >> 24));
                    }
                    pixels.flip(); id = GL11.glGenTextures(); GlStateManager.bindTexture(id);
                    GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, image.getWidth(), image.getHeight(), 0,
                        GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                    textures.put(location, id);
                } catch (IOException error) { throw new IllegalStateException(error); }
            }
            GlStateManager.bindTexture(id);
        }
        void close() { for (int id : textures.values()) GL11.glDeleteTextures(id); }
    }
}
