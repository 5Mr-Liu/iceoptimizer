package dev.rlcraft.ice.hooks;

import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyMatrixStateTracker;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyModelLightingState;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.entity.LwjglModelMeshCache;
import dev.rlcraft.ice.optimizer.render.entity.ModelMeshPayload;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.resource.*;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import org.lwjgl.util.glu.Project;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;

/** Real transformed Forge/G5 wrappers, actual legacy exit and native palette/list comparison. */
public final class ModelPoseBoundaryProbe {
    private static final int SIZE = 256;
    private static final String GSM = "net/minecraft/client/renderer/GlStateManager";
    private static final Set<String> WRAPPERS = new HashSet<String>(Arrays.asList(
        "func_179128_n", "func_179096_D", "func_179094_E", "func_179121_F",
        "func_179114_b", "func_179152_a", "func_179139_a", "func_179109_b",
        "func_179137_b", "func_179110_a", "func_179130_a"));
    private static long nativeMultiplies;
    private static final Map<Class<?>, MoBendsRotation> MOBENDS = new HashMap<Class<?>, MoBendsRotation>();

    public static void countNativeMultiply() { nativeMultiplies++; }

    public static void main(String[] args) throws Exception {
        if (args.length != 4 && args.length != 5)
            throw new IllegalArgumentException("forge-srg client optifine tsrg [mobends]");
        byte[] forgeBytes = readJar(args[0], GSM);
        Class<?> forge = wrappers(forgeBytes, "Forge", true);
        Class<?> forgeBefore = wrappers(forgeBytes, "ForgeBeforeMultDeferral", false);
        URLClassLoader optifineLoader = new URLClassLoader(new URL[] {
            new File(args[2]).toURI().toURL(), new File(args[1]).toURI().toURL()
        }, ModelPoseBoundaryProbe.class.getClassLoader());
        Class<?> optifine, optifineBefore;
        try {
            Class<?> transformer = Class.forName("optifine.OptiFineClassTransformer", true, optifineLoader);
            byte[] original = readJar(args[1], "bus");
            byte[] patched = (byte[]) transformer.getMethod("transform", String.class,
                String.class, byte[].class).invoke(transformer.newInstance(), "bus",
                    GSM.replace('/', '.'), original);
            if (Arrays.equals(original, patched)) throw new AssertionError("G5 patch not applied");
            byte[] mapped = remap(patched, Paths.get(args[3]));
            optifine = wrappers(mapped, "OptiFineG5", true);
            optifineBefore = wrappers(mapped, "OptiFineG5BeforeMultDeferral", false);
        } finally { optifineLoader.close(); }
        if (args.length == 5) for (Class<?> wrapper : new Class<?>[]{forge, forgeBefore, optifine, optifineBefore})
            MOBENDS.put(wrapper, new MoBendsRotation(wrapper, args[4]));
        Pbuffer context = new Pbuffer(SIZE, SIZE, new PixelFormat().withDepthBits(24), null, null);
        try {
            context.makeCurrent();
            exercise(forge, forgeBefore); exercise(optifine, optifineBefore);
            System.out.println("actual_forge_and_optifine_matrix_wrappers PASS renderer=" + GL11.glGetString(GL11.GL_RENDERER));
        } finally { context.destroy(); }
    }

