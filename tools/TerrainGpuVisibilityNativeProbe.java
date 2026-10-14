package dev.rlcraft.ice.optimizer.render.visibility;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.resource.*;
import dev.rlcraft.ice.optimizer.render.terrain.LwjglTerrainGpuVisibility;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Hidden native conservative-coverage, command layout and foreign-state probe. */
public final class TerrainGpuVisibilityNativeProbe {
    private static final FrameStamp STAMP = new FrameStamp(1, 1, new ClientEpochs().snapshot());
    private static final float[] IDENTITY = {1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
    private static final long MIB = 1024L * 1024L;
    private static int assertions;

    public static void main(String[] args) {
        Pbuffer context = null;
        try {
            context = new Pbuffer(64,64,new PixelFormat().withDepthBits(24),null,null);
            context.makeCurrent();
            run();
            System.out.println("terrain_gpu_visibility_native=true assertions="+assertions+" renderer="+GL11.glGetString(GL11.GL_RENDERER));
        } catch (Throwable failure) { failure.printStackTrace(); throw new RuntimeException(failure); }
        finally { if(context!=null)context.destroy(); }
    }

    private static void run() throws Exception {
        CacheBudget budget = new CacheBudget(4*MIB,4*MIB,4*MIB);
        ResourceLedger ledger = new ResourceLedger(RenderThreadGuard.captureCurrent(),budget,(kind,id)->{
            if(kind==RenderResourceKind.BUFFER)GL15.glDeleteBuffers(id);
            else if(kind==RenderResourceKind.PROGRAM)GL20.glDeleteProgram(id);
            else throw new AssertionError(kind);
        },128);
        LwjglTerrainGpuVisibility gpu = new LwjglTerrainGpuVisibility(ledger,budget);
        CacheBudget.Reservation pressure = budget.tryReserve(BudgetKind.GPU,budget.availableBytes(BudgetKind.GPU));
        require(pressure!=null&&budget.availableBytes(BudgetKind.GPU)==0,"zero-headroom setup");
        int commandBuffer=GL15.glGenBuffers();
        int[] foreign=new int[5];
        int foreignProgram=program();
        int alignment=GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT);
        for(int i=0;i<foreign.length;i++) {
            foreign[i]=GL15.glGenBuffers(); GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,foreign[i]);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,alignment*8,GL15.GL_STATIC_DRAW);
            if(i<4)GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,i,foreign[i],alignment,alignment*3);
        }
        GL20.glUseProgram(foreignProgram);
        ByteBuffer bounds=BufferUtils.createByteBuffer(6*64);
        box(bounds,-.2,-.2,.6,.2,.2,.7,true); // hidden
        box(bounds,-.2,-.2,-.6,.2,.2,-.5,true); // nearer
        box(bounds,-.99,-.1,.6,-.85,.1,.7,true); // outside wall
        box(bounds,-.2,-.2,-1.1,.2,.2,.7,true); // clipping ambiguity
        box(bounds,-.2,-.2,.6,.2,.2,.7,false); // unknown
        box(bounds,-.2,-.2,0,.2,.2,.01,true); // coplanar conservative
        bounds.flip();
        ByteBuffer faces=BufferUtils.createByteBuffer(128);
        quad(faces,-.8,-.8,.8,.8,0,false); faces.flip();
        try {
            for(int stride:new int[]{16,112}) {
                int[] result=dispatch(gpu,commandBuffer,bounds,faces,6,1,stride,IDENTITY,IDENTITY);
                require(result[0]==0,"covered candidate");
                for(int i=1;i<result.length;i++)require(result[i]==3,"visible candidate "+i);
                state(foreign,foreignProgram,alignment);
            }
            pressure.close();
            // Fresh input and depth on every call, including moving the view and missing faces.
            ByteBuffer noFaces=BufferUtils.createByteBuffer(0);
            int[] result=dispatch(gpu,commandBuffer,bounds,noFaces,6,0,16,IDENTITY,IDENTITY);
            for(int value:result)require(value==3,"no stale depth");
            faces.clear();quad(faces,-.8,-.8,.8,.8,0,true);faces.flip();
            require(dispatch(gpu,commandBuffer,bounds,faces,6,1,16,IDENTITY,IDENTITY)[0]==3,"backface is not coverage");
            ByteBuffer hole=BufferUtils.createByteBuffer(256);
            quad(hole,-.8,-.8,-.04,.8,0,false);quad(hole,.04,-.8,.8,.8,0,false);hole.flip();
            require(dispatch(gpu,commandBuffer,bounds,hole,6,2,16,IDENTITY,IDENTITY)[0]==3,"hole remains visible");
            ByteBuffer nearFace=BufferUtils.createByteBuffer(128);
            point(nearFace,-.8,-.8,-1.1);point(nearFace,.8,-.8,0);point(nearFace,.8,.8,0);point(nearFace,-.8,.8,-1.1);nearFace.flip();
            require(dispatch(gpu,commandBuffer,bounds,nearFace,6,1,16,IDENTITY,IDENTITY)[0]==3,"near-clipped face not coverage");
            randomized(gpu,commandBuffer);
            state(foreign,foreignProgram,alignment);
            for(int i=0;i<4;i++)GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,i,0);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,0);GL20.glUseProgram(0);
            timing(gpu,commandBuffer);
            for(int i=0;i<4;i++)GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,i,foreign[i],alignment,alignment*3);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,foreign[4]);GL20.glUseProgram(foreignProgram);
            state(foreign,foreignProgram,alignment);
            require(GL11.glGetError()==GL11.GL_NO_ERROR,"OpenGL errors");
        } finally {
            pressure.close();
            gpu.close(true);GL11.glFinish();ledger.destroyAll(1);
            for(int i=0;i<4;i++)GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,i,0);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,0);GL20.glUseProgram(0);
            for(int id:foreign)GL15.glDeleteBuffers(id);
            GL15.glDeleteBuffers(commandBuffer);GL20.glDeleteProgram(foreignProgram);
        }
        require(budget.snapshot().getGpuUsed()==0,"GPU reservation leak");
    }

    private static void randomized(LwjglTerrainGpuVisibility gpu,int commandBuffer) {
        Random random=new Random(20261010);
        SoftwareOcclusionRaster cpu=new SoftwareOcclusionRaster(160,90);
        SoftwareOcclusionRaster.Bounds query=new SoftwareOcclusionRaster.Bounds();
        ByteBuffer faces=BufferUtils.createByteBuffer(24*128),bounds=BufferUtils.createByteBuffer(64*64);
        int hidden=0;
        for(int frame=0;frame<300;frame++) {
            float[] model=IDENTITY.clone();model[12]=(float)(random.nextDouble()*.6-.3);
            model[13]=(float)(random.nextDouble()*.4-.2);
            double angle=random.nextDouble()*6.28;
            model[0]=model[5]=(float)Math.cos(angle);model[1]=(float)Math.sin(angle);model[4]=-model[1];
            float[] projection=IDENTITY;
            if((frame&1)!=0) { projection=new float[]{1.2f,0,0,0,0,1.8f,0,0,0,0,-1.02f,-1,0,0,-.202f,0}; }
            cpu.begin(model,projection,0,0,0,Integer.MAX_VALUE);
            faces.clear();bounds.clear();
            for(int q=0;q<24;q++) {
                float x=(float)(random.nextDouble()*3-1.5),y=(float)(random.nextDouble()*2-1);
                float width=(float)(random.nextDouble()*1.2+.08),height=(float)(random.nextDouble()+.08);
                float z=(frame&1)==0?(float)(random.nextDouble()*.8-.4):(float)(-random.nextDouble()*4-1);
                float[] quad={x,y,z,x+width,y,z,x+width,y+height,z,x,y+height,z};
                for(int i=0;i<12;i+=3)point(faces,quad[i],quad[i+1],quad[i+2]);
                cpu.rasterize(quad,0,0,0,0);
            }
            boolean[] proven=new boolean[64];
            for(int i=0;i<64;i++) {
                double x=random.nextDouble()*2-1,y=random.nextDouble()*1.5-.75;
                double z=(frame&1)==0?random.nextDouble()*.9:-random.nextDouble()*8-1;
                double size=random.nextDouble()*.3+.01;
                box(bounds,x,y,z,x+size,y+size,z+.01,true);
                proven[i]=cpu.projectBounds(x,y,z,x+size,y+size,z+.01,query)&&cpu.occluded(query);
            }
            faces.flip();bounds.flip();
            int[] result=dispatch(gpu,commandBuffer,bounds,faces,64,24,frame%3==0?112:16,model,projection);
            for(int i=0;i<64;i++) {require(result[i]!=0||proven[i],"false occlusion frame="+frame+" candidate="+i);if(result[i]==0)hidden++;}
        }
        require(hidden>20,"oracle exercised actual occlusion");
        System.out.println("moving_rotated_projection_oracle_frames=300 gpu_hidden="+hidden);
    }

    private static void timing(LwjglTerrainGpuVisibility gpu,int commandBuffer) {
        ByteBuffer bounds=BufferUtils.createByteBuffer(4096*64),faces=BufferUtils.createByteBuffer(2048*128);
        for(int i=0;i<4096;i++) {
            double x=(i%64)/32.0-1,y=(i/64)/32.0-1;
            box(bounds,x,y,.5,x+.02,y+.02,.55,true);
        }
        for(int i=0;i<2048;i++) {
            double x=(i%64)/32.0-1,y=(i/64)/16.0-1;
            quad(faces,x,y,x+.1,y+.1,0,false);
        }
        bounds.flip();faces.flip();
        ByteBuffer commands=commands(4096,112);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,commandBuffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER,commands,GL15.GL_STREAM_DRAW);
        for(int i=0;i<100;i++)gpu.cull(STAMP,IDENTITY,IDENTITY,bounds,4096,faces,2048,commandBuffer,128,112,4096);
        GL11.glFinish();int query=GL15.glGenQueries();
        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,query);long start=System.nanoTime();
        for(int i=0;i<240;i++)gpu.cull(STAMP,IDENTITY,IDENTITY,bounds,4096,faces,2048,commandBuffer,128,112,4096);
        long cpuNs=System.nanoTime()-start;GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
        long gpuNs=GL33.glGetQueryObjectui64(query,GL15.GL_QUERY_RESULT);GL15.glDeleteQueries(query);
        System.out.println("gpu_visibility_4096_candidates_2048_faces cpu_us="+(cpuNs/240000.0)+" gpu_us="+(gpuNs/240000.0));
    }

    private static int[] dispatch(LwjglTerrainGpuVisibility gpu,int buffer,ByteBuffer bounds,ByteBuffer faces,
                                  int count,int faceCount,int stride,float[] model,float[] projection) {
        ByteBuffer commands=commands(count,stride);byte[] expected=new byte[commands.remaining()];commands.get(expected).rewind();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,buffer);GL15.glBufferData(GL15.GL_ARRAY_BUFFER,commands,GL15.GL_STREAM_DRAW);
        require(gpu.cull(STAMP,model,projection,bounds,count,faces,faceCount,buffer,128,stride,count),"GPU path consumed");
        ByteBuffer actual=BufferUtils.createByteBuffer(commands.remaining());
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);GL15.glGetBufferSubData(GL15.GL_ARRAY_BUFFER,0,actual);
        int[] result=new int[count];for(int i=0;i<count;i++) {
            result[i]=actual.getInt(128+i*stride+4);actual.putInt(128+i*stride+4,3);
        }
        for(int i=0;i<expected.length;i++)require(actual.get(i)==expected[i],"command payload changed at "+i);
        return result;
    }

    private static ByteBuffer commands(int count,int stride) {
        ByteBuffer bytes=BufferUtils.createByteBuffer(128+count*stride);
        for(int i=0;i<bytes.capacity();i++)bytes.put(i,(byte)(i*31+9));
        for(int i=0;i<count;i++) {int p=128+i*stride;bytes.putInt(p,123);bytes.putInt(p+4,3);bytes.putInt(p+8,456);bytes.putInt(p+12,i);}
        return bytes;
    }
    private static void state(int[] buffers,int program,int alignment) {
        require(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)==program,"foreign program");
        require(GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING)==buffers[4],"generic SSBO");
        for(int i=0;i<4;i++) {
            require(GL30.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,i)==buffers[i],"indexed SSBO");
            require(GL32.glGetInteger64(GL43.GL_SHADER_STORAGE_BUFFER_START,i)==alignment,"indexed SSBO start");
            require(GL32.glGetInteger64(GL43.GL_SHADER_STORAGE_BUFFER_SIZE,i)==alignment*3,"indexed SSBO size");
        }
    }
    private static int program() {
        int s=GL20.glCreateShader(GL20.GL_VERTEX_SHADER);GL20.glShaderSource(s,"#version 120\nvoid main(){gl_Position=gl_Vertex;}\n");GL20.glCompileShader(s);
        int p=GL20.glCreateProgram();GL20.glAttachShader(p,s);GL20.glLinkProgram(p);GL20.glDetachShader(p,s);GL20.glDeleteShader(s);return p;
    }
    private static void box(ByteBuffer target,double x,double y,double z,double X,double Y,double Z,boolean known) {
        target.putDouble(x).putDouble(y).putDouble(z).putDouble(known?1:0);
        target.putDouble(X).putDouble(Y).putDouble(Z).putDouble(0);
    }
    private static void point(ByteBuffer target,double x,double y,double z) {
        target.putDouble(x).putDouble(y).putDouble(z).putDouble(0);
    }
    private static void quad(ByteBuffer target,double x,double y,double X,double Y,double z,boolean reverse) {
        point(target,x,y,z);
        if(reverse){point(target,x,Y,z);point(target,X,Y,z);point(target,X,y,z);}
        else {point(target,X,y,z);point(target,X,Y,z);point(target,x,Y,z);}
    }
    private static void require(boolean condition,String detail) {assertions++;if(!condition)throw new AssertionError(detail);}
}
