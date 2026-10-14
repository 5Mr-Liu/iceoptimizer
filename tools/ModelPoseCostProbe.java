package dev.rlcraft.ice.optimizer.render.entity;

import dev.rlcraft.ice.optimizer.compat.gl.*;
import dev.rlcraft.ice.optimizer.memory.*;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.resource.*;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.io.*;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Offline native-call instrumentation; never loaded by the production mod. */
public final class ModelPoseCostProbe {
    public static long uniforms, readbacks, readbackBytes;
    public static void uniform() { uniforms++; }
    public static void readback(ByteBuffer data) { readbacks++; readbackBytes += data.remaining(); }

    public static void main(String[] args) throws Exception {
        // Optional directory containing the backed-up pre-change cache/pose classes.
        if (args.length > 1) throw new IllegalArgumentException("[baseline-classes]");
        if (args.length == 1) execute(Paths.get(args[0]), false);
        execute(null, true);
    }

    private static void execute(Path baseline, boolean optimized) throws Exception {
        uniforms = readbacks = readbackBytes = 0;
        ClassLoader loader = new CountingLoader(baseline);
        try {
            Class.forName(Run.class.getName(), true, loader).getMethod("execute", boolean.class).invoke(null, optimized);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw failure;
        }
    }

    private static final class CountingLoader extends ClassLoader {
        final Path baseline;
        CountingLoader(Path baseline) { super(ModelPoseCostProbe.class.getClassLoader()); this.baseline = baseline; }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            String outer = ModelPoseCostProbe.class.getName();
            if (!name.startsWith("dev.rlcraft.ice.") || name.equals(outer) || name.equals(CountingLoader.class.getName()))
                return super.loadClass(name, resolve);
            Class<?> found = findLoadedClass(name);
            if (found == null) {
                String resource = name.replace('.', '/') + ".class";
                byte[] bytes;
                try {
                    Path old = baseline == null ? null : baseline.resolve(resource);
                    if (old != null && Files.isRegularFile(old)) bytes = Files.readAllBytes(old);
                    else try (InputStream in = getParent().getResourceAsStream(resource)) {
                        if (in == null) throw new ClassNotFoundException(name);
                        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192];
                        for (int n; (n = in.read(buffer)) >= 0;) out.write(buffer, 0, n);
                        bytes = out.toByteArray();
                    }
                } catch (IOException e) { throw new ClassNotFoundException(name, e); }
                if (name.startsWith("dev.rlcraft.ice.optimizer.render.entity.LwjglModel")) bytes = instrument(bytes);
                found = defineClass(name, bytes, 0, bytes.length);
            }
            if (resolve) resolveClass(found);
            return found;
        }
        private byte[] instrument(byte[] source) {
            ClassNode node = new ClassNode(Opcodes.ASM5); new ClassReader(source).accept(node, 0);
            for (MethodNode method : node.methods) for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) insn;
                if (call.owner.equals("org/lwjgl/opengl/GL20") && call.name.equals("glUniform1i"))
                    method.instructions.insertBefore(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                        ModelPoseCostProbe.class.getName().replace('.', '/'), "uniform", "()V", false));
                if (call.owner.equals("org/lwjgl/opengl/GL15") && call.name.equals("glGetBufferSubData")
                    && call.desc.equals("(IJLjava/nio/ByteBuffer;)V")) {
                    InsnList counter = new InsnList(); counter.add(new InsnNode(Opcodes.DUP));
                    counter.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ModelPoseCostProbe.class.getName().replace('.', '/'),
                        "readback", "(Ljava/nio/ByteBuffer;)V", false));
                    method.instructions.insertBefore(call, counter);
                }
            }
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
        }
    }

    public static final class Run {
        private static final int SIZE = 96;
        private static CacheBudget budget;
        private static ResourceLedger ledger;
        private static FrameStamp stamp;
        private static LwjglModelMeshCache cache;
        private static int lists;

        public static void execute(boolean optimized) throws Exception {
            Pbuffer context = new Pbuffer(SIZE, SIZE, new PixelFormat(), null, null);
            try {
                context.makeCurrent();
                RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
                budget = new CacheBudget(4 << 20, 4 << 20, 4 << 20);
                ledger = new ResourceLedger(guard, budget, new ResourceLedger.Destroyer() {
                    public void destroy(RenderResourceKind kind, int id) {
                        if (kind == RenderResourceKind.BUFFER) GL15.glDeleteBuffers(id);
                        else if (kind == RenderResourceKind.VERTEX_ARRAY) GL30.glDeleteVertexArrays(id);
                        else if (kind == RenderResourceKind.PROGRAM) GL20.glDeleteProgram(id);
                        else if (kind == RenderResourceKind.SHADER) GL20.glDeleteShader(id);
                    }
                }, 128);
                ClientEpochs epochs = new ClientEpochs(); epochs.nextFrame(); stamp = new FrameStamp(1, 1, epochs.snapshot());
                cache = new LwjglModelMeshCache(guard, ledger, budget);
                lists = GL11.glGenLists(8);
                LwjglModelMeshCache.Entry[] entries = upload();
                long heapWithGeometry = budget.snapshot().getHeapUsed();
                long beforeUniforms = ModelPoseCostProbe.uniforms;
                int maximumPixelError = 0;
                for (int frame = 0; frame < 24; frame++) {
                    byte[] legacy = render(entries, frame, false);
                    byte[] modern = render(entries, frame, true);
                    maximumPixelError = Math.max(maximumPixelError, pixels(legacy, modern));
                }
                long changedUniforms = ModelPoseCostProbe.uniforms - beforeUniforms;
                beforeUniforms = ModelPoseCostProbe.uniforms;
                for (int frame = 0; frame < 100; frame++) render(entries, 23, true);
                long repeatedUniforms = ModelPoseCostProbe.uniforms - beforeUniforms;
                if (optimized) require(repeatedUniforms == 0, "unchanged program scalar values must not be resubmitted");
                else require(repeatedUniforms == 1900, "baseline scalar count");
                require(ModelPoseCostProbe.readbacks == (optimized ? 0 : 8), "cold merge readback count");
                long firstReadbackBytes = ModelPoseCostProbe.readbackBytes;
                long retainedVertices = optimized ? 8 * 96 : 0;
                require(maximumPixelError <= 1, "native pixel difference");
                byte[] reference = render(entries, 23, false);
                beforeUniforms = ModelPoseCostProbe.uniforms;
                require(pixels(reference, render(entries, 23, true, true)) <= 1, "split palette base");
                if (optimized) require(ModelPoseCostProbe.uniforms - beforeUniforms == 1, "split palette base must advance once");
                beforeUniforms = ModelPoseCostProbe.uniforms;
                require(pixels(reference, render(entries, 23, true)) <= 1, "next tree base reset");
                if (optimized) require(ModelPoseCostProbe.uniforms - beforeUniforms == 1, "next tree base must return to zero");
                // Keep an already merged shape alive while replacing its source.
                LwjglModelMeshCache.Entry old = entries[0];
                ModelMeshPayload replacement = new ModelMeshPayload(lists, geometry(0), DefaultVertexFormats.POSITION_TEX_NORMAL, GL11.GL_QUADS, 4, 1, 1);
                byte[] changed = replacement.getVertices(); changed[0] ^= 1;
                replacement = new ModelMeshPayload(lists, changed, DefaultVertexFormats.POSITION_TEX_NORMAL, GL11.GL_QUADS, 4, 1, 1);
                seed(); require(cache.accept(replacement, stamp), "replacement");
                entries[0] = cache.findStateBatchEntry(lists, stamp);
                require(entries[0] != old, "replacement not published");
                if (optimized) require(field(old.getClass(), "geometry").get(old) == null, "old pose shape retained CPU source");
                render(entries, 23, true);
                if (optimized) require(ModelPoseCostProbe.readbacks == 0, "replacement rebuilt through GPU readback");
                // Same program reset must invalidate scalar history; source data
                // is retained only for live entries and released on both reset modes.
                cache.reset(true); ledger.collect(1, 128);
                if (optimized) require(field(entries[0].getClass(), "geometry").get(entries[0]) == null, "reset retained source");
                entries = upload();
                beforeUniforms = ModelPoseCostProbe.uniforms;
                render(entries, 23, true);
                require(ModelPoseCostProbe.uniforms - beforeUniforms == 19, "new program reused stale scalar cache");
                if (optimized) publicationFailure(guard);
                cache.close(false); ledger.destroyAll(1);
                require(budget.snapshot().getHeapUsed() == 0 && budget.snapshot().getDirectUsed() == 0
                    && budget.snapshot().getGpuUsed() == 0, "retained geometry/storage leaked");
                System.out.println((optimized ? "optimized" : "baseline") + " pose_cost_native PASS changed_24_scope_uniform_calls=" + changedUniforms
                    + " unchanged_100_scope_uniform_calls=" + repeatedUniforms + " first_merge_readback_calls=" + (optimized ? 0 : 8)
                    + " first_merge_readback_bytes=" + firstReadbackBytes + " retained_vertex_heap_bytes=" + retainedVertices
                    + " total_entry_heap_with_metadata=" + heapWithGeometry + " max_pixel_error=" + maximumPixelError
                    + " replacement_source_released=" + optimized + " program_reset_reuploads=19 all_budgets_zero=true");
            } finally { if (lists > 0) GL11.glDeleteLists(lists, 8); context.destroy(); }
        }

        private static void publicationFailure(RenderThreadGuard guard) throws Exception {
            final RuntimeException failure = new IllegalStateException("publication failure");
            final boolean[] fail = {true};
            LwjglModelMeshCache failing = new LwjglModelMeshCache(guard, ledger, budget, new LwjglModelMeshCache.PublicationHook() {
                public void afterEntryPut() { if (fail[0]) throw failure; }
            });
            long before = budget.snapshot().getHeapUsed();
            seed();
            try { failing.accept(new ModelMeshPayload(lists + 30, geometry(0), DefaultVertexFormats.POSITION_TEX_NORMAL,
                GL11.GL_QUADS, 4, 1, 1), stamp); throw new AssertionError("publication succeeded"); }
            catch (RuntimeException expected) { require(expected == failure, "exception changed"); }
            require(failing.size() == 0 && budget.snapshot().getHeapUsed() == before, "rollback kept geometry reservation");
            fail[0] = false;
            seed(); require(failing.accept(new ModelMeshPayload(lists + 30, geometry(0), DefaultVertexFormats.POSITION_TEX_NORMAL,
                GL11.GL_QUADS, 4, 1, 1), stamp), "publish before injected replacement failure");
            LwjglModelMeshCache.Entry original = failing.find(lists + 30, stamp);
            Object originalGeometry = field(original.getClass(), "geometry").get(original);
            before = budget.snapshot().getHeapUsed();
            fail[0] = true; seed();
            try { failing.accept(new ModelMeshPayload(lists + 30, geometry(1), DefaultVertexFormats.POSITION_TEX_NORMAL,
                GL11.GL_QUADS, 4, 1, 1), stamp); throw new AssertionError("replacement succeeded"); }
            catch (RuntimeException expected) { require(expected == failure, "replacement exception changed"); }
            require(failing.find(lists + 30, stamp) == original && field(original.getClass(), "geometry").get(original) == originalGeometry
                && budget.snapshot().getHeapUsed() == before, "replacement rollback lost old geometry or retained new reservation");
            failing.invalidate(lists + 30);
            require(field(original.getClass(), "geometry").get(original) == null, "invalidation retained immutable source");
            failing.close(true); ledger.collect(1, 128);
        }

        private static LwjglModelMeshCache.Entry[] upload() {
            LwjglModelMeshCache.Entry[] entries = new LwjglModelMeshCache.Entry[8];
            for (int i = 0; i < 8; i++) {
                byte[] vertices = geometry(i);
                ModelMeshPayload payload = new ModelMeshPayload(lists + i, vertices, DefaultVertexFormats.POSITION_TEX_NORMAL,
                    GL11.GL_QUADS, 4, 1, 1);
                // Neither caller mutation nor public getters can change the owned copy.
                Arrays.fill(vertices, (byte) 0x4f); Arrays.fill(payload.getVertices(), (byte) 0x6c);
                seed(); require(cache.accept(payload, stamp), "capture upload");
                entries[i] = cache.findStateBatchEntry(lists + i, stamp);
                ByteBuffer data = ByteBuffer.wrap(geometry(i)).order(ByteOrder.nativeOrder());
                GL11.glNewList(lists + i, GL11.GL_COMPILE); GL11.glBegin(GL11.GL_QUADS);
                for (int vertex = 0; vertex < 4; vertex++) {
                    GL11.glNormal3f(0, 0, 1); GL11.glTexCoord2f(0, 0);
                    GL11.glVertex3f(data.getFloat(vertex * 24), data.getFloat(vertex * 24 + 4), data.getFloat(vertex * 24 + 8));
                }
                GL11.glEnd(); GL11.glEndList();
            }
            return entries;
        }

        private static byte[] render(LwjglModelMeshCache.Entry[] entries, int frame, boolean modern) throws Exception {
            return render(entries, frame, modern, false);
        }
        private static byte[] render(LwjglModelMeshCache.Entry[] entries, int frame, boolean modern, boolean split) throws Exception {
            GL11.glViewport(0, 0, SIZE, SIZE);
            GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glLoadIdentity();
            GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glLoadIdentity();
            EarlyMatrixStateTracker.invalidate(); EarlyModelLightingState.invalidate();
            GL20.glUseProgram(0); GL11.glDisable(GL11.GL_TEXTURE_2D); GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_CULL_FACE); GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_ALPHA_TEST); GL11.glColor4f(.3F, .6F, .8F, 1);
            capability(GL11.GL_LIGHTING, frame >= 2);
            capability(GL11.GL_NORMALIZE, frame >= 6);
            capability(GL12.GL_RESCALE_NORMAL, frame >= 8);
            capability(GL11.GL_COLOR_MATERIAL, frame >= 10);
            for (int i = 0; i < 8; i++) capability(GL11.GL_LIGHT0 + i, frame >= 4 && i == 0);
            GL11.glLightModeli(GL11.GL_LIGHT_MODEL_TWO_SIDE, frame >= 12 ? 1 : 0);
            GL11.glLightModeli(GL11.GL_LIGHT_MODEL_LOCAL_VIEWER, frame >= 14 ? 1 : 0);
            GL11.glLightModeli(GL12.GL_LIGHT_MODEL_COLOR_CONTROL, frame >= 16 ? GL12.GL_SEPARATE_SPECULAR_COLOR : GL12.GL_SINGLE_COLOR);
            GL11.glColorMaterial(frame >= 18 ? GL11.GL_FRONT_AND_BACK : GL11.GL_FRONT,
                frame >= 20 ? GL11.GL_DIFFUSE : GL11.GL_AMBIENT_AND_DIFFUSE);
            GL11.glClearColor(0, 0, 0, 0); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            seed();
            if (modern) require(cache.beginStateBatch(entries, entries.length), "begin pose");
            try {
                for (int i = 0; i < entries.length; i++) {
                    if (modern && split && i == 4) cache.flushPoseBatch();
                    if (modern) require(cache.drawInStateBatch(entries[i]), "pose draw");
                    else GL11.glCallList(lists + i);
                }
            } finally { if (modern) cache.endStateBatch(); }
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "native error");
            ByteBuffer out = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
            GL11.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, out);
            byte[] pixels = new byte[out.remaining()]; out.get(pixels); return pixels;
        }
        private static void capability(int name, boolean enabled) { if (enabled) GL11.glEnable(name); else GL11.glDisable(name); }
        private static byte[] geometry(int part) {
            ByteBuffer data = ByteBuffer.allocate(96).order(ByteOrder.nativeOrder());
            float x = (part % 4) * .45F - .9F, y = (part / 4) * .7F - .7F;
            for (float[] point : new float[][]{{x, y}, {x + .35F, y}, {x + .35F, y + .5F}, {x, y + .5F}})
                data.putFloat(point[0]).putFloat(point[1]).putFloat(-.2F).putFloat(0).putFloat(0)
                    .put((byte) 0).put((byte) 0).put((byte) 127).put((byte) 0);
            return data.array();
        }
        private static void seed() {
            try {
                Class<?> type = Class.forName("dev.rlcraft.ice.hooks.ModelPoseBoundaryProbe");
                Method method = type.getDeclaredMethod("seedGl"); method.setAccessible(true); method.invoke(null);
            } catch (Exception failure) { throw new AssertionError(failure); }
        }
        private static int pixels(byte[] a, byte[] b) { int max = 0, lit = 0; for (int i = 0; i < a.length; i++) {
            max = Math.max(max, Math.abs((a[i] & 255) - (b[i] & 255))); if (a[i] != 0) lit++;
        } require(lit > 100, "empty image comparison"); return max; }
        private static Field field(Class<?> type, String name) throws Exception { Field f = type.getDeclaredField(name); f.setAccessible(true); return f; }
        private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    }
}
