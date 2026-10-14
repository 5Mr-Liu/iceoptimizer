package dev.rlcraft.ice.benchmark;

import dev.rlcraft.ice.optimizer.render.terrain.NativeTerrainShader;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Offline fixed-function oracle; never installed in the mod or live benchmark. */
public final class NativeTerrainShaderProbe {
    static final int W=256,H=192,N=48;
    static int program, geometry, commands, vao, indices;
    static final boolean TRIANGLES=Boolean.getBoolean("ice.probe.triangles");
    static final boolean IRREGULAR=Boolean.getBoolean("ice.probe.irregular");
    static float shiftX, shiftY, shiftZ, scale;
    static final FloatBuffer fog=BufferUtils.createFloatBuffer(4);

    public static void main(String[] args) throws Exception {
        Pbuffer context=new Pbuffer(W,H,new PixelFormat().withDepthBits(24),null,null);
        try {
            context.makeCurrent();
            program=program();
            geometry=GL15.glGenBuffers(); commands=GL15.glGenBuffers(); vao=GL30.glGenVertexArrays();
            ByteBuffer vertices=BufferUtils.createByteBuffer(N*4*28);
            for(int n=0;n<N;n++) for(int corner=0;corner<4;corner++) {
                int x=corner==1||corner==2?1:0, y=corner>=2?1:0;
                vertices.putFloat(-120+(n%8)*30+x*24).putFloat(-80+(n/8)*27+y*22)
                    .putFloat(-40-(n%9)*20-x*6+(IRREGULAR&&corner==2?7:0));
                vertices.put((byte)(100+n*3)).put((byte)(110+(IRREGULAR?corner*23:0))).put((byte)180).put((byte)255);
                vertices.putFloat(x).putFloat(y);
                vertices.putShort((short)(16+(n%12)*16+(IRREGULAR?corner*8:0))).putShort((short)(16+(n%10)*16));
            }
            vertices.flip();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,geometry);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER,vertices,GL15.GL_STATIC_DRAW);
            if(TRIANGLES) {
                indices=GL15.glGenBuffers();
                ByteBuffer indexData=BufferUtils.createByteBuffer(N*6*4);
                for(int first=0;first<N*4;first+=4)
                    indexData.putInt(first).putInt(first+1).putInt(first+2).putInt(first).putInt(first+2).putInt(first+3);
                indexData.flip();
                GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,indices);
                GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER,indexData,GL15.GL_STATIC_DRAW);
                GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,0);
            }
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
            for(float meshScale:new float[]{1F,1.000001F}) {
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
        } finally { context.destroy(); }
    }

    static byte[] render(boolean nativeDraw,int mode,int radial) {
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT);
        if(!nativeDraw) {
            GL30.glBindVertexArray(0);GL20.glUseProgram(0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,geometry);
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);GL11.glVertexPointer(3,GL11.GL_FLOAT,28,0L);
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);GL11.glColorPointer(4,GL11.GL_UNSIGNED_BYTE,28,12L);
            for(int unit=0;unit<2;unit++) {
                GL13.glClientActiveTexture(GL13.GL_TEXTURE0+unit);GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
                GL11.glTexCoordPointer(2,unit==0?GL11.GL_FLOAT:GL11.GL_SHORT,28,unit==0?16L:24L);
            }
            GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
            GL11.glPushMatrix();GL11.glTranslatef(shiftX,shiftY,shiftZ);
            GL11.glTranslatef(-8,-8,-8);GL11.glScalef(scale,scale,scale);GL11.glTranslatef(8,8,8);
            for(int n=0;n<N;n++)GL11.glDrawArrays(GL11.GL_QUADS,n*4,4);
            GL11.glPopMatrix();
        } else {
            GL30.glBindVertexArray(vao);GL20.glUseProgram(program);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,geometry);
            attribute(8,3,GL11.GL_FLOAT,false,28,0);
            attribute(9,4,GL11.GL_UNSIGNED_BYTE,true,28,12);
            attribute(10,2,GL11.GL_FLOAT,false,28,16);
            attribute(11,2,GL11.GL_SHORT,false,28,24);
            ByteBuffer data=BufferUtils.createByteBuffer(N*(TRIANGLES?36:32));
            for(int n=0;n<N;n++)data.putFloat(shiftX).putFloat(shiftY).putFloat(shiftZ).putFloat(scale);
            for(int n=0;n<N;n++) {
                if(TRIANGLES) data.putInt(6).putInt(1).putInt(0).putInt(n*4).putInt(n);
                else data.putInt(4).putInt(1).putInt(n*4).putInt(n);
            }
            data.flip();GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,commands);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER,data,GL15.GL_STREAM_DRAW);
            attribute(12,4,GL11.GL_FLOAT,false,16,0);GL33.glVertexAttribDivisor(12,1);
            GL20.glUniform1i(GL20.glGetUniformLocation(program,"iceAtlas"),0);
            GL20.glUniform1i(GL20.glGetUniformLocation(program,"iceLightmap"),1);
            GL20.glUniform1i(GL20.glGetUniformLocation(program,"iceFogMode"),mode);
            GL20.glUniform1i(GL20.glGetUniformLocation(program,"iceFogDistance"),radial);
            GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER,commands);
            if(TRIANGLES) {
                GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,indices);
                GL43.glMultiDrawElementsIndirect(GL11.GL_TRIANGLES,GL11.GL_UNSIGNED_INT,N*16L,N,20);
            } else GL43.glMultiDrawArraysIndirect(GL11.GL_QUADS,N*16L,N,16);
        }
        ByteBuffer pixels=BufferUtils.createByteBuffer(W*H*4);
        GL11.glReadPixels(0,0,W,H,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
        byte[] result=new byte[pixels.remaining()];pixels.get(result);return result;
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
        for(int i=0;i<names.length;i++)GL20.glBindAttribLocation(result,8+i,names[i]);
        GL20.glLinkProgram(result);
        if(GL20.glGetProgrami(result,GL20.GL_LINK_STATUS)==0)throw new AssertionError(GL20.glGetProgramInfoLog(result,4096));
        return result;
    }
}
