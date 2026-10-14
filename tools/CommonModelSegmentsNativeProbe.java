package dev.rlcraft.ice.hooks;

import dev.rlcraft.ice.optimizer.*;
import dev.rlcraft.ice.optimizer.client.*;
import dev.rlcraft.ice.optimizer.compat.gl.*;
import dev.rlcraft.ice.optimizer.compat.model.*;
import dev.rlcraft.ice.optimizer.compat.renderlib.RenderLibRenderBridge;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.backend.*;
import dev.rlcraft.ice.optimizer.render.entity.*;
import dev.rlcraft.ice.optimizer.render.frame.*;
import dev.rlcraft.ice.optimizer.render.resource.*;
import dev.rlcraft.ice.optimizer.render.telemetry.CorrelatedRenderProfiler;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.io.*;
import java.lang.reflect.*;
import java.lang.reflect.Method;
import java.nio.*;
import java.util.*;
import java.util.jar.*;
import net.minecraft.client.model.*;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import org.objectweb.asm.*;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;

/** Actual common-model branch bytecode and production segment bridge, compared to native lists. */
public final class CommonModelSegmentsNativeProbe {
    private static final int SIZE = 128;
    private static final String BIPED = CommonModelStateBatchAdapter.BIPED;
    private static final String QUAD = CommonModelStateBatchAdapter.QUADRUPED;
    private static Class<?> matrix;
    private static LwjglModelMeshCache cache;
    private static FrameStamp stamp;
    private static boolean modern;
    private static int callerVao, callerElement, list, callbackIndex, mutationIndex, failIndex;
    private static int nativeFallbacks, batchedParts, calls, preflights, blockedMode, lastDepth;
    private static final float[] lastMatrix = new float[16];
    private static ModelBase current;
    private static final List<Integer> order = new ArrayList<Integer>();
    private static final RuntimeException FAILURE = new IllegalStateException("original part failure");

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("forge-srg client optifine notch-srg");
        System.setProperty("ice.minecraft.client.jar", args[1]);
        System.setProperty("ice.optifine.jar", args[2]);
        System.setProperty("ice.notch.srg", args[3]);
        Pbuffer context = new Pbuffer(SIZE, SIZE, new PixelFormat(), null, null);
        try {
            context.makeCurrent();
            exercise(read(args[0], BIPED), read(args[0], QUAD), read(args[0], ModelRendererVboAdapter.GL_STATE_MANAGER), "Forge");
            try (OptifinePatchedClassSupport support = OptifinePatchedClassSupport.openOrSkip()) {
                exercise(support.patchAndRemap("bpx", BIPED.replace('/', '.')),
                    support.patchAndRemap("bqm", QUAD.replace('/', '.')),
                    support.patchAndRemap("bus", ModelRendererVboAdapter.GL_STATE_MANAGER.replace('/', '.')), "G5");
            }
            System.out.println("common_model_segments_native PASS real_forge_g5=true pixels_equal=true scope_restored=true");
        } finally { context.destroy(); }
    }

    private static void exercise(byte[] biped, byte[] quadruped, byte[] gsm, String name) throws Exception {
        Method wrappers = ModelPoseBoundaryProbe.class.getDeclaredMethod("wrappers", byte[].class, String.class, boolean.class);
        wrappers.setAccessible(true);
        matrix = (Class<?>) wrappers.invoke(null, gsm, name + "Segments", true);
        RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        CacheBudget budget = new CacheBudget(4 << 20, 4 << 20, 8 << 20);
        ResourceLedger ledger = new ResourceLedger(guard, budget, new ResourceLedger.Destroyer() {
            public void destroy(RenderResourceKind kind, int id) {
                if (kind == RenderResourceKind.BUFFER) GL15.glDeleteBuffers(id);
                else if (kind == RenderResourceKind.VERTEX_ARRAY) GL30.glDeleteVertexArrays(id);
                else if (kind == RenderResourceKind.PROGRAM) GL20.glDeleteProgram(id);
                else if (kind == RenderResourceKind.SHADER) GL20.glDeleteShader(id);
            }
        }, 128);
        cache = new LwjglModelMeshCache(guard, ledger, budget);
        ClientEpochs epochs = new ClientEpochs(); epochs.nextFrame();
        stamp = new FrameStamp(1, 1, epochs.snapshot());
        installRuntime(epochs, budget, guard);
        list = GL11.glGenLists(1);
        GL11.glNewList(list, GL11.GL_COMPILE); GL11.glBegin(GL11.GL_QUADS);
        for (float[] xy : xy()) { GL11.glNormal3f(0, 0, 1); GL11.glTexCoord2f(0, 0); GL11.glVertex3f(xy[0], xy[1], 0); }
        GL11.glEnd(); GL11.glEndList();
        callerVao = GL30.glGenVertexArrays(); GL30.glBindVertexArray(callerVao);
        callerElement = GL15.glGenBuffers(); GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, callerElement);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, 16, GL15.GL_STATIC_DRAW);
        GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
        GL13.glClientActiveTexture(GL13.GL_TEXTURE1); GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
        try {
            for (int i = 0; i < 64; i++) {
                seed();
                require(cache.accept(new ModelMeshPayload(100 + i, quad(), DefaultVertexFormats.POSITION_TEX_NORMAL,
                    GL11.GL_QUADS, 4, 1, 1), stamp), "upload");
            }
            for (int kind : new int[]{1, 2}) {
                byte[] source = kind == 1 ? biped : quadruped;
                Class<?> vanilla = fixture(source, false, name + kind + "Legacy");
                Class<?> adapted = fixture(source, true, name + kind + "Segments");
                for (boolean child : new boolean[]{false, true}) for (boolean pose : new boolean[]{false, true}) {
                    blockedMode = 0;
                    for (int bad : new int[]{-1, 0, 2, kind == 1 ? 6 : 5}) {
                        byte[] expected = draw(vanilla, kind, child, pose, bad, false, -1);
                        List<Integer> expectedOrder = new ArrayList<Integer>(order);
                        long starts = cache.getStateBatchBegins(), draws = cache.getPoseBatchDraws();
                        byte[] actual = draw(adapted, kind, child, pose, bad, false, -1);
                        require(Arrays.equals(expected, actual), "pixels " + name + " kind=" + kind + " child=" + child + " pose=" + pose + " bad=" + bad);
                        require(order.equals(expectedOrder), "draw order");
                        int roots = kind == 1 ? 7 : 6;
                        int segments = bad < 0 || bad == 0 || bad == roots - 1 ? 1 : 2;
                        require(cache.getStateBatchBegins() - starts == segments, "segment begins");
                        require(preflights == roots * 4 - (bad >= 0 ? 3 : 0), "tree checked more than once: " + preflights);
                        require(batchedParts == (roots - (bad >= 0 ? 1 : 0)) * 4, "usable roots lost");
                        if (pose) require(cache.getPoseBatchDraws() - draws == segments, "palette segments actual="
                            + (cache.getPoseBatchDraws() - draws) + " expected=" + segments + " detail=" + cache.getPoseBatchDetail()
                            + " kind=" + kind + " child=" + child + " bad=" + bad);
                        if (!child && bad == 2) System.out.println(name + " kind=" + kind + " pose=" + pose
                            + " roots=" + roots + " bad_root=2 batches=" + segments + " batch_parts=" + batchedParts
                            + " native_draws=" + (pose ? segments + nativeFallbacks : roots * 4) + " pixel_error=0 preflight_visits=" + preflights);
                    }
                    byte[] expected = draw(vanilla, kind, child, pose, 2, true, -1);
                    List<Integer> expectedOrder = new ArrayList<Integer>(order);
                    byte[] actual = draw(adapted, kind, child, pose, 2, true, -1);
                    require(Arrays.equals(expected, actual) && order.equals(expectedOrder), "callback mutation stale segment");
                    require(!order.contains(Integer.valueOf(12)) && order.contains(Integer.valueOf(36))
                        && !order.contains(Integer.valueOf(16)), "hidden/replaced future root rendered from stale plan");
                    for (int failed : new int[]{1, 2, 4}) {
                        expected = draw(vanilla, kind, child, pose, 2, false, failed);
                        int expectedDepth = lastDepth;
                        float[] expectedMatrix = lastMatrix.clone();
                        actual = draw(adapted, kind, child, pose, 2, false, failed);
                        require(Arrays.equals(expected, actual), "exception partially submitted pixels");
                        require(expectedDepth == lastDepth, "exception matrix stack");
                        for (int i = 0; i < 16; i++) require(Math.abs(expectedMatrix[i] - lastMatrix[i]) < .000001F,
                            "exception current matrix");
                        require(calls == failed * 4 + 1, "exception replay or continuation");
                    }
                    // A failure partway down a root must discard only that root's
                    // partial entries. Earlier complete roots remain batched.
                    for (blockedMode = 1; blockedMode <= 2; blockedMode++) {
                        expected = draw(vanilla, kind, child, pose, 2, false, -1);
                        actual = draw(adapted, kind, child, pose, 2, false, -1);
                        require(Arrays.equals(expected, actual), "partial-tree rollback pixels");
                        require(batchedParts == ((kind == 1 ? 7 : 6) - 1) * 4, "partial root leaked into batch");
                        require(preflights == (kind == 1 ? 7 : 6) * 4 - 1, "partial root rescanned");
                    }
                }
            }
        } finally {
            ModelStateBatchBridge.end();
            cache.close(true); ledger.destroyAll(1); GL11.glDeleteLists(list, 1);
            GL30.glBindVertexArray(0); GL30.glDeleteVertexArrays(callerVao); GL15.glDeleteBuffers(callerElement);
        }
        require(budget.snapshot().getGpuUsed() == 0 && budget.snapshot().getDirectUsed() == 0, "budget leak");
    }

    private static byte[] draw(Class<?> type, int kind, boolean child, boolean pose, int bad, boolean mutate, int fail) throws Exception {
        ModelBase model = (ModelBase) type.newInstance(); current = model;
        modern = type.getName().contains("Segments"); callbackIndex = bad; mutationIndex = mutate ? bad : -1; failIndex = fail;
        int roots = kind == 1 ? 7 : 6;
        for (int i = 0; i < roots; i++) setRoot(model, kind, i, tree(model, i));
        model.isChild = child;
        calls = preflights = batchedParts = nativeFallbacks = 0; order.clear();
        cache.configurePoseBatching(pose);
        EarlyMatrixStateTracker.invalidate(); EarlyModelLightingState.invalidate();
        GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glLoadIdentity(); GL11.glOrtho(-1, 2, -1, 3, -1, 1);
        GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glLoadIdentity();
        GL11.glViewport(0, 0, SIZE, SIZE); GL20.glUseProgram(0);
        GL11.glDisable(GL11.GL_TEXTURE_2D); GL11.glDisable(GL11.GL_LIGHTING); GL11.glDisable(GL11.GL_FOG);
        GL11.glDisable(GL11.GL_CULL_FACE); GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glEnable(GL11.GL_BLEND); GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(.2F, .8F, .3F, .6F); GL11.glClearColor(0, 0, 0, 0); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        seed();
        int depth = GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH);
        try { type.getMethod("draw", Entity.class, float.class, float.class, float.class, float.class, float.class, float.class)
            .invoke(model, new Entity(), 0F, 0F, 0F, 0F, 0F, .0625F);
            require(fail < 0, "lost original failure");
        } catch (InvocationTargetException failure) { require(fail >= 0 && failure.getCause() == FAILURE, "unexpected draw error: " + failure.getCause()); }
        require(!ModelStateBatchBridge.isActive(), "scope leaked");
        checkCaller();
        lastDepth = GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH);
        FloatBuffer currentMatrix = BufferUtils.createFloatBuffer(16);
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, currentMatrix); currentMatrix.get(lastMatrix);
        // The original model leaves its matrix push outstanding on an exception;
        // compare successful calls, and unwind only in this test's cleanup.
        if (fail < 0) require(GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH) == depth, "matrix stack changed");
        while (GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH) > depth) GL11.glPopMatrix();
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "native error");
        ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        GL11.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        byte[] data = new byte[pixels.remaining()]; pixels.get(data); return data;
    }

    public static final class Entity { public boolean sneak() { return false; } }

    public static final class Part extends ModelRenderer implements ModelStateBatchAccess, ModelStateBatchSubclassAccess {
        final int index;
        Part(ModelBase model, int index) { super(model); this.index = index; }
        public boolean ice$stateBatchNodeReady() { return true; }
        public int ice$stateBatchDisplayList() { return blockedMode == 2 && index == callbackIndex * 4 + 2 ? 900 : 100 + index; }
        public boolean ice$stateBatchRenderClassReady() {
            preflights++;
            return callbackIndex < 0 || blockedMode == 2 || index != callbackIndex * 4 + (blockedMode == 1 ? 2 : 0);
        }
        @Override public void render(float scale) {
            calls++;
            if (failIndex >= 0 && index == failIndex * 4) throw FAILURE;
            if (isHidden || !showModel) return;
            order.add(Integer.valueOf(index));
            try {
                if (callbackIndex >= 0 && index == callbackIndex * 4) {
                    require(!ModelStateBatchBridge.isActive(), "unknown root entered held state"); checkCaller();
                    GL11.glColor4f(.9F, .1F, .2F, .6F); seed();
                    if (mutationIndex >= 0 && index == mutationIndex * 4) {
                        int kind = current instanceof ModelBiped ? 1 : 2;
                        root(current, kind, 3).showModel = false;
                        // The future root field itself changes, not just old-object visibility.
                        setRoot(current, kind, 4, tree(current, 9));
                    }
                }
                call("func_179094_E"); call("func_179109_b", index * .019F, index * .008F, 0F);
                if (modern && ModelStateBatchBridge.draw(100 + index)) batchedParts++;
                else { GL11.glCallList(list); nativeFallbacks++; }
                if (childModels != null) for (ModelRenderer child : childModels) child.render(scale);
                call("func_179121_F");
                if (callbackIndex >= 0 && index == callbackIndex * 4) { GL11.glColor4f(.2F, .3F, .9F, .6F); seed(); }
            } catch (RuntimeException e) { throw e; } catch (Exception e) { throw new AssertionError(e); }
        }
    }

    private static Part tree(ModelBase model, int root) {
        Part part = new Part(model, root * 4);
        for (int i = 1; i < 4; i++) part.addChild(new Part(model, root * 4 + i));
        return part;
    }

    private static void checkCaller() {
        require(GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING) == callerVao, "caller VAO");
        require(GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING) == callerElement, "caller element buffer");
        require(GL11.glIsEnabled(GL11.GL_COLOR_ARRAY), "caller color array");
        GL13.glClientActiveTexture(GL13.GL_TEXTURE1);
        require(GL11.glIsEnabled(GL11.GL_TEXTURE_COORD_ARRAY), "caller texture1 array");
        GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
    }

    private static ModelRenderer root(ModelBase model, int kind, int index) throws Exception {
        String[] names = kind == 1 ? new String[]{"bipedHead", "bipedBody", "bipedRightArm", "bipedLeftArm", "bipedRightLeg", "bipedLeftLeg", "bipedHeadwear"}
            : new String[]{"head", "body", "leg1", "leg2", "leg3", "leg4"};
        return (ModelRenderer) model.getClass().getField(names[index]).get(model);
    }
    private static void setRoot(ModelBase model, int kind, int index, Part value) throws Exception {
        String[] names = kind == 1 ? new String[]{"bipedHead", "bipedBody", "bipedRightArm", "bipedLeftArm", "bipedRightLeg", "bipedLeftLeg", "bipedHeadwear"}
            : new String[]{"head", "body", "leg1", "leg2", "leg3", "leg4"};
        model.getClass().getField(names[index]).set(model, value);
    }

    private static Class<?> fixture(byte[] bytes, boolean batch, String label) throws Exception {
        ClassNode source = new ClassNode(Opcodes.ASM5); new ClassReader(bytes).accept(source, ClassReader.EXPAND_FRAMES);
        String owner = source.name;
        if (batch) bytes = new CommonModelStateBatchAdapter().transform(owner, bytes,
            new TargetSpec(owner, "modern-entity-backend", "modern-common-model-state-batch", Collections.<String>emptySet()));
        source = new ClassNode(Opcodes.ASM5); new ClassReader(bytes).accept(source, ClassReader.EXPAND_FRAMES);
        MethodNode render = ModelStateBatchMethodProof.method(source, CommonModelStateBatchAdapter.RENDER, CommonModelStateBatchAdapter.RENDER_DESCRIPTOR);
        final String generated = "dev/rlcraft/ice/hooks/CommonSegments" + label;
        final Map<String, String> names = new HashMap<String, String>();
        String[] srg = {"field_78116_c", "field_78115_e", "field_178723_h", "field_178724_i", "field_178721_j", "field_178722_k", "field_178720_f",
            "field_78150_a", "field_78148_b", "field_78149_c", "field_78146_d", "field_78147_e", "field_78144_f", "field_78091_s", "field_78145_g", "field_78151_h"};
        String[] mcp = {"bipedHead", "bipedBody", "bipedRightArm", "bipedLeftArm", "bipedRightLeg", "bipedLeftLeg", "bipedHeadwear",
            "head", "body", "leg1", "leg2", "leg3", "leg4", "isChild", "childYOffset", "childZOffset"};
        for (int i = 0; i < srg.length; i++) names.put(srg[i], mcp[i]);
        // OptiFine emits the inherited ModelBase field with the concrete owner.
        names.put("q", "isChild");
        ClassNode minimal = new ClassNode(Opcodes.ASM5);
        minimal.version = Opcodes.V1_8; minimal.access = Opcodes.ACC_PUBLIC; minimal.name = owner; minimal.superName = "java/lang/Object";
        minimal.methods.add(render);
        MethodNode animation = new MethodNode(Opcodes.ACC_PUBLIC, "func_78087_a", "(FFFFFFLnet/minecraft/entity/Entity;)V", null, null);
        animation.instructions.add(new InsnNode(Opcodes.RETURN)); minimal.methods.add(animation);
        final String originalOwner = owner;
        ClassNode mapped = new ClassNode(Opcodes.ASM5);
        minimal.accept(new ClassRemapper(mapped, new Remapper() {
            @Override public String map(String name) {
                if (name.equals(originalOwner)) return generated;
                if (name.equals("net/minecraft/entity/Entity")) return Type.getInternalName(Entity.class);
                if (name.equals(ModelRendererVboAdapter.GL_STATE_MANAGER)) return Type.getInternalName(matrix);
                return name;
            }
            @Override public String mapFieldName(String owner, String name, String desc) { return names.containsKey(name) ? names.get(name) : name; }
            @Override public String mapMethodName(String owner, String name, String desc) {
                if (name.equals("func_78088_a")) return "draw";
                if (name.equals("func_78087_a")) return "animate";
                if (name.equals("func_78785_a")) return "render";
                if (name.equals("func_70093_af")) return "sneak";
                return name;
            }
        }));
        mapped.superName = owner;
        MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        if (QUAD.equals(owner)) { init.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 12)); init.instructions.add(new InsnNode(Opcodes.FCONST_0)); }
        init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, "<init>", QUAD.equals(owner) ? "(IF)V" : "()V", false));
        init.instructions.add(new InsnNode(Opcodes.RETURN)); mapped.methods.add(init);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); mapped.accept(writer); final byte[] executable = writer.toByteArray();
        return new ClassLoader(matrix.getClassLoader()) { Class<?> define() { return defineClass(generated.replace('/', '.'), executable, 0, executable.length); } }.define();
    }

    private static void installRuntime(ClientEpochs epochs, CacheBudget budget, RenderThreadGuard guard) throws Exception {
        Constructor<ModernRendererRuntime> ctor = ModernRendererRuntime.class.getDeclaredConstructor(ClientEpochs.class, CacheBudget.class); ctor.setAccessible(true);
        ModernRendererRuntime runtime = ctor.newInstance(epochs, budget);
        field(ModernRendererRuntime.class, "initialized").setBoolean(runtime, true);
        field(ModernRendererRuntime.class, "currentStamp").set(runtime, stamp);
        Object components = field(ModernRendererRuntime.class, "components").get(runtime);
        field(components.getClass(), "threadGuard").set(components, guard);
        field(components.getClass(), "modelMeshes").set(components, cache);
        field(components.getClass(), "profiler").set(components, new CorrelatedRenderProfiler(4));
        Object shader = field(ModernRendererRuntime.class, "shaderRuntime").get(runtime);
        field(shader.getClass(), "shaderPackStateKnown").setBoolean(shader, true);
        field(ClientOptimizerRuntime.class, "modernRenderer").set(ClientOptimizerRuntime.INSTANCE, runtime);
        AdaptiveBackendController backend = new AdaptiveBackendController(OptimizerRegistry.breaker(OptimizationModule.MODERN_ENTITY_BACKEND), EnumSet.noneOf(ModernCapability.class));
        field(AdaptiveBackendController.class, "state").set(backend, BackendLifecycleState.PAIRED_MEASURE);
        ((Map) field(components.getClass(), "backends").get(components)).put(OptimizationModule.MODERN_ENTITY_BACKEND, backend);
        Constructor<?> sampleCtor = ModernRendererRuntime.RenderBackendSample.class.getDeclaredConstructors()[0]; sampleCtor.setAccessible(true);
        Object sample = sampleCtor.newInstance(OptimizationModule.MODERN_ENTITY_BACKEND, BackendLifecycleState.PAIRED_MEASURE, MeasurementArm.MODERN, null, null, null, 0L, 1L);
        ThreadLocal<?> stacks = (ThreadLocal<?>) field(RenderLibRenderBridge.class, "TRAVERSALS").get(null); stacks.remove(); Object stack = stacks.get();
        Object traversal = ((Object[]) field(stack.getClass(), "values").get(stack))[0]; field(stack.getClass(), "size").setInt(stack, 1);
        field(traversal.getClass(), "module").set(traversal, OptimizationModule.MODERN_ENTITY_BACKEND);
        field(traversal.getClass(), "objectPass").set(traversal, RenderPass.ENTITY_PASS_0);
        field(traversal.getClass(), "objectActive").setBoolean(traversal, true);
        field(traversal.getClass(), "objectCandidateAllowed").setBoolean(traversal, true);
        field(traversal.getClass(), "sample").set(traversal, sample);
    }
    private static void seed() throws Exception { Method seed = ModelPoseBoundaryProbe.class.getDeclaredMethod("seedGl"); seed.setAccessible(true); seed.invoke(null); }
    private static void call(String name, Object... args) throws Exception { for (Method m : matrix.getMethods()) if (m.getName().equals(name)) { m.invoke(null, args); return; } throw new AssertionError(name); }
    private static byte[] quad() { ByteBuffer b = ByteBuffer.allocate(96).order(ByteOrder.nativeOrder()); for (float[] p : xy()) b.putFloat(p[0]).putFloat(p[1]).putFloat(0).putFloat(0).putFloat(0).put((byte) 0).put((byte) 0).put((byte) 127).put((byte) 0); return b.array(); }
    private static float[][] xy() { return new float[][]{{-.5F, -.5F}, {.5F, -.5F}, {.5F, .5F}, {-.5F, .5F}}; }
    private static Field field(Class<?> type, String name) throws Exception { Field f = type.getDeclaredField(name); f.setAccessible(true); return f; }
    private static byte[] read(String path, String owner) throws Exception { try (JarFile jar = new JarFile(path); InputStream in = jar.getInputStream(jar.getJarEntry(owner + ".class"))) { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192]; for (int n; (n = in.read(b)) >= 0;) out.write(b, 0, n); return out.toByteArray(); } }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
