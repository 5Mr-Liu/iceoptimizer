package dev.rlcraft.ice.hooks;

import dev.rlcraft.ice.optimizer.client.ModernRendererRuntime;
import dev.rlcraft.ice.optimizer.compat.foamfix.FoamFixUploadBridge;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.compat.texture.AnimatedTextureUploadBridge;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.backend.*;
import dev.rlcraft.ice.optimizer.render.resource.*;
import dev.rlcraft.ice.optimizer.render.texture.*;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import sun.misc.Unsafe;

/** Real FoamFix helper -> transformed G5 TextureUtil -> production capture/stream.
 * Only TextureUtil's game/settings dependencies use the existing GL fixture.
 * Native helper bindings are redirected in this test process, never production.
 */
public final class FoamFixAtlasStreamNativeProbe {
    public static void main(String[] args) throws Exception {
        byte[] original = Files.readAllBytes(Paths.get(args[2]));
        byte[] patched = new VanillaTextureUploadAdapter().transform(VanillaTextureUploadAdapter.TARGET.replace('/', '.'), original,
            new TargetSpec(VanillaTextureUploadAdapter.TARGET.replace('/', '.'), "foamfix-texture-upload", "vanilla-texture-parameters", Collections.<String>emptySet()));
        Pbuffer context = new Pbuffer(16, 16, new PixelFormat(), null, null);
        try {
            context.makeCurrent();
            TextureParameterScopeProbe.Fixture baseline = new TextureParameterScopeProbe.Fixture(args[0], args[1], original, TextureParameterScopeProbe.names(args[3]));
            TextureParameterScopeProbe.Fixture actual = new TextureParameterScopeProbe.Fixture(args[0], args[1], patched, TextureParameterScopeProbe.names(args[3]));
            try {
                redirectExactHelpers(actual.level.getDeclaringClass());
                require(TextureUploadBootstrap.install(FoamFixUploadBridge.class), "pixel bridge");
                require(AnimatedTextureBootstrap.install(AnimatedTextureUploadBridge.class), "atlas bridge");
                for (int maxMip : new int[]{0, 1, 2}) for (boolean persistent : new boolean[]{false, true}) {
                    byte[][] reference = run(baseline, false, maxMip, persistent, 256);
                    byte[][] pixels = run(actual, true, maxMip, persistent, 256);
                    require(Arrays.deepEquals(reference, pixels), "pixels maxMip=" + maxMip + " persistent=" + persistent);
                }
                require(Arrays.deepEquals(run(baseline, false, 0, false, 2), run(actual, true, 0, false, 2)), "small legacy batch");
                require(Arrays.deepEquals(run(baseline, false, 1, false, 3), run(actual, true, 1, false, 3)), "captured mip barrier");
                require(GL11.glGetError() == 0, "GL errors");
                System.out.println("actual_foamfix_g5_capture=true mip_pixels_exact=true max_mips_and_filter_exact=true mutable_arrays_snapshotted=true streaming_and_persistent=true small_batch_replay=true");
            } finally { baseline.close(); actual.close(); }
        } finally { context.destroy(); }
    }

