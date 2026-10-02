import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyMatrixStateTracker;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.entity.LwjglModelMeshCache;
import dev.rlcraft.ice.optimizer.render.entity.ModelMeshPayload;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.Pbuffer;
import org.lwjgl.opengl.PixelFormat;

/** Exercises the production model state batch in a hidden compatibility context. */
public final class ModelStateBatchProbe {
    private static final int PARTS = 16;
    private static final int SIZE = 64;

    public static void main(String[] args) throws Exception {
        Pbuffer context = new Pbuffer(SIZE, SIZE, new PixelFormat().withDepthBits(24), null, null);
        try {
            context.makeCurrent();
            RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
            CacheBudget budget = new CacheBudget(1 << 20, 1 << 20, 1 << 20);
            ResourceLedger ledger = new ResourceLedger(guard, budget,
                new ResourceLedger.Destroyer() {
                    public void destroy(RenderResourceKind kind, int id) {
                        if (kind == RenderResourceKind.BUFFER) GL15.glDeleteBuffers(id);
                    }
                }, 64);
            LwjglModelMeshCache cache = new LwjglModelMeshCache(guard, ledger, budget);
            ClientEpochs epochs = new ClientEpochs();
            epochs.nextFrame();
            FrameStamp stamp = new FrameStamp(1, 1, epochs.snapshot());
            int original = GL15.glGenBuffers();
            try {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, original);
                EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER, original);
                LwjglModelMeshCache.Entry[] entries = new LwjglModelMeshCache.Entry[PARTS];
                for (int i = 0; i < PARTS; i++) {
                    ModelMeshPayload mesh = new ModelMeshPayload(i + 1, quad(i),
                        DefaultVertexFormats.POSITION_TEX_NORMAL, GL11.GL_QUADS, 4, 1, 1);
                    if (!cache.accept(mesh, stamp)) throw new AssertionError("upload declined");
                    entries[i] = cache.find(i + 1, stamp);
                    if (!cache.validate(entries[i])) throw new AssertionError("upload mismatch");
                    if (entries[i].getNativeBuffer() != entries[0].getNativeBuffer()
                        || entries[i].getFirstVertex() != i * 4) {
                        throw new AssertionError("model parts did not share an aligned page");
                    }
                    if (i > 0 && entries[i].getLogicalHandle() == entries[0].getLogicalHandle()) {
                        throw new AssertionError("shared page aliased mesh identity");
                    }
                }
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, original);
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, 128L, GL15.GL_STATIC_DRAW);
                GL11.glVertexPointer(2, GL11.GL_FLOAT, 24, 0L);
                GL11.glNormalPointer(GL11.GL_BYTE, 24, 20L);
                GL11.glTexCoordPointer(2, GL11.GL_FLOAT, 24, 12L);
                GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
                GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
                GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
                GL13.glClientActiveTexture(GL13.GL_TEXTURE1);
                GL11.glTexCoordPointer(2, GL11.GL_FLOAT, 24, 12L);
                GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
                GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
                GL11.glViewport(0, 0, SIZE, SIZE);
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glLoadIdentity();
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glLoadIdentity();
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glDisable(GL11.GL_LIGHTING);
                GL11.glColor4f(0.25F, 0.5F, 0.75F, 0.55F);
                seed(original);
                FloatBuffer identity = BufferUtils.createFloatBuffer(16);
                for (int i = 0; i < 16; i++) identity.put(i, i % 5 == 0 ? 1 : 0);
                EarlyMatrixStateTracker.seed(GL11.GL_MODELVIEW, identity, identity);
                int[] before = state();
                for (int scenario = 0; scenario < 3; scenario++) {
                    if (scenario == 1) {
                        GL11.glEnable(GL11.GL_BLEND);
                        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                    } else if (scenario == 2) {
                        GL11.glDisable(GL11.GL_BLEND);
                        GL11.glEnable(GL11.GL_DEPTH_TEST);
                    }
                    byte[] immediate = draw(cache, entries, stamp, false);
                    assertState(before, original);
                    byte[] legacy = drawLegacy();
                    assertState(before, original);
                    long bindsBefore = cache.getStateBatchBufferBinds();
                    long pointersBefore = cache.getStateBatchPointerSets();
                    byte[] batched = draw(cache, entries, stamp, true);
                    assertState(before, original);
                    if (!Arrays.equals(immediate, batched) || !Arrays.equals(legacy, batched)) {
                        throw new AssertionError("pixel mismatch scenario=" + scenario);
                    }
                    if (cache.getStateBatchBufferBinds() - bindsBefore != 1
                        || cache.getStateBatchPointerSets() - pointersBefore != 1) {
                        throw new AssertionError("shared page pointers were repeated per part");
                    }
                    boolean visible = false;
                    for (byte value : batched) visible |= value != 0;
                    if (!visible) throw new AssertionError("empty output");
                }
                long issued = cache.getStateBatchDraws();
                if (!cache.beginStateBatch()) throw new AssertionError("batch declined");
                try {
                    EarlyMatrixStateTracker.invalidate();
                    if (cache.drawInStateBatch(entries[0])) {
                        throw new AssertionError("invalid matrix accepted");
                    }
                    if (cache.getStateBatchDraws() != issued) {
                        throw new AssertionError("draw issued after matrix invalidation");
                    }
                } finally { cache.endStateBatch(); }
                assertState(before, original);
                EarlyMatrixStateTracker.seed(GL11.GL_MODELVIEW, identity, identity);
                checkPageTransitions(cache, entries[0], stamp);
                assertState(before, original);
                checkPublicationRollback(guard, stamp, original);
                checkZeroBudget(guard, stamp, original);
                checkContextAbandon(guard, stamp);
                if (GL11.glGetError() != GL11.GL_NO_ERROR) throw new AssertionError("GL error");
                System.out.println("production_model_state_batch parts=16 scenarios=3 legacy_pixels_equal=true client_state_equal=true mirror_equal=true dynamic_matrix_rejection=true shared_buffer=true aligned_offsets=true binds_per_scope=1 pointer_sets_per_scope=1 page_transitions=true publication_rollback=true replacement=true zero_gpu_budget=true context_abandon=true");
                System.out.println("renderer=" + GL11.glGetString(GL11.GL_RENDERER));
            } finally {
                cache.close(true);
                GL11.glFinish();
                ledger.destroyAll(1);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
                GL15.glDeleteBuffers(original);
            }
        } finally { context.destroy(); }
    }

    private static byte[] quad(int index) {
        ByteBuffer vertices = ByteBuffer.allocate(4 * 24).order(ByteOrder.nativeOrder());
        float width = .65F + index * .03F;
        for (float[] xy : new float[][] {{-.32F,-.24F},{.32F,-.24F},{.32F,.24F},{-.32F,.24F}}) {
            vertices.putFloat(xy[0] * width).putFloat(xy[1]).putFloat(0);
            vertices.putFloat(0).putFloat(0);
            vertices.put((byte) 0).put((byte) 0).put((byte) 127).put((byte) 0);
        }
        return vertices.array();
    }

    private static void checkPageTransitions(LwjglModelMeshCache cache,
        LwjglModelMeshCache.Entry first, FrameStamp stamp) {
        ByteBuffer vertices = ByteBuffer.allocate(96 * 1024).order(ByteOrder.nativeOrder());
        while (vertices.hasRemaining()) {
            vertices.putFloat(100).putFloat(100).putFloat(100);
            vertices.putFloat(0).putFloat(0).putInt(0);
        }
        ModelMeshPayload mesh = new ModelMeshPayload(1000, vertices.array(),
            DefaultVertexFormats.POSITION_TEX_NORMAL, GL11.GL_QUADS, 4096, 1, 1);
        if (!cache.accept(mesh, stamp)) throw new AssertionError("second page upload declined");
        LwjglModelMeshCache.Entry second = cache.find(1000, stamp);
        if (!cache.validate(second) || second.getNativeBuffer() == first.getNativeBuffer()) {
            throw new AssertionError("second model page was not created");
        }
        long binds = cache.getStateBatchBufferBinds();
        long pointers = cache.getStateBatchPointerSets();
        if (!cache.beginStateBatch()) throw new AssertionError("page transition scope declined");
        try {
            if (!cache.drawInStateBatch(first) || !cache.drawInStateBatch(second)
                || !cache.drawInStateBatch(first)) throw new AssertionError("page transition draw declined");
        } finally { cache.endStateBatch(); }
        if (cache.getStateBatchBufferBinds() - binds != 3
            || cache.getStateBatchPointerSets() - pointers != 3) {
            throw new AssertionError("page transition pointers did not follow buffer changes");
        }
    }

    private static void checkPublicationRollback(RenderThreadGuard guard,
        FrameStamp stamp, int original) throws Exception {
        CacheBudget budget = new CacheBudget(1 << 20, 1 << 20, 1 << 20);
        ResourceLedger ledger = ledger(guard, budget);
        final boolean[] failPublication = {false};
        Class<?> hookType = Class.forName(LwjglModelMeshCache.class.getName() + "$PublicationHook");
        Object hook = Proxy.newProxyInstance(hookType.getClassLoader(), new Class<?>[] {hookType},
            new InvocationHandler() {
                @Override public Object invoke(Object proxy, Method method, Object[] args) {
                    if (failPublication[0]) throw new IllegalStateException("injected publication failure");
                    return null;
                }
            });
        Constructor<LwjglModelMeshCache> constructor = LwjglModelMeshCache.class.getDeclaredConstructor(
            RenderThreadGuard.class, ResourceLedger.class, CacheBudget.class, hookType);
        constructor.setAccessible(true);
        LwjglModelMeshCache cache = constructor.newInstance(guard, ledger, budget, hook);
        try {
            ModelMeshPayload oldMesh = new ModelMeshPayload(41, quad(0),
                DefaultVertexFormats.POSITION_TEX_NORMAL, GL11.GL_QUADS, 4, 1, 1);
            ModelMeshPayload newMesh = new ModelMeshPayload(41, quad(3),
                DefaultVertexFormats.POSITION_TEX_NORMAL, GL11.GL_QUADS, 4, 1, 1);
            if (!cache.accept(oldMesh, stamp)) throw new AssertionError("initial publication declined");
            LwjglModelMeshCache.Entry old = cache.find(41, stamp);
            failPublication[0] = true;
            try {
                cache.accept(newMesh, stamp);
                throw new AssertionError("injected publication failure ignored");
            } catch (IllegalStateException expected) {
                if (!"injected publication failure".equals(expected.getMessage())) throw expected;
            }
            if (cache.find(41, stamp) != old || !cache.validate(old)
                || EarlyGlStateTracker.arrayBufferBinding() != original) {
                throw new AssertionError("failed replacement did not retain original mesh");
            }
            failPublication[0] = false;
            if (!cache.accept(newMesh, stamp)) throw new AssertionError("replacement declined");
            LwjglModelMeshCache.Entry replacement = cache.find(41, stamp);
            if (replacement == old || !cache.validate(replacement) || old.isValidated()
                || cache.validate(old)) {
                throw new AssertionError("old mesh was not retired by replacement");
            }
        } finally {
            cache.close(true);
            GL11.glFinish();
            ledger.destroyAll(1);
        }
        if (budget.snapshot().getGpuUsed() != 0 || budget.snapshot().getHeapUsed() != 0
            || budget.snapshot().getDirectUsed() != 0) throw new AssertionError("model rollback budget leak");
    }

    private static void checkZeroBudget(RenderThreadGuard guard, FrameStamp stamp, int original) {
        CacheBudget budget = new CacheBudget(1 << 20, 1 << 20, 1);
        ResourceLedger ledger = ledger(guard, budget);
        LwjglModelMeshCache cache = new LwjglModelMeshCache(guard, ledger, budget);
        try {
            ModelMeshPayload mesh = new ModelMeshPayload(99, quad(0),
                DefaultVertexFormats.POSITION_TEX_NORMAL, GL11.GL_QUADS, 4, 1, 1);
            if (cache.accept(mesh, stamp) || ledger.snapshot().getCreated() != 0
                || EarlyGlStateTracker.arrayBufferBinding() != original) {
                throw new AssertionError("zero GPU budget allocated a model page");
            }
        } finally { cache.close(true); }
        if (budget.snapshot().getDirectUsed() != 0) throw new AssertionError("staging budget leak");
    }

    private static ResourceLedger ledger(RenderThreadGuard guard, CacheBudget budget) {
        return new ResourceLedger(guard, budget, new ResourceLedger.Destroyer() {
            @Override public void destroy(RenderResourceKind kind, int id) {
                if (kind == RenderResourceKind.BUFFER) GL15.glDeleteBuffers(id);
            }
        }, 64);
    }

    private static void checkContextAbandon(RenderThreadGuard guard, FrameStamp stamp) {
        CacheBudget budget = new CacheBudget(1 << 20, 1 << 20, 1 << 20);
        final int[] deletes = {0};
        ResourceLedger ledger = new ResourceLedger(guard, budget, new ResourceLedger.Destroyer() {
            @Override public void destroy(RenderResourceKind kind, int id) { deletes[0]++; }
        }, 64);
        LwjglModelMeshCache cache = new LwjglModelMeshCache(guard, ledger, budget);
        for (int id = 51; id <= 52; id++) {
            ModelMeshPayload mesh = new ModelMeshPayload(id, quad(id - 51),
                DefaultVertexFormats.POSITION_TEX_NORMAL, GL11.GL_QUADS, 4, 1, 1);
            if (!cache.accept(mesh, stamp)) throw new AssertionError("context abandon upload declined");
        }
        cache.invalidate(51); // Creates a native range Fence on the still-shared page.
        cache.close(false);
        ledger.abandonContext(1);
        if (deletes[0] != 0 || budget.snapshot().getGpuUsed() != 0
            || budget.snapshot().getHeapUsed() != 0 || budget.snapshot().getDirectUsed() != 0) {
            throw new AssertionError("context abandon deleted a name or retained accounting");
        }
        // The probe context teardown owns the intentionally abandoned GL objects.
    }

    private static void seed(int original) {
        EarlyGlStateTracker.useProgram(0);
        EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER, original);
        EarlyGlStateTracker.clientActiveTexture(GL13.GL_TEXTURE0);
        EarlyGlStateTracker.depthFunction(GL11.GL_LESS);
        EarlyGlStateTracker.seedDrawState(0, 0, 0, false, GL11.GL_ONE, GL11.GL_ZERO,
            GL11.GL_ONE, GL11.GL_ZERO, false, true, false, 15, .25F, .5F, .75F, .55F);
    }

    private static byte[] draw(LwjglModelMeshCache cache, LwjglModelMeshCache.Entry[] entries,
                               FrameStamp stamp, boolean batch) {
        GL11.glClearColor(0, 0, 0, 0);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        if (batch && !cache.beginStateBatch()) throw new AssertionError("batch declined");
        try {
            for (int i = 0; i < entries.length; i++) {
                GL11.glPushMatrix();
                GL11.glTranslatef((i % 4 - 1.5F) * .28F, (i / 4 - 1.5F) * .25F, i * .012F);
                GL11.glRotatef(i * 9, 0, 0, 1);
                try {
                    boolean handled = batch ? cache.drawInStateBatch(entries[i])
                        : cache.drawCurrent(entries[i], stamp);
                    if (!handled) throw new AssertionError("draw declined");
                } finally { GL11.glPopMatrix(); }
            }
        } finally { if (batch) cache.endStateBatch(); }
        ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        GL11.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        byte[] output = new byte[pixels.remaining()];
        pixels.get(output);
        return output;
    }

    private static byte[] drawLegacy() {
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        for (int i = 0; i < PARTS; i++) {
            GL11.glPushMatrix();
            GL11.glTranslatef((i % 4 - 1.5F) * .28F, (i / 4 - 1.5F) * .25F, i * .012F);
            GL11.glRotatef(i * 9, 0, 0, 1);
            ByteBuffer vertices = ByteBuffer.wrap(quad(i)).order(ByteOrder.nativeOrder());
            GL11.glBegin(GL11.GL_QUADS);
            for (int vertex = 0; vertex < 4; vertex++) {
                int offset = vertex * 24;
                GL11.glVertex3f(vertices.getFloat(offset), vertices.getFloat(offset + 4),
                    vertices.getFloat(offset + 8));
            }
            GL11.glEnd();
            GL11.glPopMatrix();
        }
        ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        GL11.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        byte[] output = new byte[pixels.remaining()];
        pixels.get(output);
        return output;
    }

    private static int[] state() {
        int[] result = new int[12];
        result[0] = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        result[1] = GL11.glGetInteger(GL15.GL_VERTEX_ARRAY_BUFFER_BINDING);
        result[2] = GL11.glGetInteger(GL15.GL_NORMAL_ARRAY_BUFFER_BINDING);
        result[3] = GL11.glGetInteger(GL15.GL_TEXTURE_COORD_ARRAY_BUFFER_BINDING);
        result[4] = GL11.glGetInteger(GL11.GL_VERTEX_ARRAY_SIZE);
        result[5] = GL11.glGetInteger(GL11.GL_VERTEX_ARRAY_STRIDE);
        result[6] = GL11.glIsEnabled(GL11.GL_VERTEX_ARRAY) ? 1 : 0;
        result[7] = GL11.glIsEnabled(GL11.GL_NORMAL_ARRAY) ? 1 : 0;
        result[8] = GL11.glIsEnabled(GL11.GL_TEXTURE_COORD_ARRAY) ? 1 : 0;
        result[9] = GL11.glGetInteger(GL13.GL_CLIENT_ACTIVE_TEXTURE);
        GL13.glClientActiveTexture(GL13.GL_TEXTURE1);
        result[10] = GL11.glGetInteger(GL15.GL_TEXTURE_COORD_ARRAY_BUFFER_BINDING);
        result[11] = GL11.glIsEnabled(GL11.GL_TEXTURE_COORD_ARRAY) ? 1 : 0;
        GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
        return result;
    }

    private static void assertState(int[] before, int original) {
        if (!Arrays.equals(before, state())) throw new AssertionError("client state mismatch");
        if (EarlyGlStateTracker.arrayBufferBinding() != original) {
            throw new AssertionError("array buffer mirror mismatch");
        }
    }
}