    private static void exercise(Class<?> wrapper, Class<?> beforeWrapper) throws Exception {
        RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        CacheBudget budget = new CacheBudget(4 << 20, 4 << 20, 4 << 20);
        ResourceLedger ledger = new ResourceLedger(guard, budget, new ResourceLedger.Destroyer() {
            public void destroy(RenderResourceKind kind, int id) {
                if (kind == RenderResourceKind.BUFFER) GL15.glDeleteBuffers(id);
                else if (kind == RenderResourceKind.PROGRAM) GL20.glDeleteProgram(id);
                else if (kind == RenderResourceKind.SHADER) GL20.glDeleteShader(id);
                else if (kind == RenderResourceKind.VERTEX_ARRAY) GL30.glDeleteVertexArrays(id);
            }
        }, 128);
        LwjglModelMeshCache cache = new LwjglModelMeshCache(guard, ledger, budget);
        cache.configurePoseBatching(true);
        ClientEpochs epochs = new ClientEpochs(); epochs.nextFrame();
        FrameStamp stamp = new FrameStamp(1, 1, epochs.snapshot());
        Class<?> runtimeType = Class.forName("dev.rlcraft.ice.optimizer.client.ModernRendererRuntime");
        Constructor<?> constructor = runtimeType.getDeclaredConstructor(ClientEpochs.class, CacheBudget.class);
        constructor.setAccessible(true);
        Object runtime = constructor.newInstance(epochs, budget);
        Method legacyExit = runtimeType.getDeclaredMethod("endLegacyBoundary", long.class, Throwable.class);
        legacyExit.setAccessible(true);
        int list = GL11.glGenLists(1);
        try {
            // Original compiled ModelRenderer geometry uses a real display list.
            GL11.glNewList(list, GL11.GL_COMPILE); GL11.glBegin(GL11.GL_QUADS);
            for (float[] xy : coordinates()) {
                GL11.glNormal3f(0, 0, 1); GL11.glTexCoord2f(0, 0); GL11.glVertex3f(xy[0], xy[1], 0);
            }
            GL11.glEnd(); GL11.glEndList();
            seedGl();
            LwjglModelMeshCache.Entry[] entries = new LwjglModelMeshCache.Entry[64];
            for (int i = 0; i < entries.length; i++) {
                if (!cache.accept(new ModelMeshPayload(100 + i, quad(), DefaultVertexFormats.POSITION_TEX_NORMAL,
                    GL11.GL_QUADS, 4, 1, 1), stamp)) throw new AssertionError("mesh upload");
                entries[i] = cache.findStateBatchEntry(100 + i, stamp);
            }
            verifyConsumerAndBudgetRecovery(wrapper, runtime, legacyExit, cache, entries, list, budget, ledger);
            long poseDraws = cache.getPoseBatchDraws(), poseParts = cache.getPoseBatchParts();
            int expectedParts = 0;
            for (int frame = 0; frame < 12; frame++) {
                int count = (frame & 1) == 0 ? 8 : 16;
                byte[] reference = render(wrapper, runtime, legacyExit, cache, entries, count, list, frame, false);
                byte[] modern = render(wrapper, runtime, legacyExit, cache, entries, count, list, frame, true);
                int max = 0, lit = 0;
                for (int i = 0; i < reference.length; i++) {
                    max = Math.max(max, Math.abs((reference[i] & 255) - (modern[i] & 255)));
                    if (reference[i] != 0) lit++;
                }
                if (lit < 100 || max > 1) throw new AssertionError("pixels frame=" + frame + " error=" + max + " lit=" + lit);
                expectedParts += count;
                System.out.println(wrapper.getSimpleName() + " frame=" + frame + " parts=" + count + " max_channel_error=" + max);
            }
            if (cache.getPoseBatchDraws() - poseDraws != 12 || cache.getPoseBatchParts() - poseParts != expectedParts)
                throw new AssertionError("pose did not run at every real camera boundary");
            System.out.println(wrapper.getSimpleName() + " loaded_wrappers=" + WRAPPERS.size()
                + " legacy_hud_exits=24 camera_frames=24 palette_draws=12 palette_parts=" + expectedParts
                + " caller_matrix_stack_restored=true global_known_not_faked=true");
            verifyMultMatrix(wrapper, beforeWrapper, runtime, legacyExit, cache, entries, list);
        } finally {
            cache.close(true); ledger.destroyAll(1); GL11.glDeleteLists(list, 1);
        }
        if (budget.snapshot().getGpuUsed() != 0 || budget.snapshot().getDirectUsed() != 0)
            throw new AssertionError("budget leak");
    }

