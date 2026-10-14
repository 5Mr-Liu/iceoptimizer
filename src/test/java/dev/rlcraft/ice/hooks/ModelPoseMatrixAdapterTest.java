package dev.rlcraft.ice.hooks;

import static org.junit.Assert.*;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyMatrixStateTracker;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyModelBatchBarrier;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyModelLightingState;
import java.nio.FloatBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** Executes transformed wrappers against native-call stubs, including both branch exits. */
public final class ModelPoseMatrixAdapterTest {
    private static final String OWNER = GlStateTrackingAdapter.GL_STATE_MANAGER;
    private static final String FIXTURE = "dev/rlcraft/ice/hooks/ModelPoseMatrixFixture";
    private Class<?> fixture;
    private final List<String> events = new ArrayList<String>();

    @Before public void setup() throws Exception {
        EarlyModelBatchBarrier.leave();
        FloatBuffer identity = FloatBuffer.wrap(new float[] {1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1});
        EarlyMatrixStateTracker.seed(5888, identity, identity);
        NativeStub.calls = NativeStub.pushes = NativeStub.pops = 0;
        BooleanStub.events = events;
        fixture = transformedFixture();
    }
    @After public void cleanup() { EarlyModelBatchBarrier.leave(); BooleanStub.events = null; }

    @Test public void ordinaryAndDeferredBranchesEachApplyExactlyOneTransform() throws Exception {
        translate(2F, 0F, 0F);
        assertEquals(1, NativeStub.calls);
        assertEquals(2F, EarlyMatrixStateTracker.modelView()[12], 0F);
        enter();
        translate(3F, 0F, 0F);
        assertEquals(1, NativeStub.calls);
        assertEquals(5F, EarlyMatrixStateTracker.modelView()[12], 0F);
        fixture.getMethod("func_179139_a", double.class, double.class, double.class)
            .invoke(null, 2D, 1D, 1D);
        assertEquals(2F, EarlyMatrixStateTracker.modelView()[0], 0F);
        assertEquals(1, NativeStub.calls);
        EarlyModelBatchBarrier.barrier();
        assertEquals(Arrays.asList("flush", "sync:0:5.0"), events);
    }

    @Test public void deferredStackAvoidsNativePushPopAndCrossScopePopResumesOrdinaryPath() throws Exception {
        invoke("func_179094_E");
        translate(2F, 0F, 0F);
        assertEquals(1, EarlyMatrixStateTracker.modelViewDepth());
        enter();
        invoke("func_179094_E");
        translate(3F, 0F, 0F);
        assertEquals(2, EarlyMatrixStateTracker.modelViewDepth());
        assertEquals(1, NativeStub.pushes);
        invoke("func_179121_F");
        assertEquals(1, EarlyMatrixStateTracker.modelViewDepth());
        assertEquals(0, NativeStub.pops);
        // The next pop belongs to the caller and must materialize before native pop.
        invoke("func_179121_F");
        assertEquals(0, EarlyMatrixStateTracker.modelViewDepth());
        assertEquals(1, NativeStub.pops);
        assertEquals(Arrays.asList("flush", "sync:1:2.0"), events);
        translate(4F, 0F, 0F);
        assertEquals(4F, EarlyMatrixStateTracker.modelView()[12], 0F);
        assertEquals(2, NativeStub.calls);
    }

    @Test public void invalidTransformFlushesOldValidPoseBeforeOrdinaryNativeCall() throws Exception {
        enter();
        translate(7F, 0F, 0F);
        translate(Float.POSITIVE_INFINITY, 0F, 0F);
        assertFalse(EarlyMatrixStateTracker.isKnown());
        assertEquals(1, NativeStub.calls);
        // The ordinary branch's matrix barrier may flush again after the
        // invalidation, but must not try to materialize an invalid matrix.
        assertEquals(Arrays.asList("flush", "sync:0:7.0", "flush"), events);
    }

    @Test public void booleanStateDelegationFlushesBeforeTheActualCapabilityChanges() throws Exception {
        enter();
        translate(2F, 0F, 0F);
        invoke("func_179147_l");
        assertEquals(Arrays.asList("flush", "sync:0:2.0", "enable"), events);
    }

    @Test public void matrixMultiplyWrapperDefersOnceAndKeepsNativeOutsidePoseOrModelView() throws Exception {
        FloatBuffer source = matrix(4);
        source.put(16, 2F); source.mark();
        multiply(source);
        assertEquals(1, NativeStub.calls);
        assertEquals(2F, EarlyMatrixStateTracker.modelView()[12], 0F);
        enter();
        for (int i = 0; i < 32; i++) multiply(source.asReadOnlyBuffer());
        assertEquals(1, NativeStub.calls);
        assertEquals(66F, EarlyMatrixStateTracker.modelView()[12], 0F);
        assertTrue(events.isEmpty());
        source.reset();
        assertEquals(4, source.position()); assertEquals(20, source.limit());
        EarlyMatrixStateTracker.matrixMode(5889);
        multiply(source);
        assertEquals(2, NativeStub.calls);
        assertEquals(2F, EarlyMatrixStateTracker.snapshot().getProjection()[12], 0F);
        assertEquals(Arrays.asList("flush", "sync:0:66.0"), events);
    }

    @Test public void invalidMatrixBuffersKeepOriginalLwjglExceptionsAndFlushPriorPose() throws Exception {
        enter();
        translate(7F, 0F, 0F);
        FloatBuffer shortBuffer = matrix(3); shortBuffer.limit(18);
        for (FloatBuffer source : new FloatBuffer[] {null, FloatBuffer.allocate(16), shortBuffer}) {
            try { multiply(source); fail("original LWJGL buffer validation skipped"); }
            catch (InvocationTargetException expected) {
                assertTrue(expected.getCause() instanceof NullPointerException
                    || expected.getCause() instanceof IllegalArgumentException);
            }
            assertEquals(0, NativeStub.calls);
            assertEquals(7F, EarlyMatrixStateTracker.modelView()[12], 0F);
        }
        assertEquals(Arrays.asList("flush", "sync:0:7.0", "flush", "flush"), events);
    }

