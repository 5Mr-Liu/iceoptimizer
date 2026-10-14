package dev.rlcraft.ice.benchmark;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import org.lwjgl.util.vector.Matrix4f;

/** Native fog experiment only. Reads the installed dependency; ships no foreign shader. */
public final class NothiriumFogProbe {
    private static final int W=256, H=192, NV_DISTANCE=0x855A, NV_RADIAL=0x855B, NV_ABSOLUTE=0x855C;
    private static final float R=.8F,G=.3F,B=.1F;
    private static int buffer, vertices;
    private static final FloatBuffer matrix=BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer projection=BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer model=BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer fogModel=BufferUtils.createFloatBuffer(16);
    private static boolean offsetCamera;
    private static float fogAlpha=1;
    public static void main(String[] args) throws Exception {
        if(args.length!=2) throw new IllegalArgumentException("dependency.jar output-directory");
        Path output=Paths.get(args[1]); Files.createDirectories(output);
        String vertex,fragment;
        try(ZipFile jar=new ZipFile(args[0])) {
            vertex=read(jar,"assets/nothirium/shaders/chunk_vert.glsl");
            fragment=read(jar,"assets/nothirium/shaders/chunk_frag.glsl");
        }
        String[] adjusted=dev.rlcraft.ice.hooks.NothiriumFogShaderRewrite.rewrite(vertex,fragment);
        String[] supplied=dev.rlcraft.ice.hooks.NothiriumFogShaderRewrite.rewrite(vertex+"\n",fragment+"\n");
        if(!java.util.Arrays.equals(adjusted,supplied)) throw new AssertionError("ResourceSupplier newline mismatch");
        try {dev.rlcraft.ice.hooks.NothiriumFogShaderRewrite.rewrite(vertex+"// changed",fragment);throw new AssertionError("Unknown source accepted");}
        catch(IllegalArgumentException expected) {System.out.println("unknown_source_rejected=true");}
        Display.setDisplayMode(new DisplayMode(W,H)); Display.setTitle("ICE isolated fog oracle");
        Display.create(new PixelFormat(0,24,0));
        int[] programs=new int[3]; int texture=0;
        try {
            if(!GLContext.getCapabilities().GL_NV_fog_distance) throw new IllegalStateException("NV fog-distance control unavailable");
            System.out.println("renderer="+GL11.glGetString(GL11.GL_RENDERER));
            System.out.println("This probe tests flat-color fog math, not world visibility, texture animation, or full-game FPS.");
            programs[0]=program(vertex,fragment);
            programs[1]=program(adjusted[0],adjusted[1]);
            programs[2]=program(compatibilityVertex(),null);
            texture=GL11.glGenTextures();
            ByteBuffer white=BufferUtils.createByteBuffer(4); white.putInt(-1).flip();
            for(int unit=0;unit<2;unit++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0+unit); GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,1,1,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,white);
                GL11.glEnable(GL11.GL_TEXTURE_2D);
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE0); GL11.glDisable(GL11.GL_DITHER);
            GL11.glEnable(GL11.GL_DEPTH_TEST); GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_LIGHTING); GL11.glDisable(GL11.GL_BLEND);
            GL11.glViewport(0,0,W,H); GL11.glClearColor(.1F,.1F,.1F,1);
            GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glLoadIdentity(); GL11.glOrtho(-128,128,-96,96,1,512);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            buffer=GL15.glGenBuffers();
            System.out.println("fog_mode,radial,yaw,sloped,camera_offset,fog_alpha,variant,covered_pixels,coverage_difference,channels_gt1,max_channel,mean_abs_channel");
            for(int mode=0;mode<3;mode++) for(int radial=0;radial<2;radial++)
                for(int yaw:new int[]{0,25}) for(int slope=0;slope<2;slope++)
                    for(int offset=0;offset<2;offset++) for(float alpha:new float[]{.35F,1F}) {
                    offsetCamera=offset!=0; fogAlpha=alpha;
                    mesh(slope!=0); setupFog(mode,radial);
                    GL11.glLoadIdentity(); GL11.glRotatef(yaw,0,1,0); updateMatrix();
                    byte[] reference=render(0,mode,radial);
                    for(int variant=0;variant<programs.length;variant++) {
                        byte[] candidate=render(programs[variant],mode,radial);
                        int covered=0,coverage=0,channels=0,maximum=0; long absolute=0;
                        for(int pixel=0;pixel<W*H;pixel++) {
                            int i=pixel*4; boolean a=background(reference,i),b=background(candidate,i);
                            if(a!=b) coverage++;
                            if(a||b) continue;
                            covered++;
                            for(int c=0;c<3;c++) {
                                int delta=Math.abs((reference[i+c]&255)-(candidate[i+c]&255));
                                if(delta>1) channels++; maximum=Math.max(maximum,delta); absolute+=delta;
                            }
                        }
                        if(covered<W*H/16) throw new IllegalStateException("Insufficient shared visible geometry for a fog comparison");
                        System.out.println(mode+","+radial+","+yaw+","+slope+","+offset+","+alpha+","+variant+","+covered+","+coverage+","+channels+","+maximum+","+(absolute/(double)Math.max(1,covered*3)));
                        if(mode==0&&radial==1&&yaw==0&&slope==1) {
                            png(output.resolve("fog-reference.png"),reference);
                            png(output.resolve("fog-variant-"+variant+".png"),candidate);
                        }
                        if(variant!=0&&(channels>0||coverage>0)) {
                            String key="fog-"+mode+"-"+radial+"-"+yaw+"-"+slope+"-"+offset+"-"+alpha;
                            png(output.resolve(key+"-reference.png"),reference);
                            png(output.resolve(key+"-variant-"+variant+".png"),candidate);
                            throw new AssertionError("Fog oracle mismatch: "+key+" variant="+variant+" channels="+channels+" coverage="+coverage);
                        }
                    }
                }
            if(GL11.glGetError()!=GL11.GL_NO_ERROR) throw new IllegalStateException("OpenGL error during fog probe");
            System.out.println("COMPLETE variants=0:dependency,1:linear_eye_distance,2:vertex_only_fixed_fragment");
        } finally {
            GL20.glUseProgram(0);
            for(int p:programs) if(p!=0) GL20.glDeleteProgram(p);
            if(buffer!=0) GL15.glDeleteBuffers(buffer);
            if(texture!=0) GL11.glDeleteTextures(texture);
            Display.destroy();
        }
    }
    private static String read(ZipFile zip,String path) throws IOException {
        try(InputStream in=zip.getInputStream(zip.getEntry(path));ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[4096]; int n; while((n=in.read(b))>=0) out.write(b,0,n);
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    private static int program(String vertex,String fragment) {
        int p=GL20.glCreateProgram(); int[] shaders=new int[2];
        try {
            String[] sources={vertex,fragment};
            for(int i=0;i<2;i++) if(sources[i]!=null) {
                shaders[i]=GL20.glCreateShader(i==0?GL20.GL_VERTEX_SHADER:GL20.GL_FRAGMENT_SHADER);
                GL20.glShaderSource(shaders[i],sources[i]); GL20.glCompileShader(shaders[i]);
                if(GL20.glGetShaderi(shaders[i],GL20.GL_COMPILE_STATUS)==0) throw new IllegalStateException(GL20.glGetShaderInfoLog(shaders[i],8192));
                GL20.glAttachShader(p,shaders[i]);
            }
            String[] names={"a_Pos","a_Color","a_TexCoord","a_LightCoord","a_Offset"};
            for(int i=0;i<names.length;i++) GL20.glBindAttribLocation(p,i,names[i]);
            GL20.glLinkProgram(p);
            if(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)==0) throw new IllegalStateException(GL20.glGetProgramInfoLog(p,8192));
            return p;
        } catch(RuntimeException failure) { GL20.glDeleteProgram(p); throw failure; }
        finally { for(int s:shaders) if(s!=0) GL20.glDeleteShader(s); }
    }
    private static void mesh(boolean slope) {
        FloatBuffer data=BufferUtils.createFloatBuffer(16*12*4*3);
        for(int y=0;y<12;y++) for(int x=0;x<16;x++) {
            float z=-32-((x+y)%12)*22;
            for(int[] corner:new int[][]{{0,0},{1,0},{1,1},{0,1}})
                data.put(-128+x*16+corner[0]*16).put(-96+y*16+corner[1]*16).put(z-(slope?corner[0]*18:0));
        }
        vertices=data.position()/3; data.flip(); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,buffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER,data,GL15.GL_STATIC_DRAW);
    }
    private static void setupFog(int mode,int radial) {
        GL11.glEnable(GL11.GL_FOG); GL11.glFogi(GL11.GL_FOG_MODE,new int[]{GL11.GL_LINEAR,GL11.GL_EXP,GL11.GL_EXP2}[mode]);
        GL11.glFogf(GL11.GL_FOG_START,80); GL11.glFogf(GL11.GL_FOG_END,256); GL11.glFogf(GL11.GL_FOG_DENSITY,.009F);
        FloatBuffer color=BufferUtils.createFloatBuffer(4); color.put(new float[]{.5F,.65F,.9F,fogAlpha}).flip();
        GL11.glFog(GL11.GL_FOG_COLOR,color); GL11.glFogi(NV_DISTANCE,radial==1?NV_RADIAL:NV_ABSOLUTE);
        GL11.glHint(GL11.GL_FOG_HINT,GL11.GL_NICEST);
    }
    private static void updateMatrix() {
        projection.clear(); model.clear(); GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX,projection); GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX,model);
        Matrix4f p=new Matrix4f();p.load(projection); Matrix4f m=new Matrix4f();m.load(model);
        if(offsetCamera) m.translate(new org.lwjgl.util.vector.Vector3f(3.25F,1.6F,-8.5F));
        fogModel.clear();m.store(fogModel);fogModel.flip();
        matrix.clear(); Matrix4f.mul(p,m,null).store(matrix); matrix.flip();
    }
    private static byte[] render(int p,int mode,int radial) {
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT); GL20.glUseProgram(p);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,buffer);
        if(p==0) {
            GL20.glDisableVertexAttribArray(0);
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glVertexPointer(3,GL11.GL_FLOAT,12,0);
            // Generic attributes alias compatibility current state on this driver.
            // Reset secondary color and fog coordinate after the shader arms so
            // a previous camera offset cannot contaminate the next oracle.
            GL11.glDisable(GL14.GL_COLOR_SUM);
            GL14.glSecondaryColor3f(0,0,0);
            GL14.glFogCoordf(0);
            GL11.glColor4f(R,G,B,1);
        }
        else {
            GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY); GL20.glEnableVertexAttribArray(0); GL20.glVertexAttribPointer(0,3,GL11.GL_FLOAT,false,12,0);
            GL20.glVertexAttrib4f(1,R,G,B,1);GL20.glVertexAttrib2f(2,.5F,.5F);GL20.glVertexAttrib2f(3,120,120);
            GL20.glVertexAttrib3f(4,offsetCamera?-3.25F:0,offsetCamera?-1.6F:0,offsetCamera?8.5F:0);
            GL20.glUniformMatrix4(GL20.glGetUniformLocation(p,"u_ModelViewProjectionMatrix"),false,matrix);
            GL20.glUniformMatrix4(GL20.glGetUniformLocation(p,"iceFogModelView"),false,fogModel);
            uniform(p,"u_BlockTex",0);uniform(p,"u_LightTex",1);uniform(p,"u_FogEnabled",1);uniform(p,"u_FogShape",0);uniform(p,"u_FogMode",mode);uniform(p,"iceFogDistance",radial);
            GL20.glUniform1f(GL20.glGetUniformLocation(p,"u_FogStart"),80);GL20.glUniform1f(GL20.glGetUniformLocation(p,"u_FogEnd"),256);GL20.glUniform1f(GL20.glGetUniformLocation(p,"u_FogDensity"),.009F);
            GL20.glUniform4f(GL20.glGetUniformLocation(p,"u_FogColor"),.5F,.65F,.9F,fogAlpha);
        }
        GL11.glDrawArrays(GL11.GL_QUADS,0,vertices); ByteBuffer pixels=BufferUtils.createByteBuffer(W*H*4);
        GL11.glReadPixels(0,0,W,H,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels); byte[] result=new byte[pixels.remaining()];pixels.get(result);return result;
    }
    private static void uniform(int p,String name,int value) { GL20.glUniform1i(GL20.glGetUniformLocation(p,name),value); }
    private static boolean background(byte[] p,int i) { return (p[i]&255)==26&&(p[i+1]&255)==26&&(p[i+2]&255)==26; }
    private static void png(Path path,byte[] p) throws IOException {
        BufferedImage image=new BufferedImage(W,H,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<H;y++) for(int x=0;x<W;x++) {int i=(y*W+x)*4;image.setRGB(x,H-1-y,0xFF000000|((p[i]&255)<<16)|((p[i+1]&255)<<8)|(p[i+2]&255));}
        ImageIO.write(image,"png",path.toFile());
    }
    private static String compatibilityVertex() {
        return "#version 120\nuniform mat4 u_ModelViewProjectionMatrix,iceFogModelView; uniform int iceFogDistance;\n"
            +"attribute vec3 a_Pos,a_Offset; attribute vec4 a_Color; attribute vec2 a_TexCoord,a_LightCoord;\n"
            +"void main(){ vec4 position=vec4(a_Pos+a_Offset,1.0); vec4 eye=iceFogModelView*position;\n"
            +"gl_Position=u_ModelViewProjectionMatrix*position; gl_FrontColor=a_Color; gl_BackColor=a_Color;\n"
            +"gl_TexCoord[0]=gl_TextureMatrix[0]*vec4(a_TexCoord,0.0,1.0);\n"
            +"gl_TexCoord[1]=gl_TextureMatrix[1]*vec4(a_LightCoord,0.0,1.0);\n"
            +"gl_FogFragCoord=iceFogDistance==1?length(eye.xyz):-eye.z; }\n";
    }
}
