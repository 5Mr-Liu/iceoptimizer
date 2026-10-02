package dev.rlcraft.ice.hooks;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.*;

/** Native proof using unchanged FoamFix bytes and the real OF G5 upload closure.
 * Fixtures replace only game startup/settings, and count calls before real GL.
 * Arguments: foamfix.jar, optifine.jar, patched SRG TextureUtil.class, MCP csv.zip.
 */
public final class TextureParameterScopeProbe {
    private static final String UTIL = VanillaTextureUploadAdapter.TARGET;
    private static int writes, uploads;
    private static boolean enabled;

    public static void main(String[] args) throws Exception {
        final Map<String,String> names = names(args[3]);
        byte[] actual = Files.readAllBytes(Paths.get(args[2]));
        TargetSpec spec = new TargetSpec(UTIL.replace('/', '.'), "foamfix-texture-upload",
            "vanilla-texture-parameters", Collections.<String>emptySet());
        byte[] adapted = new VanillaTextureUploadAdapter().transform(spec.className, actual, spec);
        require(TextureUploadBootstrap.install(ConfigurationBridge.class), "bridge install");
        Pbuffer context = new Pbuffer(32, 32, new PixelFormat().withDepthBits(24), null, null);
        try {
            context.makeCurrent();
            Fixture old = new Fixture(args[0], args[1], actual, names);
            Fixture next = new Fixture(args[0], args[1], adapted, names);
            try {
                for (boolean blur : new boolean[] {false, true}) {
                    for (boolean clamp : new boolean[] {false, true}) {
                        Result baseline = run(old, false, blur, clamp, false);
                        Result optimized = run(next, true, blur, clamp, false);
                        equivalent(baseline, optimized);
                        require(baseline.writes == 24 && optimized.writes == 8,
                            "two helpers: writes " + baseline.writes + "/" + optimized.writes);
                        require(baseline.uploads == 6 && optimized.uploads == 6,
                            "pixel uploads changed");
                        Result disabled = run(next, false, blur, clamp, false);
                        equivalent(baseline, disabled);
                        require(disabled.writes == 24, "module disable did not restore writes");
                        Result boundary = run(next, true, blur, clamp, true);
                        equivalent(baseline, boundary);
                        require(boundary.writes == 8, "each new sprite needs four writes");
                    }
                }
                equivalent(run(old,false,false,false,false,true),run(next,true,false,false,false,true));
                verifyGameTwoLevelCase(old,next);
                verifyInterruptedScopes(next);
                require(GL11.glGetError() == GL11.GL_NO_ERROR, "GL error");
                System.out.println("actual_foamfix_original_class=true actual_optifine_g5_upload_closure=true"
                    + " native_pixels_equal=true mip_pixels_equal=true filter_wrap_equal=true"
                    + " two_helper_parameter_writes=24->8 pixel_uploads=6->6 mutable_same_array_uploaded=true"
                    + " sprite_and_texture_boundaries=true module_disable=true dormant_token_zero=true"
                    + " changed_helper_filter_flags=true game_two_level_parameter_writes=8->4"
                    + " nested_atlas_abort_barrier_first_level=true");
                System.out.println("renderer=" + GL11.glGetString(GL11.GL_RENDERER));
            } finally { old.close(); next.close(); }
        } finally { context.destroy(); TextureUploadBootstrap.resetForTest(); AnimatedTextureBootstrap.resetForTest(); }
    }