    private static void verifyMultMatrix(Class<?> wrapper, Class<?> beforeWrapper, Object runtime,
        Method legacyExit, LwjglModelMeshCache cache, LwjglModelMeshCache.Entry[] entries, int list) throws Exception {
        for (int count : new int[]{32, 64}) for (int variant = 0; variant < 4; variant++) {
            int frame = variant + 2;
            byte[] reference = render(wrapper, runtime, legacyExit, cache, entries, count, list, frame, false, variant);
            long draws = cache.getPoseBatchDraws(), multiplies = nativeMultiplies;
            byte[] before = render(beforeWrapper, runtime, legacyExit, cache, entries, count, list, frame, true, variant);
            long beforeDraws = cache.getPoseBatchDraws() - draws, beforeMultiplies = nativeMultiplies - multiplies;
            draws = cache.getPoseBatchDraws(); multiplies = nativeMultiplies;
            byte[] after = render(wrapper, runtime, legacyExit, cache, entries, count, list, frame, true, variant);
            long afterDraws = cache.getPoseBatchDraws() - draws, afterMultiplies = nativeMultiplies - multiplies;
            int oldError = comparePixels(reference, before), newError = comparePixels(reference, after);
            if (beforeDraws != count || beforeMultiplies != count || afterMultiplies != 0
                || afterDraws < 1 || afterDraws > 2 || oldError > 1 || newError > 1)
                throw new AssertionError("multMatrix batching count=" + count + " draws=" + beforeDraws + "/" + afterDraws
                    + " nativeMult=" + beforeMultiplies + "/" + afterMultiplies + " errors=" + oldError + "/" + newError);
            System.out.println(wrapper.getSimpleName() + " multMatrix_parts=" + count + " buffer_variant=" + variant
                + " before_palette_draws=" + beforeDraws + " after_palette_draws=" + afterDraws
                + " before_native_mult=" + beforeMultiplies + " after_native_mult=" + afterMultiplies
                + " old_pixel_error=" + oldError + " new_pixel_error=" + newError
                + " actual_MoBends_rotate=" + (variant == 0 && MOBENDS.containsKey(wrapper)));
        }
        verifyNativeModesAndBufferErrors(wrapper, runtime, legacyExit, cache, entries);
    }

    private static int comparePixels(byte[] expected, byte[] actual) {
        int max = 0, lit = 0;
        for (int i = 0; i < expected.length; i++) {
            max = Math.max(max, Math.abs((expected[i] & 255) - (actual[i] & 255)));
            if (expected[i] != 0) lit++;
        }
        if (lit < 100) throw new AssertionError("empty reference");
        return max;
    }