    private static FloatBuffer matrix(int offset) {
        FloatBuffer result = ByteBuffer.allocateDirect((offset + 20) * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
        for (int i = 0; i < 16; i++) result.put(offset + i, i % 5 == 0 ? 1F : 0F);
        result.position(offset); result.limit(offset + 16);
        return result;
    }

    private void multiply(FloatBuffer matrix) throws Exception {
        fixture.getMethod("func_179110_a", FloatBuffer.class).invoke(null, matrix);
    }

    @Test public void lightingWrapperUsesItsBooleanStateBarrierOnceAndThenPublishes() throws Exception {
        int[] lighting = new int[EarlyModelLightingState.SIZE];
        EarlyModelLightingState.seed(lighting, new int[8], 0);
        enter();
        translate(2F, 0F, 0F);
        invoke("func_179108_z");
        assertEquals(Arrays.asList("flush", "sync:0:2.0", "enable"), events);
        assertTrue(EarlyModelLightingState.copyTo(lighting));
        assertEquals(1, lighting[EarlyModelLightingState.NORMALIZE]);
    }

    private void enter() {
        EarlyModelBatchBarrier.enter(() -> {
            events.add("flush");
            EarlyModelBatchBarrier.synchronize();
        }, () -> events.add("sync:" + EarlyMatrixStateTracker.modelViewDepth()
            + ":" + EarlyMatrixStateTracker.modelView()[12]));
    }
    private void translate(float x, float y, float z) throws Exception {
        fixture.getMethod("func_179109_b", float.class, float.class, float.class).invoke(null, x, y, z);
    }
    private void invoke(String name) throws Exception { fixture.getMethod(name).invoke(null); }

    private static Class<?> transformedFixture() throws Exception {
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(GlStateTrackingAdapterTest.syntheticManager(OWNER)).accept(node, 0);
        node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "func_179108_z", "()V", null, null));
        for (MethodNode method : node.methods) {
            String nativeName = method.name.equals("func_179109_b") ? "glTranslatef"
                : method.name.equals("func_179139_a") ? "glScaled"
                : method.name.equals("func_179094_E") ? "glPushMatrix"
                : method.name.equals("func_179121_F") ? "glPopMatrix"
                : method.name.equals("func_179110_a") ? "glMultMatrix" : null;
            if (nativeName != null) {
                method.instructions.clear();
                int local = 0;
                for (Type argument : Type.getArgumentTypes(method.desc)) {
                    method.instructions.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), local));
                    local += argument.getSize();
                }
                method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "org/lwjgl/opengl/GL11", nativeName, method.desc, false));
                method.instructions.add(new InsnNode(Opcodes.RETURN));
            } else if (method.name.equals("func_179147_l") || method.name.equals("func_179108_z")) {
                method.instructions.clear();
                method.instructions.add(new TypeInsnNode(Opcodes.NEW, OWNER + "$BooleanState"));
                method.instructions.add(new InsnNode(Opcodes.DUP));
                method.instructions.add(new IntInsnNode(Opcodes.SIPUSH,
                    method.name.equals("func_179147_l") ? 3042 : 2977));
                method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                    OWNER + "$BooleanState", "<init>", "(I)V", false));
                method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    OWNER + "$BooleanState", "setEnabled", "()V", false));
                method.instructions.add(new InsnNode(Opcodes.RETURN));
            }
        }
        ClassWriter original = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(original);
        byte[] adapted = new GlStateTrackingAdapter(GlStateTrackingAdapter.Part.GL_STATE_MANAGER)
            .transform(OWNER, original.toByteArray(), new TargetSpec(OWNER,"modern-entity-backend","test",Collections.<String>emptySet()));
        ClassNode verified = new ClassNode(Opcodes.ASM5);
        new ClassReader(adapted).accept(verified, ClassReader.EXPAND_FRAMES);
        for (MethodNode method : verified.methods)
            new Analyzer<BasicValue>(new BasicVerifier()).analyze(verified.name, method);
        Map<String,String> names = new HashMap<String,String>();
        names.put(OWNER, FIXTURE);
        names.put(OWNER + "$BooleanState", Type.getInternalName(BooleanStub.class));
        names.put("org/lwjgl/opengl/GL11", Type.getInternalName(NativeStub.class));
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(adapted).accept(new ClassRemapper(writer, new SimpleRemapper(names)), 0);
        byte[] bytes = writer.toByteArray();
        return new ClassLoader(ModelPoseMatrixAdapterTest.class.getClassLoader()) {
            Class<?> define() { return defineClass(FIXTURE.replace('/', '.'), bytes, 0, bytes.length); }
        }.define();
    }

    public static final class NativeStub {
        static int calls, pushes, pops;
        public static void glTranslatef(float x,float y,float z) { calls++; }
        public static void glScaled(double x,double y,double z) { calls++; }
        public static void glPushMatrix() { pushes++; }
        public static void glPopMatrix() { pops++; }
        public static void glMultMatrix(FloatBuffer matrix) {
            org.lwjgl.BufferChecks.checkBuffer(matrix, 16);
            calls++;
        }
    }
    public static final class BooleanStub {
        static List<String> events;
        public BooleanStub(int capability) { }
        public void setEnabled() { events.add("enable"); }
    }
}
