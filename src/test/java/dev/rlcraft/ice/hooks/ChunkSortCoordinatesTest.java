package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import dev.rlcraft.ice.optimizer.compat.chunk.ChunkBufferAccessor;
import dev.rlcraft.ice.optimizer.compat.chunk.ChunkPrimitiveSortBridge;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Executes the installed wrapper, including its world-to-buffer conversion. */
public final class ChunkSortCoordinatesTest {
    private static final String OWNER = VanillaChunkRenderAdapter.BUFFER_BUILDER;
    private static final String[] OFFSETS = {"field_179004_l", "field_179005_m", "field_179002_n"};
    public static boolean accept;
    public static boolean sort;
    public static float[] received;

    @Test public void wrapperConvertsEachAxisWithVanillaDoubleAddition() throws Exception {
        Object buffer = fixture();
        accept = true; sort = false;
        for (double[] offset : new double[][] {
                {0, 0, 0}, {1488, -64, -2304}, {-512, -256, 1024},
                {-30000000.75, 0.0000001, 30000000.25}}) {
            float[] world = {30000000F, 72.125F, -29999998F};
            setOffsets(buffer, offset);
            invoke(buffer, world);
            for (int axis = 0; axis < 3; axis++)
                assertEquals(Float.floatToRawIntBits((float) ((double) world[axis] + offset[axis])),
                    Float.floatToRawIntBits(received[axis]));
        }
    }

    @Test public void pausedOrRejectedSortReceivesOriginalWorldCoordinates() throws Exception {
        Object buffer = fixture();
        accept = false; sort = false;
        setOffsets(buffer, new double[] {1488, -64, -2304});
        float[] world = {-1480.25F, 72.125F, 2311.5F};
        invoke(buffer, world);
        assertArrayEquals(world, (float[]) buffer.getClass().getField("originalArguments").get(buffer), 0F);
    }

    @Test public void translatedChunksAndRegionsMatchActualVanillaSortByteForByte() throws Exception {
        Object buffer = fixture();
        accept = true; sort = true;
        Random random = new Random(0x1CE20261020L);
        for (int stride : new int[] {7, 14}) {
            VertexFormat format = new VertexFormat(DefaultVertexFormats.BLOCK);
            if (stride == 14) format.addElement(new VertexFormatElement(0,
                VertexFormatElement.EnumType.FLOAT, VertexFormatElement.EnumUsage.PADDING, 7));
            assertEquals(stride, format.getIntegerSize());
            for (int quads : new int[] {8, 511, 512, 513, 2048})
            for (double[] offset : new double[][] {
                    {1488, -64, -2304}, {-512, -256, 1024}, {-30000000, -240, 30000000}}) {
                int[] raw = new int[quads * 4 * stride];
                for (int q = 0; q < quads; q++) {
                    float x = random.nextInt(16), y = random.nextInt(16), z = random.nextInt(16);
                    for (int v = 0; v < 4; v++) {
                        int p = (q * 4 + v) * stride;
                        raw[p] = Float.floatToRawIntBits(x + (v == 1 || v == 2 ? 1 : 0));
                        raw[p + 1] = Float.floatToRawIntBits(y);
                        raw[p + 2] = Float.floatToRawIntBits(z + (v >= 2 ? 1 : 0));
                        for (int e = 3; e < stride; e++) raw[p + e] = random.nextInt();
                    }
                }
                // Compare to Minecraft's real implementation, including translation;
                // a second copy of ICE's distance calculation would miss this bug.
                float[] world = {(float) (8.25 - offset[0]), (float) (20.125 - offset[1]),
                    (float) (7.75 - offset[2])};
                BufferBuilder vanilla = new BufferBuilder(raw.length * 4);
                vanilla.begin(7, format);
                vanilla.setVertexState(vanilla.new State(raw.clone(), format));
                vanilla.setTranslation(offset[0], offset[1], offset[2]);
                vanilla.sortVertexData(world[0], world[1], world[2]);
                ByteBuffer bytes = ByteBuffer.allocateDirect(raw.length * 4).order(ByteOrder.nativeOrder());
                bytes.asIntBuffer().put(raw);
                buffer.getClass().getField("field_178999_b").set(buffer, bytes.asIntBuffer());
                buffer.getClass().getField("field_179000_c").set(buffer, bytes.asFloatBuffer());
                buffer.getClass().getField("field_178997_d").setInt(buffer, quads * 4);
                buffer.getClass().getField("field_179011_q").set(buffer, format);
                setOffsets(buffer, offset);
                invoke(buffer, world);
                int[] actual = new int[raw.length]; bytes.asIntBuffer().get(actual);
                assertArrayEquals("stride=" + stride + " quads=" + quads,
                    vanilla.getVertexState().getRawBuffer(), actual);
            }
        }
    }

