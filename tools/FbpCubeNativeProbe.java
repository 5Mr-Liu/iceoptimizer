package dev.rlcraft.ice.hooks;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import dev.rlcraft.ice.optimizer.client.ClientOptimizerRuntime;
import dev.rlcraft.ice.optimizer.client.ModernRendererRuntime;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.compat.particle.FbpCubeRenderBridge;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipFile;
import java.util.jar.JarFile;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.Vec2f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.Pbuffer;
import org.lwjgl.opengl.PixelFormat;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/** Hidden GL context, real FBP helper/putCube/CUBE data and real Forge uploader. */
public final class FbpCubeNativeProbe {
    private static final int SIZE = 64;
    private static final String STATE = "dev/rlcraft/ice/hooks/fbpprobe/State";
    private static final String UTIL = "dev/rlcraft/ice/hooks/fbpprobe/Util";
    private static CountingTessellator tessellator;
    private static int atlas;
    private static int binds;
    private static Class<?> state;
    private static Object rotation;
    private static Method square;
    private static Method tall;
    private static ModernRendererRuntime runtime;

    public static void main(String[] args) throws Exception {
        loadRealHelpers(args[0], args[1]);
        if (args.length > 2) verifyActualOptifineMultiTexture(args[2]);
        Pbuffer context = new Pbuffer(SIZE, SIZE, new PixelFormat().withDepthBits(24), null, null);
        try {
            context.makeCurrent();
            OpenGlHelper.initializeTextures();
            tessellator = new CountingTessellator();
            setupRuntime();
            atlas = texture(new int[] { 0xffaa6030, 0xff20a0d0, 0xffa02080, 0xff70d050 });
            OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            int lightmap = texture(new int[] { 0xff404040, 0xff909090, 0xffe0e0e0, 0xff707070 });
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
            for (boolean cartoon : new boolean[] { false, true }) {
                state.getField("cartoonMode").setBoolean(null, cartoon);
                Result legacy = render(false, true, false, true);
                Result modern = render(true, true, false, true);
                equivalent(legacy, modern, "mixed square/tall cartoon=" + cartoon);
                require(legacy.draws == 7 && legacy.empty == 3 && legacy.binds == 6,
                    "legacy flush/bind counts: " + legacy);
                require(modern.draws == 3 && modern.empty == 1 && modern.binds == 4,
                    "native flush/bind counts: " + modern);
                require(legacy.begins == 7 && modern.begins == 5, "begin count mismatch: " + legacy + " / " + modern);
                Result unknown = render(true, false, false, true);
                equivalent(legacy, unknown, "untracked GL fallback");
                require(unknown.draws == 5 && unknown.empty == 1, "unknown did not use uploader");
                Result shaders = render(true, true, true, true);
                equivalent(legacy, shaders, "ShaderPack fallback");
                require(shaders.draws == 5, "shader did not use uploader");
                Result legacyEmpty = render(false, true, false, false);
                Result modernEmpty = render(true, true, false, false);
                equivalent(legacyEmpty, modernEmpty, "nondefault client selector, empty incoming run");
                System.out.println("fbp_real_helpers cartoon=" + cartoon
                    + " pixels_equal=true state_equal=true nonempty_draws=4->4"
                    + " tessellator_draws=7->3 empty_flushes=3->1 atlas_binds=6->4 begins=7->5"
                    + " unknown_gl_fallback=true shader_fallback=true mixed_order_equal=true");
            }
            verifyMutableFormatFallback();
            verifyBoundBufferFallback();
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "GL error");
            GL11.glDeleteTextures(atlas);
            GL11.glDeleteTextures(lightmap);
            System.out.println("renderer=" + GL11.glGetString(GL11.GL_RENDERER));
        } finally { context.destroy(); }
    }

    private static void verifyMutableFormatFallback() throws Exception {
        VertexFormat format = (VertexFormat) state.getField("POSITION_TEX_COLOR_LMAP_NORMAL").get(null);
        net.minecraft.client.renderer.vertex.VertexFormatElement old = format.getElement(2);
        format.getElements().set(2, new net.minecraft.client.renderer.vertex.VertexFormatElement(0,
            net.minecraft.client.renderer.vertex.VertexFormatElement.EnumType.BYTE,
            net.minecraft.client.renderer.vertex.VertexFormatElement.EnumUsage.COLOR, 4));
        try {
            Result legacy = render(false, true, false, true);
            Result fallback = render(true, true, false, true);
            equivalent(legacy, fallback, "same-size format mutation fallback");
            require(fallback.draws == 5, "mutated format consumed natively");
            System.out.println("fbp_mutable_format same_size_edit_detected=true legacy_pixels_equal=true");
        } finally { format.getElements().set(2, old); }
    }

    private static void verifyBoundBufferFallback() throws Exception {
        int sentinel = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, sentinel);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, 4096, GL15.GL_STATIC_DRAW);
        EarlyGlStateTracker.useProgram(0);
        EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER, sentinel);
        shaderState(false);
        Class<?> expected = null;
        try {
            for (boolean bridge : new boolean[] { false, true }) {
                BufferBuilder buffer = tessellator.getBuffer();
                buffer.begin(GL11.GL_QUADS, (VertexFormat) state.getField("POSITION_TEX_COLOR_LMAP_NORMAL").get(null));
                buffer.pos(0, 0, 0).tex(0, 0).color(255, 255, 255, 255)
                    .lightmap(0, 0).normal(0, 0, 1).endVertex();
                int before = tessellator.draws;
                try {
                    if (bridge) FbpCubeRenderBridge.drawCube(tessellator);
                    else tessellator.draw();
                    throw new AssertionError("bound client pointer unexpectedly accepted");
                } catch (RuntimeException original) {
                    if (expected == null) expected = original.getClass();
                    else require(original.getClass() == expected, "original exception changed");
                    require(tessellator.draws == before + 1, "bound buffer bypassed original uploader");
                } finally { buffer.reset(); }
            }
            require(GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING) == sentinel,
                "fallback rebound caller's buffer");
            System.out.println("fbp_bound_buffer legacy_uploader_used=true original_exception_equal=true binding_unchanged=true");
        } finally {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GL15.glDeleteBuffers(sentinel);
        }
    }

    private static void verifyActualOptifineMultiTexture(String classFile) throws Exception {
        ClassNode original = FbpRenderUtilAdapterTest.node(Files.readAllBytes(Paths.get(classFile)));
        MethodNode isMulti = FbpRenderUtilAdapterTest.find(original, "isMultiTexture");
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, original.name, null, "java/lang/Object", null);
        String sprites = null;
        String spriteType = null;
        for (AbstractInsnNode instruction : isMulti.instructions.toArray()) {
            if (instruction instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) instruction;
                sprites = field.name;
                spriteType = field.desc;
                writer.visitField(Opcodes.ACC_PUBLIC, field.name, field.desc, null, null).visitEnd();
            }
        }
        require(sprites != null, "OptiFine isMultiTexture changed");
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        isMulti.accept(writer);
        writer.visitEnd();
        final byte[] bufferClass = writer.toByteArray();
        final String bufferName = original.name.replace('/', '.');
        final String rendererName = "dev.rlcraft.ice.optimizer.render.particle.LwjglFbpCubeRenderer";
        final byte[] rendererClass = resource(rendererName.replace('.', '/') + ".class");
        ClassLoader loader = new ClassLoader(FbpCubeNativeProbe.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(bufferName) && !name.equals(rendererName)) return super.loadClass(name, resolve);
                Class<?> type = findLoadedClass(name);
                if (type == null) {
                    byte[] bytes = name.equals(bufferName) ? bufferClass : rendererClass;
                    type = defineClass(name, bytes, 0, bytes.length);
                }
                if (resolve) resolveClass(type);
                return type;
            }
        };
        Class<?> bufferType = loader.loadClass(bufferName);
        Object buffer = bufferType.newInstance();
        Method accepts = loader.loadClass(rendererName).getMethod("isSingleTexture", bufferType);
        require((boolean) accepts.invoke(null, buffer), "normal OptiFine buffer rejected");
        bufferType.getField(sprites).set(buffer, java.lang.reflect.Array.newInstance(
            Class.forName(spriteType.substring(2, spriteType.length() - 1).replace('/', '.')), 1));
        require(!(boolean) accepts.invoke(null, buffer), "OptiFine multiTexture would lose sprite submission");
        System.out.println("fbp_actual_optifine isMultiTexture_real_bytecode=true singletexture_accepted=true multisprite_legacy=true");
    }

    private static byte[] resource(String name) throws Exception {
        java.io.InputStream input = FbpCubeNativeProbe.class.getClassLoader().getResourceAsStream(name);
        try {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] bytes = new byte[4096];
            for (int count; (count = input.read(bytes)) >= 0;) output.write(bytes, 0, count);
            return output.toByteArray();
        } finally { input.close(); }
    }

    private static Result render(boolean enabled, boolean tracked, boolean shaders,
                                 boolean incoming) throws Exception {
        OptimizerRegistry.breaker(OptimizationModule.FBP_CUBE_SUBMISSION).configure(enabled, 3);
        shaderState(shaders);
        GL20.glUseProgram(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
        GL11.glViewport(0, 0, SIZE, SIZE);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();
        GlStateManager.disableDepth();
        GlStateManager.disableCull();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableLighting();
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.enableTexture2D();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlas);
        OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        GlStateManager.enableTexture2D();
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
        GL11.glNormal3f(0.2F, 0.3F, 0.4F);
        GL11.glColor4f(0.4F, 0.5F, 0.6F, 0.7F);
        GL11.glClearColor(0, 0, 0, 0);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        EarlyGlStateTracker.beginProbe();
        if (tracked) {
            EarlyGlStateTracker.useProgram(0);
            EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        }
        binds = tessellator.draws = tessellator.empty = tessellator.buffer.begins = 0;
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP);
        if (incoming) quad(buffer, -0.95, -0.95, 0.95, 0.95, 220, 40, 20, 110);
        Vec2f[] uv = { new Vec2f(0, 0), new Vec2f(0, 1), new Vec2f(1, 1), new Vec2f(1, 0) };
        square.invoke(null, buffer, uv, -0.20F, 0.10F, 0F, 0.55D, rotation,
            128, 196, 0.9F, 0.7F, 0.4F, 0.55F, false);
        assertBoundary(buffer);
        tall.invoke(null, buffer, uv, 0.15F, -0.12F, 0F, 0.50D, 0.65D, rotation,
            200, 100, 0.3F, 0.8F, 0.95F, 0.45F, true);
        assertBoundary(buffer);
        quad(buffer, -0.80, -0.30, 0.75, 0.80, 10, 70, 240, 130);
        tessellator.draw();
        ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        GL11.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        byte[] output = new byte[pixels.remaining()];
        pixels.get(output);
        boolean visible = false;
        for (byte value : output) visible |= value != 0;
        require(visible, "empty pixels");
        int[] finalState = snapshot(buffer);
        int glError = GL11.glGetError();
        require(glError == GL11.GL_NO_ERROR, "render GL error=" + glError
            + " enabled=" + enabled + " tracked=" + tracked + " shaders=" + shaders);
        return new Result(output, finalState, tessellator.draws,
            tessellator.empty, binds, tessellator.buffer.begins);
    }

    private static void assertBoundary(BufferBuilder buffer) throws Exception {
        require(buffer.getVertexCount() == 0, "cube did not reset vertices");
        require(buffer.getVertexFormat().equals(DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP),
            "cube did not restore manager format");
        Field drawing = BufferBuilder.class.getDeclaredField("isDrawing");
        drawing.setAccessible(true);
        require(drawing.getBoolean(buffer), "cube did not restart manager buffer");
    }

    private static int[] snapshot(BufferBuilder buffer) {
        int[] state = new int[26];
        int at = 0;
        for (int constant : new int[] { GL13.GL_CLIENT_ACTIVE_TEXTURE,
            GL15.GL_ARRAY_BUFFER_BINDING, GL30.GL_VERTEX_ARRAY_BINDING,
            GL20.GL_CURRENT_PROGRAM, GL11.GL_LIGHTING, GL11.GL_CULL_FACE,
            GL11.GL_COLOR_MATERIAL, GL11.GL_VERTEX_ARRAY, GL11.GL_COLOR_ARRAY,
            GL11.GL_NORMAL_ARRAY }) state[at++] = GL11.glGetInteger(constant);
        for (int constant : new int[] { GL11.GL_CURRENT_COLOR, GL11.GL_CURRENT_NORMAL }) {
            FloatBuffer values = BufferUtils.createFloatBuffer(16);
            GL11.glGetFloat(constant, values);
            for (int i = 0; i < (constant == GL11.GL_CURRENT_COLOR ? 4 : 3); i++) {
                state[at++] = Float.floatToIntBits(values.get(i));
            }
        }
        for (int unit : new int[] { OpenGlHelper.defaultTexUnit, OpenGlHelper.lightmapTexUnit }) {
            OpenGlHelper.setClientActiveTexture(unit);
            state[at++] = GL11.glGetInteger(GL11.GL_TEXTURE_COORD_ARRAY);
        }
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        state[at++] = buffer.getByteBuffer().position();
        state[at++] = buffer.getByteBuffer().limit();
        state[at++] = buffer.getVertexCount();
        return Arrays.copyOf(state, at);
    }

    private static void equivalent(Result old, Result next, String label) {
        require(Arrays.equals(old.pixels, next.pixels), label + " pixel mismatch");
        require(Arrays.equals(old.state, next.state), label + " state mismatch "
            + Arrays.toString(old.state) + " / " + Arrays.toString(next.state));
    }

    private static void quad(BufferBuilder buffer, double x0, double y0, double x1, double y1,
                             int red, int green, int blue, int alpha) {
        for (double[] xy : new double[][] { {x0,y0}, {x1,y0}, {x1,y1}, {x0,y1} }) {
            buffer.pos(xy[0], xy[1], 0).tex(0.5, 0.5).color(red, green, blue, alpha)
                .lightmap(160, 160).endVertex();
        }
    }

    private static int texture(int[] colors) {
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        ByteBuffer bytes = BufferUtils.createByteBuffer(16);
        for (int color : colors) bytes.put((byte)(color >> 16)).put((byte)(color >> 8))
            .put((byte)color).put((byte)(color >> 24));
        bytes.flip();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 2, 2, 0,
            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, bytes);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        return id;
    }

    private static void setupRuntime() throws Exception {
        Constructor<ModernRendererRuntime> constructor = ModernRendererRuntime.class
            .getDeclaredConstructor(ClientEpochs.class, CacheBudget.class);
        constructor.setAccessible(true);
        runtime = constructor.newInstance(new ClientEpochs(), new CacheBudget(1, 1, 1024));
        Field field = ClientOptimizerRuntime.class.getDeclaredField("modernRenderer");
        field.setAccessible(true);
        field.set(ClientOptimizerRuntime.INSTANCE, runtime);
        OptimizerRegistry.breaker(OptimizationModule.FBP_CUBE_SUBMISSION).patchInstalled("fixture", "real");
    }

    private static void shaderState(boolean active) throws Exception {
        Field field = ModernRendererRuntime.class.getDeclaredField("shaderRuntime");
        field.setAccessible(true);
        Object shaders = field.get(runtime);
        for (String name : new String[] { "shaderPackStateKnown", "shaderPackActive" }) {
            Field flag = shaders.getClass().getDeclaredField(name);
            flag.setAccessible(true);
            flag.setBoolean(shaders, name.equals("shaderPackStateKnown") || active);
        }
    }

    private static void loadRealHelpers(String fbp, String mappings) throws Exception {
        final Map<String, String> names = new HashMap<String, String>();
        ZipFile csv = new ZipFile(mappings);
        try {
            for (String file : new String[] { "methods.csv", "fields.csv" }) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                    csv.getInputStream(csv.getEntry(file)), "UTF-8"));
                for (String line; (line = reader.readLine()) != null;) {
                    String[] columns = line.split(",", 3);
                    if (columns.length >= 2) names.put(columns[0], columns[1]);
                }
                reader.close();
            }
        } finally { csv.close(); }
        final Map<String, String> classes = new HashMap<String, String>();
        classes.put(FbpRenderUtilAdapter.OWNER, UTIL);
        classes.put("com/TominoCZ/FBP/FBP", STATE);
        classes.put("net/minecraft/client/Minecraft", internal(MinecraftFixture.class));
        classes.put("net/minecraft/client/renderer/entity/RenderManager", internal(RenderManagerFixture.class));
        classes.put("net/minecraft/client/renderer/texture/TextureManager", internal(TextureManagerFixture.class));
        classes.put("net/minecraft/client/renderer/texture/TextureMap", internal(TextureMapFixture.class));
        Remapper mapper = new Remapper() {
            @Override public String map(String value) { return classes.containsKey(value) ? classes.get(value) : value; }
            @Override public String mapMethodName(String owner, String name, String descriptor) {
                return names.containsKey(name) ? names.get(name) : name;
            }
            @Override public String mapFieldName(String owner, String name, String descriptor) {
                return names.containsKey(name) ? names.get(name) : name;
            }
        };
        Loader loader = new Loader();
        JarFile jar = new JarFile(fbp);
        try {
            state = loader.define(remap(stateClass(FbpRenderUtilAdapterTest.read(jar, "com/TominoCZ/FBP/FBP")), mapper));
            state.getField("POSITION_TEX_COLOR_LMAP_NORMAL").set(null, new VertexFormat()
                .addElement(DefaultVertexFormats.POSITION_3F).addElement(DefaultVertexFormats.TEX_2F)
                .addElement(DefaultVertexFormats.COLOR_4UB).addElement(DefaultVertexFormats.TEX_2S)
                .addElement(DefaultVertexFormats.NORMAL_3B));
            Class<?> vector = loader.define(remap(FbpRenderUtilAdapterTest.read(jar,
                "com/TominoCZ/FBP/vector/FBPVector3d"), mapper));
            rotation = vector.getConstructor(Double.TYPE, Double.TYPE, Double.TYPE).newInstance(23D, 37D, 11D);
            byte[] adapted = FbpRenderUtilAdapterTest.transform(FbpRenderUtilAdapterTest.read(jar, FbpRenderUtilAdapter.OWNER));
            ClassNode util = FbpRenderUtilAdapterTest.node(adapted);
            for (MethodNode method : util.methods) for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (call.owner.equals(FbpRenderUtilAdapter.TESSELLATOR)
                        && call.name.equals("func_178181_a")) call.owner = internal(CountingTessellator.class);
                }
            }
            ClassWriter bytes = new ClassWriter(0);
            util.accept(bytes);
            Class<?> helper = loader.define(remap(bytes.toByteArray(), mapper));
            square = helper.getMethod("renderCubeShaded_S", BufferBuilder.class, Vec2f[].class,
                Float.TYPE, Float.TYPE, Float.TYPE, Double.TYPE, vector, Integer.TYPE,
                Integer.TYPE, Float.TYPE, Float.TYPE, Float.TYPE, Float.TYPE, Boolean.TYPE);
            tall = helper.getMethod("renderCubeShaded_WH", BufferBuilder.class, Vec2f[].class,
                Float.TYPE, Float.TYPE, Float.TYPE, Double.TYPE, Double.TYPE, vector,
                Integer.TYPE, Integer.TYPE, Float.TYPE, Float.TYPE, Float.TYPE, Float.TYPE, Boolean.TYPE);
        } finally { jar.close(); }
    }

    private static byte[] stateClass(byte[] fbp) {
        ClassNode original = FbpRenderUtilAdapterTest.node(fbp);
        MethodNode clinit = FbpRenderUtilAdapterTest.find(original, "<clinit>");
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/TominoCZ/FBP/FBP", null, "java/lang/Object", null);
        for (String name : new String[] { "CUBE", "CUBE_NORMALS" }) {
            writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name,
                "[Lnet/minecraft/util/math/Vec3d;", null, null).visitEnd();
        }
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "cartoonMode", "Z", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "POSITION_TEX_COLOR_LMAP_NORMAL",
            "Lnet/minecraft/client/renderer/vertex/VertexFormat;", null, null).visitEnd();
        MethodVisitor code = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        code.visitCode();
        for (String name : new String[] { "CUBE", "CUBE_NORMALS" }) {
            AbstractInsnNode end = null;
            for (AbstractInsnNode instruction : clinit.instructions.toArray()) {
                if (instruction instanceof FieldInsnNode && instruction.getOpcode() == Opcodes.PUTSTATIC
                    && ((FieldInsnNode) instruction).name.equals(name)) end = instruction;
            }
            require(end != null, "missing real cube data " + name);
            AbstractInsnNode begin = end;
            while (!(begin instanceof TypeInsnNode && begin.getOpcode() == Opcodes.ANEWARRAY)) begin = begin.getPrevious();
            begin = begin.getPrevious();
            while (begin.getOpcode() < 0) begin = begin.getPrevious();
            require(begin instanceof IntInsnNode, "real cube data prefix changed");
            for (AbstractInsnNode instruction = begin; ; instruction = instruction.getNext()) {
                if (instruction.getOpcode() >= 0) instruction.accept(code);
                if (instruction == end) break;
            }
        }
        code.visitInsn(Opcodes.RETURN);
        code.visitMaxs(0, 0);
        code.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] remap(byte[] bytes, Remapper mapper) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(writer, mapper), 0);
        return writer.toByteArray();
    }

    private static String internal(Class<?> type) { return type.getName().replace('.', '/'); }
    private static void require(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }

    public static final class MinecraftFixture {
        public static MinecraftFixture getMinecraft() { return new MinecraftFixture(); }
        public RenderManagerFixture getRenderManager() { return new RenderManagerFixture(); }
    }
    public static final class RenderManagerFixture { public TextureManagerFixture renderEngine = new TextureManagerFixture(); }
    public static final class TextureManagerFixture {
        public void bindTexture(ResourceLocation ignored) { binds++; GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlas); }
    }
    public static final class TextureMapFixture { public static final ResourceLocation LOCATION_BLOCKS_TEXTURE = new ResourceLocation("probe", "atlas"); }
    public static final class CountingTessellator extends Tessellator {
        private final CountingBuffer buffer = new CountingBuffer();
        private int draws;
        private int empty;
        CountingTessellator() throws Exception {
            super(4096);
            Field field = Tessellator.class.getDeclaredField("buffer");
            field.setAccessible(true);
            field.set(this, buffer);
        }
        public static Tessellator getInstance() { return tessellator; }
        @Override public void draw() { draws++; if (buffer.getVertexCount() == 0) empty++; super.draw(); }
    }
    public static final class CountingBuffer extends BufferBuilder {
        private int begins;
        CountingBuffer() { super(4096); }
        @Override public void begin(int mode, VertexFormat format) { begins++; super.begin(mode, format); }
    }
    private static final class Loader extends ClassLoader {
        Loader() { super(FbpCubeNativeProbe.class.getClassLoader()); }
        Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
    }
    private static final class Result {
        final byte[] pixels;
        final int[] state;
        final int draws, empty, binds, begins;
        Result(byte[] pixels, int[] state, int draws, int empty, int binds, int begins) {
            this.pixels = pixels; this.state = state; this.draws = draws;
            this.empty = empty; this.binds = binds; this.begins = begins;
        }
        @Override public String toString() { return draws + "/" + empty + "/" + binds + "/" + begins; }
    }
}