    private static void verifyNativeModesAndBufferErrors(Class<?> wrapper, Object runtime, Method legacyExit,
        LwjglModelMeshCache cache, LwjglModelMeshCache.Entry[] entries) throws Exception {
        // Scope-outside and projection/texture matrix operations retain native execution.
        legacyExit.invoke(runtime, 0L, null);
        FloatBuffer matrix = matrixView(1, 0);
        for (int mode : new int[]{GL11.GL_MODELVIEW, GL11.GL_PROJECTION, GL11.GL_TEXTURE}) {
            call(wrapper, "func_179128_n", mode); call(wrapper, "func_179096_D");
            long calls = nativeMultiplies;
            call(wrapper, "func_179110_a", matrix);
            if (nativeMultiplies != calls + 1) throw new AssertionError("outside scope skipped native matrix");
        }
        call(wrapper, "func_179128_n", GL11.GL_MODELVIEW); call(wrapper, "func_179096_D"); seedGl();
        if (!cache.beginStateBatch(entries, 8)) throw new AssertionError("validation scope");
        try {
            for (int mode : new int[]{GL11.GL_PROJECTION, GL11.GL_TEXTURE}) {
                call(wrapper, "func_179128_n", mode);
                long calls = nativeMultiplies;
                call(wrapper, "func_179110_a", matrix);
                if (nativeMultiplies != calls + 1) throw new AssertionError("non-modelview skipped native matrix");
            }
            call(wrapper, "func_179128_n", GL11.GL_MODELVIEW);
            call(wrapper, "func_179109_b", .125F, 0F, 0F);
            FloatBuffer shortBuffer = BufferUtils.createFloatBuffer(20); shortBuffer.position(5);
            for (FloatBuffer bad : new FloatBuffer[]{null, FloatBuffer.allocate(16), shortBuffer}) {
                try {
                    wrapper.getMethod("func_179110_a", FloatBuffer.class).invoke(null, bad);
                    throw new AssertionError("native invalid buffer accepted");
                } catch (InvocationTargetException expected) {
                    if (!(expected.getCause() instanceof IllegalArgumentException)
                        && !(expected.getCause() instanceof NullPointerException)) throw expected;
                }
                FloatBuffer nativeMatrix = BufferUtils.createFloatBuffer(16);
                GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, nativeMatrix);
                if (Math.abs(nativeMatrix.get(12) - .125F) > .00001F)
                    throw new AssertionError("buffer error lost pending transform");
            }
        } finally { cache.endStateBatch(); }
        if (GL11.glGetError() != GL11.GL_NO_ERROR) throw new AssertionError("buffer/mode GL error");
        System.out.println(wrapper.getSimpleName() + " outside_scope_native=true projection_texture_native=true"
            + " invalid_buffer_exceptions_preserved=true pending_matrix_restored=true");
    }

    private static void verifyConsumerAndBudgetRecovery(Class<?> wrapper, Object runtime, Method legacyExit,
        LwjglModelMeshCache cache, LwjglModelMeshCache.Entry[] entries, int list, CacheBudget budget,
        ResourceLedger ledger) throws Exception {
        legacyExit.invoke(runtime, 0L, null); seedGl();
        long created = ledger.snapshot().getCreated();
        if (!cache.beginStateBatch(entries, 4)) throw new AssertionError("small batch");
        cache.endStateBatch();
        if (EarlyMatrixStateTracker.isModelViewKnown() || EarlyModelLightingState.isKnown()
            || ledger.snapshot().getCreated() != created) throw new AssertionError("small tree seeded unused pose state");
        long directBefore = budget.snapshot().getDirectUsed();
        CacheBudget.Reservation fill = budget.tryReserve(BudgetKind.DIRECT,
            budget.snapshot().getDirectLimit() - directBefore);
        try { beginFailure(cache, entries, 8, "palette-budget"); }
        finally { fill.close(); }
        fill = budget.tryReserve(BudgetKind.GPU, budget.snapshot().getGpuLimit() - budget.snapshot().getGpuUsed());
        try {
            // Opaque program/VAO names do not pretend to own fixed GPU bytes.
            // The actual merged vertex payload remains correctly budgeted.
            beginFailure(cache, entries, 8, "mesh-budget");
            beginFailure(cache, entries, 8, "mesh-budget");
            if (ledger.snapshot().getCreated() != created + 1)
                throw new AssertionError("full payload budget blocked or rebuilt the opaque program");
            if (budget.snapshot().getGpuUsed() != budget.snapshot().getGpuLimit())
                throw new AssertionError("opaque program charged guessed payload bytes");
        } finally { fill.close(); }
        long before = cache.getPoseBatchDraws();
        render(wrapper, runtime, legacyExit, cache, entries, 8, list, 0, true);
        if (cache.getPoseBatchDraws() != before + 1) throw new AssertionError("program budget rejection permanently disabled pose");
        fill = budget.tryReserve(BudgetKind.GPU, budget.snapshot().getGpuLimit() - budget.snapshot().getGpuUsed());
        try { beginFailure(cache, entries, 16, "mesh-budget"); }
        finally { fill.close(); }
        // Exactly enough for merged vertices; its immutable VAO needs no
        // invented payload reservation on top of those real vertex bytes.
        long gpuBefore = budget.snapshot().getGpuUsed();
        created = ledger.snapshot().getCreated();
        fill = budget.tryReserve(BudgetKind.GPU, budget.snapshot().getGpuLimit() - gpuBefore - 16 * 4 * 28);
        before = cache.getPoseBatchDraws();
        try {
            render(wrapper, runtime, legacyExit, cache, entries, 16, list, 0, true);
            if (ledger.snapshot().getCreated() != created + 2)
                throw new AssertionError("exact payload capacity did not create mesh plus uncharged VAO");
        } finally { fill.close(); }
        if (budget.snapshot().getGpuUsed() != gpuBefore + 16 * 4 * 28)
            throw new AssertionError("mesh payload accounting changed");
        if (cache.getPoseBatchDraws() != before + 1) throw new AssertionError("mesh budget rejection did not recover");
        System.out.println(wrapper.getSimpleName()
            + " small_tree_native_seed=false palette_budget_retry=true opaque_program_at_full_payload_budget=true"
            + " mesh_budget_retry=true vao_extra_payload_bytes=0");
    }

    private static void beginFailure(LwjglModelMeshCache cache, LwjglModelMeshCache.Entry[] entries,
                                     int count, String detail) {
        if (!cache.beginStateBatch(entries, count)) throw new AssertionError("ordinary arrays disabled on budget miss");
        try { if (!detail.equals(cache.getPoseBatchDetail())) throw new AssertionError(cache.getPoseBatchDetail()); }
        finally { cache.endStateBatch(); }
    }

    private static byte[] render(Class<?> wrapper, Object runtime, Method legacyExit,
        LwjglModelMeshCache cache, LwjglModelMeshCache.Entry[] entries, int count, int list,
        int frame, boolean modern) throws Exception {
        return render(wrapper, runtime, legacyExit, cache, entries, count, list, frame, modern, -1);
    }

    private static byte[] render(Class<?> wrapper, Object runtime, Method legacyExit,
        LwjglModelMeshCache cache, LwjglModelMeshCache.Entry[] entries, int count, int list,
        int frame, boolean modern, int matrixVariant) throws Exception {
        // Same invalidation performed after Forge HUD/legacy rendering, then the
        // actual EntityRenderer.func_78479_a camera sequence, including raw GLU.
        legacyExit.invoke(runtime, 0L, null);
        GL11.glViewport(0, 0, SIZE, SIZE);
        call(wrapper, "func_179128_n", GL11.GL_PROJECTION); call(wrapper, "func_179096_D");
        Project.gluPerspective(60 + frame, 1, .05F, 256);
        call(wrapper, "func_179128_n", GL11.GL_MODELVIEW); call(wrapper, "func_179096_D");
        call(wrapper, "func_179114_b", frame * .37F, 0F, 1F, 0F);
        call(wrapper, "func_179109_b", frame * .002F, -.05F, -3F);
        // Caller levels predate the pose scope. A raw third-party transform also
        // bypasses our wrapper and must be included by the native entry snapshot.
        call(wrapper, "func_179094_E"); call(wrapper, "func_179109_b", .07F, .03F, 0F);
        GL11.glTranslatef(.019F, -.017F, 0F);
        if (EarlyMatrixStateTracker.isKnown()) throw new AssertionError("camera fabricated full matrix mirror");
        FloatBuffer before = BufferUtils.createFloatBuffer(16); GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, before);
        int depth = GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH);
        GL20.glUseProgram(0); GL11.glDisable(GL11.GL_TEXTURE_2D); GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_FOG); GL11.glDisable(GL11.GL_CULL_FACE); GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glColor4f(.3F, .6F, .8F, 1);
        GL11.glClearColor(0, 0, 0, 0); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        seedGl();
        if (modern && !cache.beginStateBatch(entries, count)) throw new AssertionError("begin batch");
        try {
            if (modern && (!EarlyMatrixStateTracker.isModelViewKnown() || EarlyMatrixStateTracker.isKnown()))
                throw new AssertionError("local model-view scope");
            for (int part = 0; part < count; part++) {
                call(wrapper, "func_179094_E");
                int columns = count > 16 ? 8 : 4;
                call(wrapper, "func_179109_b", (part % columns - (columns - 1) * .5F) * .22F,
                    (part / columns - (count / columns - 1) * .5F) * .22F, 0F);
                if (matrixVariant < 0) call(wrapper, "func_179114_b", part * 11F + frame, 0F, 1F, 0F);
                else if (matrixVariant == 0 && MOBENDS.containsKey(wrapper)) MOBENDS.get(wrapper).rotate(part * 11F + frame);
                else {
                    FloatBuffer transform = matrixView(part * 11F + frame, matrixVariant);
                    transform.mark(); int position = transform.position(), limit = transform.limit();
                    call(wrapper, "func_179110_a", transform);
                    transform.reset();
                    if (position != transform.position() || limit != transform.limit()) throw new AssertionError("buffer bounds changed");
                }
                call(wrapper, "func_179152_a", 1F + frame * .008F, .93F, .8F);
                if (modern) { if (!cache.drawInStateBatch(entries[part])) throw new AssertionError("draw part"); }
                else GL11.glCallList(list);
                call(wrapper, "func_179121_F");
            }
        } finally { if (modern) cache.endStateBatch(); }
        FloatBuffer after = BufferUtils.createFloatBuffer(16); GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, after);
        for (int i = 0; i < 16; i++) if (Math.abs(before.get(i) - after.get(i)) > .00001F)
            throw new AssertionError("caller matrix changed " + i);
        if (GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH) != depth) throw new AssertionError("caller depth changed");
        if (EarlyMatrixStateTracker.isModelViewKnown()) throw new AssertionError("scope leaked matrix");
        call(wrapper, "func_179121_F");
        if (GL11.glGetError() != GL11.GL_NO_ERROR) throw new AssertionError("GL error");
        ByteBuffer buffer = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        GL11.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
        byte[] result = new byte[buffer.remaining()]; buffer.get(result); return result;
    }

    private static void seedGl() {
        EarlyGlStateTracker.useProgram(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM));
        EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER, GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING));
        EarlyGlStateTracker.bindVertexArray(GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING));
        EarlyGlStateTracker.clientActiveTexture(GL11.glGetInteger(GL13.GL_CLIENT_ACTIVE_TEXTURE));
        EarlyGlStateTracker.depthFunction(GL11.glGetInteger(GL11.GL_DEPTH_FUNC));
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0); int texture0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE1); int texture1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(active);
        FloatBuffer color = BufferUtils.createFloatBuffer(16); GL11.glGetFloat(GL11.GL_CURRENT_COLOR, color);
        ByteBuffer mask = BufferUtils.createByteBuffer(16); GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK, mask);
        int bits = (mask.get(0) != 0 ? 1 : 0) | (mask.get(1) != 0 ? 2 : 0)
            | (mask.get(2) != 0 ? 4 : 0) | (mask.get(3) != 0 ? 8 : 0);
        EarlyGlStateTracker.seedDrawState(active - GL13.GL_TEXTURE0, texture0, texture1,
            GL11.glIsEnabled(GL11.GL_BLEND), GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),
            GL11.glGetInteger(GL14.GL_BLEND_DST_RGB), GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),
            GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA), GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
            GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK), GL11.glIsEnabled(GL11.GL_CULL_FACE),
            bits, color.get(0), color.get(1), color.get(2), color.get(3));
    }

    private static void call(Class<?> wrapper, String name, Object... arguments) throws Exception {
        for (Method method : wrapper.getDeclaredMethods()) if (name.equals(method.getName())) {
            try { method.invoke(null, arguments); return; }
            catch (InvocationTargetException error) { throw new AssertionError("actual wrapper " + name, error.getCause()); }
        }
        throw new AssertionError("missing wrapper " + name);
    }

    private static Class<?> wrappers(byte[] original, String label, boolean deferMultiply) throws Exception {
        byte[] transformed = new GlStateTrackingAdapter(GlStateTrackingAdapter.Part.GL_STATE_MANAGER)
            .transform(GSM.replace('/', '.'), original, new TargetSpec(GSM.replace('/', '.'),
                "modern-entity-backend", "native-probe", Collections.<String>emptySet()));
        ClassNode source = new ClassNode(Opcodes.ASM5); new ClassReader(transformed).accept(source, 0);
        ClassNode shell = new ClassNode(Opcodes.ASM5);
        shell.version = Opcodes.V1_8; shell.access = Opcodes.ACC_PUBLIC; shell.superName = "java/lang/Object";
        shell.name = "dev/rlcraft/ice/hooks/ActualMatrixWrappers" + label;
        for (MethodNode method : source.methods) if (WRAPPERS.contains(method.name)) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof FieldInsnNode) throw new AssertionError("unexpected wrapper field");
                if (instruction instanceof MethodInsnNode && GSM.equals(((MethodInsnNode) instruction).owner))
                    throw new AssertionError("unexpected wrapper self-call");
                if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode invocation = (MethodInsnNode) instruction;
                    if (!deferMultiply && "deferMultMatrix".equals(invocation.name)
                        && GlStateTrackingAdapter.MODEL_BARRIER.equals(invocation.owner)) {
                        // Restore exactly the pre-fix entry: remove ALOAD, defer,
                        // IFEQ, RETURN, while retaining the ordinary branch label.
                        AbstractInsnNode load = invocation.getPrevious();
                        AbstractInsnNode branch = invocation.getNext();
                        AbstractInsnNode exit = branch.getNext();
                        if (load.getOpcode() != Opcodes.ALOAD || branch.getOpcode() != Opcodes.IFEQ
                            || exit.getOpcode() != Opcodes.RETURN) throw new AssertionError("defer branch shape");
                        method.instructions.remove(load); method.instructions.remove(invocation);
                        method.instructions.remove(branch); method.instructions.remove(exit);
                    }
                    if (invocation.owner.equals("org/lwjgl/opengl/GL11") && invocation.name.equals("glMultMatrix"))
                        method.instructions.insertBefore(invocation, new MethodInsnNode(Opcodes.INVOKESTATIC,
                            org.objectweb.asm.Type.getInternalName(ModelPoseBoundaryProbe.class), "countNativeMultiply", "()V", false));
                }
            }
            // Keep actual wrapper behavior; add only a native-call counter and
            // the explicitly labeled pre-fix branch variant for comparison.
            shell.methods.add(method);
        }
        if (shell.methods.size() != WRAPPERS.size()) throw new AssertionError("wrapper extraction " + label);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        shell.accept(writer); final byte[] bytes = writer.toByteArray();
        return new ClassLoader(ModelPoseBoundaryProbe.class.getClassLoader()) {
            Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
        }.define();
    }

    private static FloatBuffer matrixView(float angle, int variant) {
        int offset = variant == 0 ? 0 : variant == 1 ? 5 : 3;
        ByteBuffer bytes = BufferUtils.createByteBuffer(32 * 4);
        FloatBuffer nativeView = bytes.asFloatBuffer();
        float c = (float) Math.cos(Math.toRadians(angle)), s = (float) Math.sin(Math.toRadians(angle));
        for (int i = 0; i < 16; i++) nativeView.put(offset + i, i % 5 == 0 ? 1F : 0F);
        nativeView.put(offset, c); nativeView.put(offset + 2, -s);
        nativeView.put(offset + 8, s); nativeView.put(offset + 10, c);
        nativeView.position(offset); nativeView.limit(offset + 16);
        if (variant == 2) return nativeView.slice().asReadOnlyBuffer();
        if (variant == 3) {
            ByteOrder opposite = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
            FloatBuffer swapped = bytes.order(opposite).asFloatBuffer();
            swapped.position(offset); swapped.limit(offset + 16); return swapped;
        }
        return nativeView;
    }

    private static final class MoBendsRotation {
        private final Constructor<?> quaternion;
        private final Method rotate;
        private final FloatBuffer buffer;

        MoBendsRotation(Class<?> wrapper, String jar) throws Exception {
            final String originalName = "goblinbob/mobends/core/util/GlHelper";
            Map<String,String> names = new HashMap<String,String>();
            names.put(GSM, wrapper.getName().replace('.', '/'));
            names.put(originalName, "dev/rlcraft/ice/hooks/ActualMoBends" + wrapper.getSimpleName());
            ClassWriter writer = new ClassWriter(0);
            new ClassReader(readJar(jar, originalName)).accept(new ClassRemapper(writer, new SimpleRemapper(names)), 0);
            final byte[] bytes = writer.toByteArray();
            Class<?> helper = new URLClassLoader(new URL[]{new File(jar).toURI().toURL()}, wrapper.getClassLoader()) {
                Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
            }.define();
            Class<?> quaternionType = Class.forName("goblinbob.mobends.core.math.Quaternion", true, helper.getClassLoader());
            quaternion = quaternionType.getConstructor(float.class, float.class, float.class, float.class);
            rotate = helper.getMethod("rotate", quaternionType);
            Field field = helper.getDeclaredField("BUF_FLOAT_16"); field.setAccessible(true);
            buffer = (FloatBuffer) field.get(null);
        }

        void rotate(float angle) throws Exception {
            double radians = Math.toRadians(angle) * .5;
            rotate.invoke(null, quaternion.newInstance(0F, (float) Math.sin(radians), 0F, (float) Math.cos(radians)));
            if (buffer.position() != 0 || buffer.limit() != 16) throw new AssertionError("actual MoBends buffer consumed");
        }
    }

    private static byte[] remap(byte[] original, Path tsrg) throws IOException {
        final Map<String, String> classes = new HashMap<String, String>();
        final Map<String, String> fields = new HashMap<String, String>();
        final Map<String, String> methods = new HashMap<String, String>();
        String owner = null;
        for (String line : Files.readAllLines(tsrg, StandardCharsets.UTF_8)) {
            String[] parts = line.trim().split(" +");
            if (!Character.isWhitespace(line.charAt(0))) { owner = parts[0]; classes.put(owner, parts[1]); }
            else if (parts.length == 2) fields.put(owner + "/" + parts[0], parts[1]);
            else methods.put(owner + "/" + parts[0] + parts[1], parts[2]);
        }
        ClassWriter result = new ClassWriter(0);
        new ClassReader(original).accept(new ClassRemapper(result, new Remapper() {
            @Override public String map(String name) { return classes.containsKey(name) ? classes.get(name) : name; }
            @Override public String mapFieldName(String owner, String name, String desc) {
                String mapped = fields.get(owner + "/" + name); return mapped == null ? name : mapped;
            }
            @Override public String mapMethodName(String owner, String name, String desc) {
                String mapped = methods.get(owner + "/" + name + desc); return mapped == null ? name : mapped;
            }
        }), 0);
        return result.toByteArray();
    }
    private static byte[] readJar(String path, String name) throws IOException {
        try (JarFile jar = new JarFile(path); InputStream input = jar.getInputStream(jar.getJarEntry(name + ".class"))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] block = new byte[8192];
            for (int n; (n = input.read(block)) != -1;) out.write(block, 0, n); return out.toByteArray();
        }
    }
    private static float[][] coordinates() { return new float[][]{{-.1F,-.1F},{.1F,-.1F},{.1F,.1F},{-.1F,.1F}}; }
    private static byte[] quad() {
        ByteBuffer buffer = ByteBuffer.allocate(96).order(ByteOrder.nativeOrder());
        for (float[] xy : coordinates()) {
            buffer.putFloat(xy[0]).putFloat(xy[1]).putFloat(0).putFloat(0).putFloat(0);
            buffer.put((byte) 0).put((byte) 0).put((byte) 127).put((byte) 0);
        }
        return buffer.array();
    }
}