    private static Result run(Fixture fixture, boolean dedup, boolean blur, boolean clamp,
                              boolean newSprite) throws Exception {
        return run(fixture,dedup,blur,clamp,newSprite,false);
    }
    private static Result run(Fixture fixture, boolean dedup, boolean blur, boolean clamp,
                              boolean newSprite,boolean changedFlags) throws Exception {
        enabled = dedup;
        int texture = texture();
        int second = texture();
        int[][] pixels = new int[][] { new int[64], new int[16], new int[4] };
        for (int mip=0;mip<3;mip++) Arrays.fill(pixels[mip], 0xff2381c0 + mip * 0x110300);
        writes = uploads = 0;
        long token = AnimatedTextureBootstrap.begin(new Object());
        require(token == 0, "native test should exercise dormant atlas backend");
        AnimatedTextureBootstrap.beforeSprite(fixture.sprite);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        fixture.helper.invoke(null, 2, pixels, 8, 8, 0, 0,
            changedFlags?!blur:blur,changedFlags?!clamp:clamp,!changedFlags);
        byte[][] first = readMips();
        // Mutable interpolation arrays retain object/frame identity while pixels change.
        for (int mip=0;mip<3;mip++) Arrays.fill(pixels[mip], 0xffb23065 + mip * 0x021800);
        if (newSprite) { AnimatedTextureBootstrap.afterSprite(); AnimatedTextureBootstrap.beforeSprite(fixture.sprite); }
        // The real helper starts at mip zero after binding another resource.
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, second);
        fixture.helper.invoke(null, 2, pixels, 8, 8, 0, 0, blur, clamp, true);
        byte[][] result = readMips();
        require(!Arrays.deepEquals(first, result), "same mutable arrays lost updated pixels");
        int[] parameters = parameters();
        int written = writes, uploaded = uploads;
        AnimatedTextureBootstrap.afterSprite();
        AnimatedTextureBootstrap.end(token);
        GL11.glDeleteTextures(texture); GL11.glDeleteTextures(second);
        return new Result(first, result, parameters, written, uploaded);
    }

    private static void verifyGameTwoLevelCase(Fixture old,Fixture next) throws Exception {
        byte[][] baseline=null;int[] expected=null;
        for(Fixture fixture:new Fixture[]{old,next}) {
            enabled=fixture==next;
            SettingsFixture settings=new SettingsFixture();settings.ofMipmapType=0;
            fixture.config.getField("gameSettings").set(null,settings);
            int texture=texture();GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
            AnimatedTextureBootstrap.begin(new Object());AnimatedTextureBootstrap.beforeSprite(fixture.sprite);
            int[][] data={new int[64],new int[16]};Arrays.fill(data[0],0xffbf2387);Arrays.fill(data[1],0xff235fc1);
            writes=uploads=0;fixture.helper.invoke(null,1,data,8,8,0,0,false,false,true);
            byte[][] pixels=Arrays.copyOf(readMips(),2);int[] parameters=parameters();
            require(writes==(enabled?4:8)&&uploads==2,"game two-mip work count");
            if(fixture==old){baseline=pixels;expected=parameters;}
            else {require(Arrays.deepEquals(baseline,pixels),"game two-mip pixels");require(Arrays.equals(expected,parameters),"game mipmap type zero parameters");}
            AnimatedTextureBootstrap.afterSprite();AnimatedTextureBootstrap.end(0);GL11.glDeleteTextures(texture);
        }
    }

    private static void verifyInterruptedScopes(Fixture fixture) throws Exception {
        enabled = true;
        int id = texture(); GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        int[] data = new int[16]; Arrays.fill(data, 0xffabcdef);
        AnimatedTextureBootstrap.begin(new Object());
        AnimatedTextureBootstrap.beforeSprite(fixture.sprite);
        writes = 0;
        fixture.level.invoke(null, 0, new int[64], 8, 8, 0, 0, false, false, true);
        require(writes == 4, "first mip omitted setup");
        AnimatedTextureBootstrap.textureBarrier();
        fixture.level.invoke(null, 1, data, 4, 4, 0, 0, false, false, true);
        require(writes == 8, "binding barrier leaked reuse");
        AnimatedTextureBootstrap.beforeSprite(fixture.sprite);
        fixture.level.invoke(null, 2, new int[4], 2, 2, 0, 0, false, false, true);
        require(writes == 12, "nested scope reused outer parameters");
        AnimatedTextureBootstrap.afterSprite();
        AnimatedTextureBootstrap.abort(0, new IllegalStateException("probe"));
        fixture.level.invoke(null, 1, data, 4, 4, 0, 0, false, false, true);
        require(writes == 16, "zero-token abort leaked reuse");
        AnimatedTextureBootstrap.beforeSprite(fixture.sprite);
        fixture.level.invoke(null, 0,new int[64],8,8,0,0,false,false,true);
        require(writes==20,"fresh scope first level");
        AnimatedTextureBootstrap.begin(new Object());
        fixture.level.invoke(null, 1,data,4,4,0,0,false,false,true);
        require(writes==24,"nested atlas begin leaked reuse");
        AnimatedTextureBootstrap.end(0);
        GL11.glDeleteTextures(id);
    }

    private static int texture() {
        int id=GL11.glGenTextures(); GL11.glBindTexture(GL11.GL_TEXTURE_2D,id);
        for(int mip=0;mip<3;mip++) GL11.glTexImage2D(GL11.GL_TEXTURE_2D,mip,GL11.GL_RGBA8,
            8>>mip,8>>mip,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL12.GL_TEXTURE_MAX_LEVEL,2);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
        return id;
    }
    private static byte[][] readMips() {
        byte[][] all=new byte[3][];
        for(int mip=0;mip<3;mip++) {
            ByteBuffer b=BufferUtils.createByteBuffer((8>>mip)*(8>>mip)*4);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D,mip,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,b);
            all[mip]=new byte[b.remaining()]; b.get(all[mip]);
        }
        return all;
    }
    private static int[] parameters() {
        return new int[] {GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER),
            GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER),
            GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S),
            GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T)};
    }
    private static void equivalent(Result a,Result b) {
        require(Arrays.deepEquals(a.first,b.first)&&Arrays.deepEquals(a.pixels,b.pixels),"mip pixel mismatch");
        require(Arrays.equals(a.parameters,b.parameters),"filter/wrap mismatch");
    }

    private static final class Fixture extends URLClassLoader {
        final Object sprite; final Method helper,level;final Class<?> config;
        Fixture(String foamfix,String optifine,byte[] texture,final Map<String,String> names) throws Exception {
            super(new URL[]{new File(foamfix).toURI().toURL()},TextureParameterScopeProbe.class.getClassLoader());
            config=define(config(optifine));
            config.getField("gameSettings").set(null,new SettingsFixture());
            Class<?> util=define(textureClosure(texture,names));
            Class<?> fast=Class.forName(TextureParameterScope.FOAMFIX_SPRITE,true,this);
            require(TextureParameterScope.reviewedClassForTest(fast),"actual defining FoamFix JAR hash rejected");
            Constructor<?> constructor=fast.getConstructor(String.class);
            sprite=constructor.newInstance("ice-native-texture");
            helper=fast.getDeclaredMethod("uploadTextureMaxMips",int.class,int[][].class,int.class,
                int.class,int.class,int.class,boolean.class,boolean.class,boolean.class);
            helper.setAccessible(true);
            level=util.getDeclaredMethod("func_147947_a",int.class,int[].class,int.class,int.class,
                int.class,int.class,boolean.class,boolean.class,boolean.class);
        }
        Class<?> define(byte[] b) {return defineClass(null,b,0,b.length);}
    }

    private static byte[] textureClosure(byte[] input,final Map<String,String> names) {
        ClassNode n=node(input);
        Set<String> closure=new HashSet<String>(); closure.add("func_147947_a");
        boolean changed;
        do {
            changed=false;
            for(MethodNode m:n.methods) if(closure.contains(m.name))
                for(AbstractInsnNode i:m.instructions.toArray()) if(i instanceof MethodInsnNode) {
                    MethodInsnNode c=(MethodInsnNode)i;
                    if(UTIL.equals(c.owner)) changed|=closure.add(c.name);
                }
        }while(changed);
        for(Iterator<MethodNode> it=n.methods.iterator();it.hasNext();) {
            MethodNode m=it.next(); if(!closure.contains(m.name))it.remove();
            else if("func_147947_a".equals(m.name))m.access=Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC;
        }
        for(Iterator<FieldNode> it=n.fields.iterator();it.hasNext();)
            if(!"field_111000_c".equals(it.next().name))it.remove();
        MethodNode init=new MethodNode(Opcodes.ACC_STATIC,"<clinit>","()V",null,null);
        init.instructions.add(new LdcInsnNode(4194304));
        init.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"org/lwjgl/BufferUtils","createIntBuffer","(I)Ljava/nio/IntBuffer;",false));
        init.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC,UTIL,"field_111000_c","Ljava/nio/IntBuffer;"));
        init.instructions.add(new InsnNode(Opcodes.RETURN)); n.methods.add(init);
        return remap(n,new Remapper() {
            @Override public String map(String value) {
                if("net/minecraft/client/Minecraft".equals(value))return internal(MinecraftFixture.class);
                if("net/minecraft/client/settings/GameSettings".equals(value))return internal(SettingsFixture.class);
                if("net/minecraft/client/renderer/GlStateManager".equals(value))return internal(CountingGlFixture.class);
                return value;
            }
            @Override public String mapMethodName(String owner,String name,String desc) {
                return !UTIL.equals(owner)&&names.containsKey(name)?names.get(name):name;
            }
            @Override public String mapFieldName(String owner,String name,String desc) {
                return !UTIL.equals(owner)&&names.containsKey(name)?names.get(name):name;
            }
        });
    }

    private static byte[] config(String jarPath) throws Exception {
        JarFile jar=new JarFile(jarPath); ClassNode original;
        try {original=node(read(jar.getInputStream(jar.getJarEntry("Config.class"))));}
        finally {jar.close();}
        ClassNode c=new ClassNode(Opcodes.ASM5); c.version=Opcodes.V1_8;
        c.access=Opcodes.ACC_PUBLIC; c.name="Config"; c.superName="java/lang/Object";
        Set<String> methods=new HashSet<String>(Arrays.asList("getMipmapType","isMultiTexture",
            "getAnisotropicFilterLevel","getAntialiasingLevel"));
        for(MethodNode m:original.methods)if(methods.contains(m.name))c.methods.add(m);
        for(FieldNode f:original.fields)if("gameSettings".equals(f.name)||"antialiasingLevel".equals(f.name)) {
            f.access=Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC; c.fields.add(f);
        }
        return remap(c,new Remapper(){@Override public String map(String value){
            return "bid".equals(value)?internal(SettingsFixture.class):value;}});
    }
    private static byte[] remap(ClassNode n,Remapper r) {
        ClassWriter w=new ClassWriter(ClassWriter.COMPUTE_MAXS); n.accept(new ClassRemapper(w,r));return w.toByteArray();
    }
    private static ClassNode node(byte[] b) {ClassNode n=new ClassNode(Opcodes.ASM5);new ClassReader(b).accept(n,ClassReader.EXPAND_FRAMES);return n;}
    private static byte[] read(InputStream in)throws Exception {
        try {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);return out.toByteArray();}
        finally {in.close();}
    }
    private static Map<String,String> names(String zip)throws Exception {
        Map<String,String> map=new HashMap<String,String>();ZipFile z=new ZipFile(zip);
        try {for(String file:new String[]{"methods.csv","fields.csv"}) {
            BufferedReader r=new BufferedReader(new InputStreamReader(z.getInputStream(z.getEntry(file)),"UTF-8"));
            try {for(String line;(line=r.readLine())!=null;) {String[] p=line.split(",",3);if(p.length>1)map.put(p[0],p[1]);}}finally{r.close();}
        }}finally{z.close();}return map;
    }
    private static String internal(Class<?> c){return c.getName().replace('.','/');}
    private static void require(boolean b,String msg){if(!b)throw new AssertionError(msg);}
    public static final class SettingsFixture {public boolean anaglyph=false;public int ofMipmapType=3,ofAfLevel=1;}
    public static final class MinecraftFixture {
        private static final MinecraftFixture INSTANCE=new MinecraftFixture();
        public SettingsFixture gameSettings=new SettingsFixture();
        public static MinecraftFixture getMinecraft(){return INSTANCE;}
    }
    public static final class CountingGlFixture {
        public static void glTexParameteri(int target,int pname,int value){writes++;GlStateManager.glTexParameteri(target,pname,value);}
        public static void glTexSubImage2D(int target,int level,int x,int y,int w,int h,int format,int type,IntBuffer pixels){
            uploads++;GlStateManager.glTexSubImage2D(target,level,x,y,w,h,format,type,pixels);
        }
    }
    public static final class ConfigurationBridge {
        public static boolean allowParameterDeduplication(){return enabled;}
        public static boolean tryUploadLevel(int mip,int[] data,int w,int h,int x,int y,boolean b,boolean c,boolean f){return false;}
        public static boolean tryUpload(int mip,int[][] data,int w,int h,int x,int y,boolean b,boolean c,boolean f){return false;}
    }
    private static final class Result {
        final byte[][] first,pixels;final int[] parameters;final int writes,uploads;
        Result(byte[][] first,byte[][] pixels,int[] parameters,int writes,int uploads){this.first=first;this.pixels=pixels;this.parameters=parameters;this.writes=writes;this.uploads=uploads;}
    }
}
