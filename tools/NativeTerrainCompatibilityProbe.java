package dev.rlcraft.ice.optimizer.render.terrain;

import dev.rlcraft.ice.optimizer.render.terrain.NativeTerrainShader;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Offscreen GL 2.1 API probe: production backend vs ordinary shader draws. */
public final class NativeTerrainCompatibilityProbe {
    static final int W=256,H=192,N=48;
    static int program, geometry, sentinel;
    static NativeTerrainBackend backend;
    static final int POSITION_ATTRIBUTE=8;
    static NativeTerrainAllocation[] allocations=new NativeTerrainAllocation[N];
    static final boolean IRREGULAR=true;
    static boolean reverse;
    static final boolean MULTI=Boolean.getBoolean("ice.probe.multitexture");
    static int[] textures=new int[3];
    static float shiftX, shiftY, shiftZ, scale;
    static final FloatBuffer fog=BufferUtils.createFloatBuffer(4);

    public static void main(String[] args) throws Exception {
        Pbuffer context=new Pbuffer(W,H,new PixelFormat().withDepthBits(24),null,null);
        try {
            context.makeCurrent();
            System.out.println("renderer="+GL11.glGetString(GL11.GL_RENDERER)+" version="+GL11.glGetString(GL11.GL_VERSION));
            NativeTerrainStartup.initialize();
            backend=Boolean.getBoolean("ice.probe.gl43") ? new NativeTerrainGl43(28) : new NativeTerrainGl21(28);
            program=program();
            geometry=GL15.glGenBuffers(); sentinel=GL15.glGenBuffers();
            ByteBuffer vertices=BufferUtils.createByteBuffer(N*4*28);
            for(int n=0;n<N;n++) for(int corner=0;corner<4;corner++) {
                int x=corner==1||corner==2?1:0, y=corner>=2?1:0;
                vertices.putFloat(-120+(n%8)*30+x*24).putFloat(-70+((n/8)%3)*48+y*40)
                    .putFloat(-40-(n/24)*80-x*6+(IRREGULAR&&corner==2?7:0));
                vertices.put((byte)(100+n*3)).put((byte)(110+(IRREGULAR?corner*23:0))).put((byte)180).put((byte)255);
                vertices.putFloat(x).putFloat(y);
                vertices.putShort((short)(16+(n%12)*16+(IRREGULAR?corner*8:0))).putShort((short)(16+(n%10)*16));
            }
            vertices.flip();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,geometry);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER,vertices,GL15.GL_STATIC_DRAW);
            // Put draws at nonzero offsets in two fixed pages, interleaved in
            // submission order so transparent correctness cannot rely on sorting by page.
            backend.upload(BufferUtils.createByteBuffer(70000*28),70000);
            for(int pass=0;pass<2;pass++) {
                if(pass==1) backend.upload(BufferUtils.createByteBuffer(65532*28),65532);
                for(int n=pass;n<N;n+=2) {
                    ByteBuffer slice=vertices.duplicate(); slice.position(n*112); slice.limit((n+1)*112);
                    allocations[n]=backend.upload(slice,4);
                }
            }
            ByteBuffer indices=BufferUtils.createByteBuffer(N*8);
            for(int n=0;n<N;n++)for(int corner=0;corner<4;corner++)indices.putShort((short)corner);
            indices.flip();
            int[] copies=new int[N*3];
            for(int n=0;n<N;n++){copies[n*3]=n*8;copies[n*3+1]=allocations[n].indexFirst(0);copies[n*3+2]=8;}
            backend.uploadIndices(indices,copies,N);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,geometry);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,sentinel);
            for(int unit=0;unit<2;unit++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);
                int texture=GL11.glGenTextures(); GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
                ByteBuffer pixels=BufferUtils.createByteBuffer(16*16*4);
                for(int y=0;y<16;y++)for(int x=0;x<16;x++)
                    pixels.put((byte)(unit==0?80+x*10:150+x*6))
                        .put((byte)(unit==0?220:120+y*8)).put((byte)(120+y*8))
                        .put((byte)(unit==0&&((x/4+y/4)&1)==0?70:255));
                pixels.flip();
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,16,16,0,
                    GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL11.glTexEnvi(GL11.GL_TEXTURE_ENV,GL11.GL_TEXTURE_ENV_MODE,GL11.GL_MODULATE);
                GL11.glMatrixMode(GL11.GL_TEXTURE); GL11.glLoadIdentity();
                if(unit==1) {GL11.glScalef(1F/256,1F/256,1); GL11.glTranslatef(8,8,0);}
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            if(MULTI) for(int material=0;material<textures.length;material++) {
                textures[material]=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,textures[material]);
                ByteBuffer pixels=BufferUtils.createByteBuffer(16*16*4);
                for(int y=0;y<16;y++)for(int x=0;x<16;x++)
                    pixels.put((byte)(40+material*70+x*2)).put((byte)(220-material*60))
                        .put((byte)(80+y*9)).put((byte)(((x/4+y/4+material)&1)==0?70:255));
                pixels.flip();GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,16,16,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
                if(GLContext.getCapabilities().GL_EXT_texture_filter_anisotropic)
                    GL11.glTexParameterf(GL11.GL_TEXTURE_2D,0x84FE,8F);
            }
            System.out.println("multitexture="+MULTI);
            GL11.glDisable(GL11.GL_DITHER); GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glShadeModel(Boolean.getBoolean("ice.probe.flat")?GL11.GL_FLAT:GL11.GL_SMOOTH);
            GL11.glDisable(GL14.GL_COLOR_SUM); GL14.glSecondaryColor3f(0,0,0); GL14.glFogCoordf(0);
            GL11.glEnable(GL11.GL_DEPTH_TEST); GL11.glClearColor(0,0,0,0);
            GL11.glViewport(0,0,W,H);
            GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glLoadIdentity(); GL11.glOrtho(-128,128,-96,96,1,512);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            int cases=0, maxDelta=0, changed=0;
            for(int mode:new int[]{0,GL11.GL_LINEAR,GL11.GL_EXP,GL11.GL_EXP2})
            for(int radial=0;radial<2;radial++) for(int yaw:new int[]{0,25})
            for(int offset=0;offset<2;offset++) for(int alpha=0;alpha<3;alpha++)
            for(float meshScale:new float[]{1F,1.000001F}) for(boolean transparent:new boolean[]{false,true}) {
                reverse=transparent;
                if(radial!=0 && !GLContext.getCapabilities().GL_NV_fog_distance) continue;
                shiftX=offset==0?0:3.25F;shiftY=offset==0?0:1.6F;shiftZ=offset==0?0:-8.5F;
                scale=meshScale;
                GL11.glLoadIdentity(); GL11.glRotatef(yaw,0,1,0);
                if(mode==0) GL11.glDisable(GL11.GL_FOG); else {
                    GL11.glEnable(GL11.GL_FOG);GL11.glFogi(GL11.GL_FOG_MODE,mode);
                }
                GL11.glFogf(GL11.GL_FOG_START,60);GL11.glFogf(GL11.GL_FOG_END,220);
                GL11.glFogf(GL11.GL_FOG_DENSITY,.009F);
                fog.clear();fog.put(new float[]{.5F,.65F,.9F,.35F}).flip();GL11.glFog(GL11.GL_FOG_COLOR,fog);
                if(GLContext.getCapabilities().GL_NV_fog_distance) GL11.glFogi(0x855A,radial==1?0x855B:0x855C);
                GL11.glHint(GL11.GL_FOG_HINT,GL11.GL_NICEST);
                if(alpha==1) {GL11.glEnable(GL11.GL_ALPHA_TEST);GL11.glAlphaFunc(GL11.GL_GREATER,.5F);}
                else GL11.glDisable(GL11.GL_ALPHA_TEST);
                if(alpha==2) {GL11.glEnable(GL11.GL_BLEND);GL11.glBlendFunc(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA);}
                else GL11.glDisable(GL11.GL_BLEND);
                byte[] reference=render(false,mode,radial), candidate=render(true,mode,radial);
                boolean visible=false;
                for(int i=0;i<reference.length;i+=4) if(reference[i]!=0 || reference[i+1]!=0 || reference[i+2]!=0) {visible=true;break;}
                if(!visible) throw new AssertionError("Reference produced no geometry");
                int max=0,bad=0;
                for(int i=0;i<reference.length;i++) {
                    int d=Math.abs((reference[i]&255)-(candidate[i]&255)); max=Math.max(max,d);
                    if(d>1)bad++;
                }
                System.out.println("case="+cases+" mode="+mode+" radial="+radial+" yaw="+yaw+" offset="+offset+" alpha="+alpha+" scale="+scale+" max="+max+" channels_gt1="+bad);
                maxDelta=Math.max(maxDelta,max); changed+=bad; cases++;
                if(max>2 || bad>16) throw new AssertionError("Native terrain image mismatch");
            }
            if(GL11.glGetError()!=0)throw new AssertionError("GL error");
            System.out.println("COMPLETE cases="+cases+" max_channel_delta="+maxDelta+" channels_gt1="+changed);
        } finally { if(backend!=null)backend.delete(); context.destroy(); }
    }

    static byte[] render(boolean nativeDraw,int mode,int radial) {
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT);
        if (!nativeDraw) {
            GL20.glUseProgram(program); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,geometry);
            attribute(8,3,GL11.GL_FLOAT,false,28,0);
            attribute(9,4,GL11.GL_UNSIGNED_BYTE,true,28,12);
            attribute(10,2,GL11.GL_FLOAT,false,28,16);
            attribute(11,2,GL11.GL_SHORT,false,28,24);
            GL20.glDisableVertexAttribArray(12); GL20.glVertexAttrib4f(12,shiftX,shiftY,shiftZ,scale);
            GL20.glUniform1i(GL20.glGetUniformLocation(program,"iceAtlas"),0);
            GL20.glUniform1i(GL20.glGetUniformLocation(program,"iceLightmap"),1);
            GL20.glUniform1i(GL20.glGetUniformLocation(program,"iceFogMode"),mode);
            GL20.glUniform1i(GL20.glGetUniformLocation(program,"iceFogDistance"),radial);
            for(int n=0;n<N;n++) {
                int quad=reverse?N-1-n:n;
                if(MULTI)GL11.glBindTexture(GL11.GL_TEXTURE_2D,textures[(quad/2)%3]);
                GL11.glDrawArrays(GL11.GL_QUADS,quad*4,4);
            }
        } else {
            NativeTerrainFrameData frame=new NativeTerrainFrameData();
            int layer=reverse?3:0;
            if(reverse)frame.add(0,4,allocations[0].indexFirst(0),allocations[0].vertexFirst(0),shiftX+30,shiftY,shiftZ,scale);
            for(int n=0;n<N;n++)frame.add(layer,4,allocations[n].indexFirst(0),allocations[n].vertexFirst(0),shiftX,shiftY,shiftZ,scale,MULTI?textures[(n/2)%3]:-1);
            int[] words=new int[frame.size()*9]; frame.pack(words); backend.drawMaterials=frame.materials();backend.prepare(words,words.length*4);
            GL13.glClientActiveTexture(GL13.GL_TEXTURE1);
            int[] before=state();
            backend.submit(words,frame.size()*4,frame.starts[layer],frame.counts[layer]);
            if(!java.util.Arrays.equals(before,state()))throw new AssertionError("GL state leaked");
        }
        int error=GL11.glGetError();
        if(error!=0)throw new AssertionError("draw GL error="+error+" native="+nativeDraw);
        ByteBuffer pixels=BufferUtils.createByteBuffer(W*H*4);
        GL11.glReadPixels(0,0,W,H,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
        byte[] result=new byte[pixels.remaining()];pixels.get(result);return result;
    }
    static int[] state() {
        int[] state=new int[30]; int at=0;
        for(int p:new int[]{GL20.GL_CURRENT_PROGRAM,GL15.GL_ARRAY_BUFFER_BINDING,
                GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING,GL13.GL_CLIENT_ACTIVE_TEXTURE,
                GL13.GL_ACTIVE_TEXTURE,GL11.GL_TEXTURE_BINDING_2D})state[at++]=GL11.glGetInteger(p);
        java.nio.IntBuffer value=BufferUtils.createIntBuffer(4);
        for(int a=8;a<=11;a++)for(int p:new int[]{GL20.GL_VERTEX_ATTRIB_ARRAY_ENABLED,
                GL20.GL_VERTEX_ATTRIB_ARRAY_SIZE,GL20.GL_VERTEX_ATTRIB_ARRAY_STRIDE,
                GL20.GL_VERTEX_ATTRIB_ARRAY_TYPE,GL20.GL_VERTEX_ATTRIB_ARRAY_NORMALIZED,
                GL15.GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING}) {
            value.clear(); GL20.glGetVertexAttrib(a,p,value); state[at++]=value.get(0);
        }
        return state;
    }
    public static void attribute(int index,int size,int type,boolean normalized,int stride,long offset) {
        GL20.glEnableVertexAttribArray(index);GL20.glVertexAttribPointer(index,size,type,normalized,stride,offset);
    }
    public static int program() {
        int result=GL20.glCreateProgram();
        String[] source={NativeTerrainShader.vertex(),NativeTerrainShader.fragment()};
        for(int i=0;i<2;i++) {
            int s=GL20.glCreateShader(i==0?GL20.GL_VERTEX_SHADER:GL20.GL_FRAGMENT_SHADER);
            GL20.glShaderSource(s,source[i]);GL20.glCompileShader(s);
            if(GL20.glGetShaderi(s,GL20.GL_COMPILE_STATUS)==0)throw new AssertionError(GL20.glGetShaderInfoLog(s,4096));
            GL20.glAttachShader(result,s);GL20.glDeleteShader(s);
        }
        String[] names={"icePosition","iceColor","iceUv","iceLight","iceOffset"};
        for(int i=0;i<names.length;i++)GL20.glBindAttribLocation(result,i==0?POSITION_ATTRIBUTE:8+i,names[i]);
        GL20.glLinkProgram(result);
        if(GL20.glGetProgrami(result,GL20.GL_LINK_STATUS)==0)throw new AssertionError(GL20.glGetProgramInfoLog(result,4096));
        return result;
    }
}