    public static boolean capture(ChunkBufferAccessor buffer, float x, float y, float z) throws Exception {
        received = new float[] {x, y, z};
        if (accept && sort) {
            Method method = ChunkPrimitiveSortBridge.class.getDeclaredMethod("sortForTest",
                ChunkBufferAccessor.class, float.class, float.class, float.class);
            method.setAccessible(true);
            method.invoke(null, buffer, x, y, z);
        }
        return accept;
    }

    private static void setOffsets(Object buffer, double[] values) throws Exception {
        for (int axis = 0; axis < 3; axis++)
            buffer.getClass().getField(OFFSETS[axis]).setDouble(buffer, values[axis]);
    }

    private static void invoke(Object buffer, float[] world) throws Exception {
        buffer.getClass().getMethod(VanillaChunkRenderAdapter.SORT_METHOD,
            float.class, float.class, float.class).invoke(buffer, world[0], world[1], world[2]);
    }

    private static Object fixture() throws Exception {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
        String[][] fields = {{"field_178999_b", "Ljava/nio/IntBuffer;"},
            {"field_179000_c", "Ljava/nio/FloatBuffer;"}, {"field_178997_d", "I"},
            {"field_179011_q", "Lnet/minecraft/client/renderer/vertex/VertexFormat;"},
            {"originalArguments", "[F"}};
        for (String[] field : fields) writer.visitField(Opcodes.ACC_PUBLIC, field[0], field[1], null, null).visitEnd();
        for (String field : OFFSETS) writer.visitField(Opcodes.ACC_PUBLIC, field, "D", null, null).visitEnd();
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode(); init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN); init.visitMaxs(0, 0); init.visitEnd();
        MethodVisitor original = writer.visitMethod(Opcodes.ACC_PUBLIC,
            VanillaChunkRenderAdapter.SORT_METHOD, "(FFF)V", null, null);
        original.visitCode();
        original.visitInsn(Opcodes.ICONST_0); original.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Integer");
        original.visitInsn(Opcodes.ACONST_NULL);
        original.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Arrays", "sort",
            "([Ljava/lang/Object;Ljava/util/Comparator;)V", false);
        original.visitVarInsn(Opcodes.ALOAD, 0); original.visitInsn(Opcodes.ICONST_3);
        original.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_FLOAT);
        for (int axis = 0; axis < 3; axis++) {
            original.visitInsn(Opcodes.DUP); original.visitInsn(Opcodes.ICONST_0 + axis);
            original.visitVarInsn(Opcodes.FLOAD, axis + 1); original.visitInsn(Opcodes.FASTORE);
        }
        original.visitFieldInsn(Opcodes.PUTFIELD, OWNER, "originalArguments", "[F");
        original.visitInsn(Opcodes.RETURN); original.visitMaxs(0, 0); original.visitEnd();
        writer.visitEnd();
        byte[] transformed = new VanillaChunkRenderAdapter(VanillaChunkRenderAdapter.Part.BUFFER_SORT)
            .transform(OWNER.replace('/', '.'), writer.toByteArray(), null);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(transformed).accept(node, 0);
        for (MethodNode method : node.methods) for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) insn;
            if (call.owner.equals(VanillaChunkRenderAdapter.SORT_BRIDGE)) {
                call.owner = Type.getInternalName(ChunkSortCoordinatesTest.class); call.name = "capture";
            }
            // The isolated test class uses the MCP VertexFormat on this JVM.
            if (call.owner.equals("net/minecraft/client/renderer/vertex/VertexFormat")
                    && call.name.equals("func_181719_f")) call.name = "getIntegerSize";
        }
        ClassWriter result = new ClassWriter(0); node.accept(result);
        final byte[] bytes = result.toByteArray();
        return new ClassLoader(ChunkSortCoordinatesTest.class.getClassLoader()) {
            Class<?> define() { return defineClass(OWNER.replace('/', '.'), bytes, 0, bytes.length); }
        }.define().newInstance();
    }
}
