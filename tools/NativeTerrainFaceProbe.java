package dev.rlcraft.ice.optimizer.render.terrain;

import dev.rlcraft.ice.benchmark.NativeTerrainShaderProbe;
import dev.rlcraft.ice.benchmark.TerrainSceneData;
import java.nio.*;
import java.util.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Captured world meshes, actual native shader, ordinary vs face-bucket MDI. Test only. */
public final class NativeTerrainFaceProbe {
    static final int W=1920,H=1080;
    static final boolean ORDERED=Boolean.getBoolean("ice.probe.orderedFaces");
    static final float SCALE=Float.parseFloat(System.getProperty("ice.probe.scale","1"));
    static final class Mesh { TerrainSceneData.Section source; TerrainFaceRanges faces; NativeTerrainFaceIndex indexFaces; int first; }
    static final List<Mesh> meshes=new ArrayList<Mesh>();
    static TerrainSceneData scene;
    static int original,grouped,stream,vao,program,indexBuffer,totalVertices;
    static long submitted;
    static FloatBuffer matrix=BufferUtils.createFloatBuffer(16);
    static double[] eye=new double[3];
    static float[] view=new float[16];

    public static void main(String[] args)throws Exception {
        scene=TerrainSceneData.read(args[0]);
        Pbuffer context=new Pbuffer(W,H,new PixelFormat().withDepthBits(24),null,null);
        try {
            context.makeCurrent();program=NativeTerrainShaderProbe.program();
            original=GL15.glGenBuffers();grouped=GL15.glGenBuffers();stream=GL15.glGenBuffers();vao=GL30.glGenVertexArrays();indexBuffer=GL15.glGenBuffers();
            int bytes=0;for(TerrainSceneData.Section s:scene.sections)if(s.owned)bytes+=s.data.length;
            for(int buffer:new int[]{original,grouped}) {GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,buffer);GL15.glBufferData(GL15.GL_ARRAY_BUFFER,bytes,GL15.GL_STATIC_DRAW);}
            int cursor=0;
            for(TerrainSceneData.Section s:scene.sections)if(s.owned) {
                Mesh m=new Mesh();m.source=s;m.first=cursor/28;
                ByteBuffer raw=BufferUtils.createByteBuffer(s.data.length);raw.put(s.data).flip();
                m.indexFaces=new NativeTerrainFaceIndex(raw,SCALE,8*SCALE-8);
                ByteBuffer sorted=BufferUtils.createByteBuffer(TerrainFaceRanges.storageBytes(s.data.length));
                m.faces=TerrainFaceRanges.group(raw,sorted,s.data.length);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,original);GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER,cursor,raw);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,grouped);GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER,cursor,sorted);
                cursor+=s.data.length;meshes.add(m);
            }
            totalVertices=cursor/28;
            for(int unit=0;unit<2;unit++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);int t=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,t);
                int size=unit==0?64:16;ByteBuffer texels=BufferUtils.createByteBuffer(size*size*4);
                for(int y=0;y<size;y++)for(int x=0;x<size;x++) texels.put((byte)(unit==0?x*17:40+x*13))
                    .put((byte)(unit==0?y*29:40+y*13)).put((byte)(80+x*5+y*5))
                    .put((byte)(unit==0&&((x/3+y/5)&1)==0?0:255));
                texels.flip();GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,size,size,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,texels);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
                GL11.glMatrixMode(GL11.GL_TEXTURE);GL11.glLoadIdentity();if(unit==1){GL11.glScalef(1F/256,1F/256,1);GL11.glTranslatef(8,8,0);}
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE0);GL20.glUseProgram(program);
            GL20.glUniform1i(GL20.glGetUniformLocation(program,"iceAtlas"),0);GL20.glUniform1i(GL20.glGetUniformLocation(program,"iceLightmap"),1);
            GL20.glUniform1i(GL20.glGetUniformLocation(program,"iceFogMode"),0);GL20.glUniform1i(GL20.glGetUniformLocation(program,"iceFogDistance"),0);
            GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_LEQUAL);GL11.glEnable(GL11.GL_CULL_FACE);
            GL11.glCullFace(GL11.GL_BACK);GL11.glFrontFace(GL11.GL_CCW);GL11.glDisable(GL11.GL_BLEND);GL11.glDisable(GL11.GL_DITHER);
            if(scene.layer.equals("TRANSLUCENT")){GL11.glEnable(GL11.GL_BLEND);GL11.glBlendFunc(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA);GL11.glDepthMask(false);}
            GL11.glEnable(GL11.GL_ALPHA_TEST);GL11.glAlphaFunc(GL11.GL_GREATER,.1F);
            GL11.glViewport(0,0,W,H);GL11.glClearColor(.1F,.15F,.2F,1);
            GL11.glMatrixMode(GL11.GL_PROJECTION);load(scene.projection);GL11.glMatrixMode(GL11.GL_MODELVIEW);
            int cases=0;
            for(int shade:new int[]{GL11.GL_SMOOTH,GL11.GL_FLAT}) for(int yaw:new int[]{-90,-45,0,45,90,180}) for(int shift:new int[]{-32,0,32}) {
                GL11.glShadeModel(shade);GL11.glLoadIdentity();GL11.glRotatef(yaw,0,1,0);
                matrix.clear();matrix.put(scene.model).flip();GL11.glMultMatrix(matrix);GL11.glTranslatef(shift,0,0);
                matrix.clear();GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX,matrix);matrix.get(view);
                if(!TerrainCamera.eye(view,eye))throw new AssertionError("camera matrix");
                draw(0);byte[] before=pixels();long full=submitted;
                draw(ORDERED?0:1);byte[] groupedOnly=pixels();
                draw(ORDERED?3:2);byte[] after=pixels();long culled=submitted;int changed=0,max=0,groupChanges=0,cullChanges=0;
                for(int i=0;i<before.length;i++){int d=Math.abs((before[i]&255)-(after[i]&255));if(d!=0)changed++;max=Math.max(max,d);}
                for(int i=0;i<before.length;i++){if(before[i]!=groupedOnly[i])groupChanges++;if(groupedOnly[i]!=after[i])cullChanges++;}
                System.out.println("case="+cases+" shade="+shade+" yaw="+yaw+" shift="+shift+" vertices="+full+"/"+culled+" changed_channels="+changed+" max="+max+" grouping_changes="+groupChanges+" culling_changes="+cullChanges);
                if(changed!=0)throw new AssertionError("Face grouping/culling changed pixels");cases++;
            }
            if(GL11.glGetError()!=0)throw new AssertionError("GL error");
            System.out.println("COMPLETE layer="+scene.layer+" cases="+cases+" exact_pixels=true");
        } finally {context.destroy();}
    }

    static void draw(int mode) {
        NativeTerrainFrameData frame=new NativeTerrainFrameData();submitted=0;
        ByteBuffer indexData=mode==3?BufferUtils.createByteBuffer(totalVertices*2):null;
        for(Mesh m:meshes) {
            float x=(float)(m.source.region[0]-scene.camera[0]),y=(float)(m.source.region[1]-scene.camera[1]),z=(float)(m.source.region[2]-scene.camera[2]);
            if(mode==3) {
                int mask=scene.layer.equals("TRANSLUCENT")?m.indexFaces.allFaces():m.indexFaces.visibleMask(eye[0]-x,eye[1]-y,eye[2]-z);
                for(int part=0;part<m.source.vertices;part+=65532) {
                    int firstIndex=indexData.position()/2;
                    int count=m.indexFaces.writeShortIndices(indexData,mask,part,Math.min(65532,m.source.vertices-part));
                    if(count!=0)frame.add(0,count,firstIndex,m.first+part,x,y,z,SCALE);submitted+=count;
                }
            } else if(mode==2) {
                float translation=8*SCALE-8;
                int plan=m.faces.submissionPlan((eye[0]-x-translation)/SCALE,(eye[1]-y-translation)/SCALE,(eye[2]-z-translation)/SCALE);
                for(int left=plan>>>24;left>0;left--,plan>>>=6) {int count=m.faces.spanCount(plan);if(count!=0){frame.add(0,count,m.first+m.faces.spanFirst(plan),x,y,z,SCALE);submitted+=count;}}
            } else {frame.add(0,m.source.vertices,m.first,x,y,z,SCALE);submitted+=m.source.vertices;}
        }
        int count=frame.size();int[] words=new int[count*9];frame.pack(words);
        if(mode==3) {
            indexData.flip();
        } else for(int i=0;i<count;i++)words[count*4+i*5+3]=i;
        ByteBuffer commands=BufferUtils.createByteBuffer(words.length*4);commands.asIntBuffer().put(words);
        GL11.glDepthMask(true);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT);
        if(scene.layer.equals("TRANSLUCENT"))GL11.glDepthMask(false);
        GL30.glBindVertexArray(vao);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,mode==1||mode==2?grouped:original);
        NativeTerrainShaderProbe.attribute(8,3,GL11.GL_FLOAT,false,28,0);NativeTerrainShaderProbe.attribute(9,4,GL11.GL_UNSIGNED_BYTE,true,28,12);
        NativeTerrainShaderProbe.attribute(10,2,GL11.GL_FLOAT,false,28,16);NativeTerrainShaderProbe.attribute(11,2,GL11.GL_SHORT,false,28,24);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,stream);GL15.glBufferData(GL15.GL_ARRAY_BUFFER,commands,GL15.GL_STREAM_DRAW);
        NativeTerrainShaderProbe.attribute(12,4,GL11.GL_FLOAT,false,16,0);GL33.glVertexAttribDivisor(12,1);
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER,stream);
        if(mode==3){GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,indexBuffer);GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER,indexData,GL15.GL_STREAM_DRAW);GL43.glMultiDrawElementsIndirect(GL11.GL_QUADS,GL11.GL_UNSIGNED_SHORT,count*16L,count,20);}
        else GL43.glMultiDrawArraysIndirect(GL11.GL_QUADS,count*16L,count,20);
    }
    static void load(float[] values){matrix.clear();matrix.put(values).flip();GL11.glLoadMatrix(matrix);}
    static byte[] pixels(){ByteBuffer b=BufferUtils.createByteBuffer(W*H*4);GL11.glReadPixels(0,0,W,H,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,b);byte[] a=new byte[b.remaining()];b.get(a);return a;}
}