    private static byte[][] run(TextureParameterScopeProbe.Fixture fixture, boolean optimized, int maxMip,
                                boolean persistent, int count) throws Exception {
        CacheBudget budget = new CacheBudget(16L << 20, 16L << 20, 16L << 20);
        RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        ResourceLedger ledger = new ResourceLedger(guard, budget, (kind,id) -> { if (kind == RenderResourceKind.BUFFER) GL15.glDeleteBuffers(id); }, 64);
        LwjglAnimatedTextureUploadStream stream = new LwjglAnimatedTextureUploadStream(guard, ledger, budget,
            CapabilityReport.builder().pass(ModernCapability.PIXEL_UNPACK_BUFFER).pass(ModernCapability.SYNC_FENCE).pass(ModernCapability.PERSISTENT_MAPPING).build());
        ClientEpochs epochs = new ClientEpochs(); epochs.nextFrame();
        Constructor<ModernRendererRuntime> ctor = ModernRendererRuntime.class.getDeclaredConstructor(ClientEpochs.class, CacheBudget.class);
        ctor.setAccessible(true); ModernRendererRuntime runtime = ctor.newInstance(epochs, budget);
        int texture = GL11.glGenTextures(); GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        for (int mip = 0; mip < 3; mip++) GL11.glTexImage2D(GL11.GL_TEXTURE_2D, mip, GL11.GL_RGBA8,
            128 >> mip, 128 >> mip, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, BufferUtils.createByteBuffer((128 >> mip) * (128 >> mip) * 4));
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 2);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0); EarlyGlStateTracker.bindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        TextureParameterScopeProbe.SettingsFixture settings = new TextureParameterScopeProbe.SettingsFixture();
        settings.ofMipmapType = maxMip; fixture.config.getField("gameSettings").set(null, settings);
        long token = optimized ? AnimatedTextureBootstrap.begin(null) : 0;
        if (optimized) {
            Object state = ((ThreadLocal<?>) getStatic(AnimatedTextureUploadBridge.class, "STATE")).get();
            Object scope = ((Object[]) get(state, "scopes"))[0];
            set(scope, "uploader", stream); set(scope, "runtime", runtime); set(scope, "modern", true); set(scope, "persistent", persistent);
            set(scope, "resourceGeneration", epochs.currentResourceGeneration()); set(scope, "contextGeneration", epochs.currentGlContextGeneration());
        }
        int[][] mutable = {new int[64], new int[16], new int[4]};
        try {
            for (int i = 0; i < count; i++) {
                for (int mip = 0; mip < 3; mip++) Arrays.fill(mutable[mip], 0xff000000 | (i * 1913 + mip * 174763) & 0xffffff);
                if (optimized) AnimatedTextureBootstrap.beforeSprite(fixture.sprite);
                fixture.helper.invoke(null, maxMip, mutable, 8, 8, (i & 15) * 8, (i >> 4) * 8, (i & 1) != 0, (i & 2) != 0, true);
                if (optimized && count == 3 && i == count - 1) AnimatedTextureUploadBridge.textureBarrier();
                if (optimized) AnimatedTextureBootstrap.afterSprite();
            }
            for (int[] level : mutable) Arrays.fill(level, 0);
            if (optimized) {
                AnimatedTextureBootstrap.end(token);
                require(stream.getLastCommandCount() == count, "not one command per sprite");
                if (count == 256) {
                    require(stream.getLastError() == null && stream.getLastBytes() >= 65536, "batch failure");
                    int fences = 0;
                    for (Object slot : (Object[]) get(stream, persistent ? "persistent" : "streaming"))
                        if (get(slot, "fence") != null) fences++;
                    require(fences == 1, "expected one batch fence, got " + fences);
                }
            }
            byte[][] pixels = new byte[4][];
            for (int mip = 0; mip < 3; mip++) {
                ByteBuffer data = BufferUtils.createByteBuffer((128 >> mip) * (128 >> mip) * 4);
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D, mip, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, data);
                pixels[mip] = new byte[data.remaining()]; data.get(pixels[mip]);
            }
            ByteBuffer parameters = ByteBuffer.allocate(16);
            for (int name : new int[]{GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_TEXTURE_WRAP_S, GL11.GL_TEXTURE_WRAP_T}) parameters.putInt(GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, name));
            pixels[3] = parameters.array();
            return pixels;
        } finally {
            stream.close(true, epochs.currentGlContextGeneration()); GL11.glFinish(); ledger.destroyAll(epochs.currentGlContextGeneration());
            GL11.glDeleteTextures(texture);
            require(budget.snapshot().getHeapUsed() == 0 && budget.snapshot().getGpuUsed() == 0 && budget.snapshot().getDirectUsed() == 0, "budget leak");
        }
    }

    private static void redirectExactHelpers(Class<?> util) throws Exception {
        Class<?> bindings = Class.forName(ExactTextureUpload.class.getName() + "$Bindings");
        Field singleton = Unsafe.class.getDeclaredField("theUnsafe"); singleton.setAccessible(true); Unsafe unsafe = (Unsafe) singleton.get(null);
        for (Method method : util.getDeclaredMethods()) {
            String field = method.getParameterCount() == 9 ? "LEVEL" : method.getParameterCount() == 2 && method.getParameterTypes()[0] == boolean.class ? "FILTER" : method.getName().equals("func_110997_a") ? "WRAP" : null;
            if (field == null) continue;
            method.setAccessible(true); Field target = bindings.getDeclaredField(field);
            unsafe.putObject(unsafe.staticFieldBase(target), unsafe.staticFieldOffset(target), MethodHandles.lookup().unreflect(method));
        }
    }
    static Object getStatic(Class<?> type, String name) throws Exception { Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(null); }
    static Object get(Object owner, String name) throws Exception { Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner); }
    static void set(Object owner, String name, Object value) throws Exception { Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value); }
    static void require(boolean valid, String message) { if(!valid) throw new AssertionError(message); }
}
