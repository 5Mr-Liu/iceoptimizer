package dev.rlcraft.ice.optimizer.render.terrain;

import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyModelLightingState;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.resource.*;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.nio.*;
import java.util.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Standalone Pbuffer comparison. The baseline class is compiled from the saved
 * unmodified renderer with only its class name changed to LegacyTerrainBatchRenderer.
 * Timings use uninstrumented classes; the optional count pass uses scratch ASM copies. */
public final class TerrainScopeNativeProbe {
    private static final int SIZE = 64, PAGES = 12, RUNS = 120;
    private static final FrameStamp STAMP = new FrameStamp(1, 1, new ClientEpochs().snapshot());
    private static final int[] buffers = new int[PAGES];
    private static int originalVao, originalArray, originalIndirect;
    public static final Map<String, Long> calls = new TreeMap<String, Long>();
    public static void count(String key) { calls.put(key, calls.containsKey(key) ? calls.get(key) + 1L : 1L); }
    public static void noop() { }

    public static void main(String[] args) throws Throwable {
        Pbuffer context = new Pbuffer(SIZE, SIZE, new PixelFormat(), null, null);
        try {
            context.makeCurrent();
            originalVao = GL30.glGenVertexArrays();
            originalArray = GL15.glGenBuffers();
            originalIndirect = GL15.glGenBuffers();
            for (int p = 0; p < PAGES; p++) {
                buffers[p] = GL15.glGenBuffers();
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffers[p]);
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices(p), GL15.GL_STATIC_DRAW);
            }
            restore();
            GL11.glViewport(0, 0, SIZE, SIZE);
            GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glLoadIdentity();
            GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glLoadIdentity();
            GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_LIGHTING); GL11.glDisable(GL11.GL_FOG);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            if (GLContext.getCapabilities().GL_NV_fog_distance) GL11.glFogi(0x855A, 0x855B);
            Engine old = new Engine("LegacyTerrainBatchRenderer", false);
            Engine now = new Engine("LwjglTerrainBatchRenderer", true);
            try {
                old.prepare(); now.prepare();
                for (int gap : new int[] {0, 8, 1}) {
                    clear(); old.draw(gap); byte[] expected = pixels();
                    clear(); now.draw(gap); byte[] actual = pixels();
                    if (!Arrays.equals(expected, actual)) throw new AssertionError("mixed page pixels gap=" + gap);
                    requireBindings();
                    System.out.println("pixel_exact=true pages=" + PAGES + " runs=" + RUNS + " commands=300 legacy_gap=" + gap);
                    if (Boolean.getBoolean("ice.countGl")) {
                        calls.clear(); old.draw(gap); System.out.println("baseline_calls gap=" + gap + " " + calls);
                        calls.clear(); now.draw(gap); System.out.println("scoped_calls gap=" + gap + " " + calls);
                    }
                }
                // Growing the command stream must rebuild layouts which captured its old buffer.
                now.prepare(1200); clear(); now.draw(8); byte[] grown = pixels();
                clear(); old.draw(8);
                if (!Arrays.equals(grown, pixels())) throw new AssertionError("stream growth page layouts");
                System.out.println("stream_growth_layouts=true");
                if (!Boolean.getBoolean("ice.countGl")) {
                    GL11.glEnable(GL11.GL_SCISSOR_TEST); GL11.glScissor(0, 0, 1, 1);
                    for (int gap : new int[] {0, 8, 1}) {
                        for (int i = 0; i < 96; i++) { old.draw(gap); now.draw(gap); }
                        double[] baseline = new double[7], scoped = new double[7];
                        for (int round = 0; round < baseline.length; round++) {
                            if ((round & 1) == 0) {
                                baseline[round] = time(old, gap); scoped[round] = time(now, gap);
                            } else { scoped[round] = time(now, gap); baseline[round] = time(old, gap); }
                        }
                        Arrays.sort(baseline); Arrays.sort(scoped);
                        System.out.println("submission_glfinish_us_per_layer gap=" + gap + " baseline="
                            + Arrays.toString(baseline) + " scoped=" + Arrays.toString(scoped)
                            + " median_ratio=" + (scoped[3] / baseline[3]));
                    }
                    GL11.glDisable(GL11.GL_SCISSOR_TEST);
                }
            } finally { old.close(); now.close(); }
            budgetRetries();
            if (GL11.glGetError() != GL11.GL_NO_ERROR) throw new AssertionError("GL error");
            System.out.println("renderer=" + GL11.glGetString(GL11.GL_RENDERER));
        } finally { context.destroy(); }
    }

    private static void budgetRetries() {
        // Opaque program/shader/VAO lifetimes no longer consume invented payload
        // bytes; the actual command stream is still a retryable budget request.
        for (long available : new long[] {0}) {
            CacheBudget budget = new CacheBudget(1 << 20, 1 << 20, 1 << 20);
            ResourceLedger ledger = ledger(budget);
            LwjglTerrainBatchRenderer renderer = new LwjglTerrainBatchRenderer(ledger, budget);
            CacheBudget.Reservation blocker = budget.tryReserve(BudgetKind.GPU, (1 << 20) - available);
            try {
                seed();
                if (renderer.prepare(600, STAMP, null, originalArray, false) || renderer.isDisabled()
                    || renderer.consumeFailure() != null) throw new AssertionError("budget miss poisoned renderer " + available);
                if (!"stream-budget".equals(renderer.detail())) throw new AssertionError("wrong budget stage " + renderer.detail());
                if (budget.snapshot().getDirectUsed() != 0) throw new AssertionError("direct reservation leak");
                blocker.close();
                if (!renderer.prepare(300, STAMP, null, originalArray, false))
                    throw new AssertionError("budget retry failed: " + renderer.detail(), renderer.consumeFailure());
                CacheBudget.Reservation fill = budget.tryReserve(BudgetKind.GPU,
                    (1 << 20) - budget.snapshot().getGpuUsed());
                LwjglTerrainBatchRenderer.Page page = renderer.page(buffers[0]);
                if (!renderer.preparePage(page) || renderer.isDisabled()) throw new AssertionError("full payload pool blocked opaque layout");
                fill.close();
                if (!renderer.preparePage(page)) throw new AssertionError("layout retry");
                renderer.offset(0, 0, 0, 0); renderer.command(4, 0, 0); renderer.upload();
                renderer.drawScoped(page, 0, 1); renderer.endNativeRun(); requireBindings();
            } finally {
                blocker.close(); renderer.close(true); GL11.glFinish(); ledger.destroyAll(1);
            }
            if (budget.snapshot().getGpuUsed() != 0 || budget.snapshot().getDirectUsed() != 0)
                throw new AssertionError("budget cleanup leak");
        }
        System.out.println("opaque_objects_at_full_payload_budget=true stream_budget_retry=true reservation_cleanup=true");
    }

    private static double time(Engine engine, int gap) throws Throwable {
        GL11.glFinish(); long start = System.nanoTime();
        for (int i = 0; i < 128; i++) engine.draw(gap);
        GL11.glFinish(); return (System.nanoTime() - start) / 128000.0;
    }
    private static ResourceLedger ledger(CacheBudget budget) {
        return new ResourceLedger(RenderThreadGuard.captureCurrent(), budget, (kind, id) -> {
            if (kind == RenderResourceKind.BUFFER) GL15.glDeleteBuffers(id);
            else if (kind == RenderResourceKind.VERTEX_ARRAY) GL30.glDeleteVertexArrays(id);
            else if (kind == RenderResourceKind.PROGRAM) GL20.glDeleteProgram(id);
        }, 128);
    }
    private static MethodHandle method(Class<?> type, Object receiver, String name, Class<?>... args) throws Exception {
        Method method = type.getDeclaredMethod(name, args); method.setAccessible(true);
        return MethodHandles.lookup().unreflect(method).bindTo(receiver);
    }
    private static final class Engine {
        final ResourceLedger ledger;
        final Object renderer;
        final Class<?> type;
        final boolean scoped;
        final MethodHandle[] draw = new MethodHandle[PAGES];
        final MethodHandle end, close;
        Engine(String name, boolean scoped) throws Throwable {
            this.scoped = scoped;
            CacheBudget budget = new CacheBudget(8 << 20, 8 << 20, 8 << 20);
            ledger = ledger(budget);
            type = Class.forName("dev.rlcraft.ice.optimizer.render.terrain." + name);
            Constructor<?> ctor = type.getDeclaredConstructor(ResourceLedger.class, CacheBudget.class);
            ctor.setAccessible(true); renderer = ctor.newInstance(ledger, budget);
            close = method(type, renderer, "close", boolean.class);
            end = scoped ? method(type, renderer, "endNativeRun")
                : MethodHandles.lookup().findStatic(TerrainScopeNativeProbe.class, "noop", MethodType.methodType(void.class));
        }
        void prepare() throws Throwable { prepare(300); }
        void prepare(int capacity) throws Throwable {
            restore(); seed();
            boolean ready = (boolean) method(type, renderer, "prepare", int.class, FrameStamp.class,
                EarlyGlStateTracker.CompatibilitySnapshot.class, int.class, boolean.class)
                .invoke(capacity, STAMP, null, originalArray, false);
            if (!ready) throw new AssertionError("prepare " + type.getSimpleName());
            MethodHandle offset = method(type, renderer, "offset", int.class, float.class, float.class, float.class);
            MethodHandle command = method(type, renderer, "command", int.class, int.class, int.class);
            int mesh = 0;
            for (int run = 0; run < RUNS; run++) for (int n = 0; n < 2 + (run & 1); n++) {
                offset.invokeExact(mesh, (run % 9 - 4) * .12F, (run % 7 - 3) * .12F, 0F);
                command.invokeExact(4, n * 4, mesh); mesh++;
            }
            method(type, renderer, "upload").invokeExact();
            for (int p = 0; p < PAGES; p++) {
                if (scoped) {
                    Object page = method(type, renderer, "page", int.class).invoke(buffers[p]);
                    if (!(boolean) method(type, renderer, "preparePage", page.getClass()).invoke(page))
                        throw new AssertionError("page layout");
                    draw[p] = method(type, renderer, "drawScoped", page.getClass(), int.class, int.class).bindTo(page);
                } else draw[p] = MethodHandles.insertArguments(method(type, renderer, "draw", int.class, int.class, int.class), 0, buffers[p]);
            }
        }
        void draw(int gap) throws Throwable {
            int start = 0;
            try {
                for (int run = 0; run < RUNS; run++) {
                    int count = 2 + (run & 1);
                    draw[run % PAGES].invokeExact(start, count); start += count;
                    if (gap != 0 && (run + 1) % gap == 0) { end.invokeExact(); legacy(run); }
                }
            } finally { end.invokeExact(); }
        }
        void close() throws Throwable { close.invokeExact(true); GL11.glFinish(); ledger.destroyAll(1); }
    }
    private static void legacy(int run) {
        GL11.glColor4f(.1F, .8F, .3F, .35F);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex3f(-.6F, -.6F, 0); GL11.glVertex3f(.6F, -.6F, 0);
        GL11.glVertex3f(.6F, .6F, 0); GL11.glVertex3f(-.6F, .6F, 0);
        GL11.glEnd();
    }
    private static ByteBuffer vertices(int page) {
        ByteBuffer data = BufferUtils.createByteBuffer(3 * 112);
        for (int quad = 0; quad < 3; quad++) for (int v = 0; v < 4; v++) {
            data.putFloat(v == 1 || v == 2 ? .34F : -.34F).putFloat(v >= 2 ? .34F : -.34F).putFloat(0);
            data.put((byte)(30 + page * 18)).put((byte)(225 - page * 15)).put((byte)(50 + quad * 70)).put((byte)55);
            data.putFloat(0).putFloat(0).putShort((short)0).putShort((short)0);
        }
        data.flip(); return data;
    }
    private static void seed() {
        EarlyGlStateTracker.invalidate();
        EarlyModelLightingState.seed(new int[EarlyModelLightingState.SIZE], new int[8], 0);
    }
    private static void clear() { GL11.glClearColor(0,0,0,0); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT); }
    private static void restore() {
        GL20.glUseProgram(0); GL30.glBindVertexArray(originalVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, originalArray);
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, originalIndirect);
    }
    private static void requireBindings() {
        if (GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) != 0
            || GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING) != originalVao
            || GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING) != originalArray
            || GL11.glGetInteger(GL40.GL_DRAW_INDIRECT_BUFFER_BINDING) != originalIndirect)
            throw new AssertionError("mixed native state restore");
    }
    private static byte[] pixels() {
        ByteBuffer data = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        GL11.glReadPixels(0,0,SIZE,SIZE,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,data);
        byte[] result = new byte[data.remaining()]; data.get(result); return result;
    }
}
