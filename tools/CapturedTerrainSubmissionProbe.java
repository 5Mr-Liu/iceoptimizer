package dev.rlcraft.ice.optimizer.render.terrain;

import dev.rlcraft.ice.benchmark.TerrainSceneData;
import dev.rlcraft.ice.benchmark.QuadDescriptorNativeRenderer;
import dev.rlcraft.ice.benchmark.TerrainDisplayListReplay;
import dev.rlcraft.ice.benchmark.TerrainIndexedNativeRenderer;
import java.nio.*;
import java.util.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyModelLightingState;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.resource.*;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;

/** Offline submission comparison on captured owned geometry; not a full-game FPS result. */
public final class CapturedTerrainSubmissionProbe {
    private static final int W=1920,H=1080;
    private static final List<Mesh> meshes=new ArrayList<Mesh>();
    private static final Map<Integer,Integer> buffers=new HashMap<Integer,Integer>();
    private static TerrainSceneData scene;
    private static final double[] eye=new double[3];
    private static final FloatBuffer matrix=BufferUtils.createFloatBuffer(16);
    private static long calls, vertices;
    private static final int[] textures=new int[2];
    private static final boolean TEXTURED=Boolean.getBoolean("ice.texturedReplay");
    private static final boolean SINGLE_LAYER=Boolean.getBoolean("ice.singleLayerReplay");
    private static final boolean SHADER=SINGLE_LAYER||Boolean.getBoolean("ice.shaderReplay");
    private static final boolean VAO=Boolean.getBoolean("ice.vaoReplay");
    private static final boolean SPATIAL=Boolean.getBoolean("ice.spatialReplay");
    private static final boolean QUADS=Boolean.getBoolean("ice.quadReplay");
    private static final boolean LISTS=Boolean.getBoolean("ice.displayListReplay");
    private static final boolean SHARED_TRANSFORMS=Boolean.getBoolean("ice.sharedTransformReplay");
    private static final boolean INDEXED=Boolean.getBoolean("ice.indexedReplay");
    private static final boolean PATTERNED=Boolean.getBoolean("ice.patternedReplay");
    private static final boolean PRODUCTION_FACE_PLANNER=Boolean.getBoolean("ice.productionFacePlanner");
    private static final boolean PLANNED_FACE_SPANS=Boolean.getBoolean("ice.plannedFaceSpans");
    private static TerrainIndexedNativeRenderer indexedRenderer;
    private static TerrainDisplayListReplay displayLists;
    private static QuadDescriptorNativeRenderer quadRenderer;
    private static final List<Integer> packedBuffers=new ArrayList<Integer>();
    private static final Map<String,long[]> packedPages=new HashMap<String,long[]>();
    private static final Map<Integer,Integer> vaos=new HashMap<Integer,Integer>();
    private static LwjglTerrainBatchRenderer batch;
    private static ResourceLedger ledger;
    private static FrameStamp stamp;
    private static final Map<Integer,LwjglTerrainBatchRenderer.Page> pages=new HashMap<Integer,LwjglTerrainBatchRenderer.Page>();
    private static final Map<Integer,Long> pageSizes=new HashMap<Integer,Long>();
    private static final int[] trackedTextures=new int[32];
    private static final boolean[] trackedTextureEnabled=new boolean[32];
    private static int layerBuffer;
    private static long layerBytes;
    private static LwjglTerrainBatchRenderer.Page layerPage;
    private static final class Mesh {
        TerrainSceneData.Section source; TerrainFaceRanges faces; int buffer, first, vao, packedBuffer, packedFirst, layerFirst;
    }
    public static void main(String[] args)throws Exception {
        scene=TerrainSceneData.read(args[0]);
        if(!INDEXED&&!"SOLID".equals(scene.layer))throw new IllegalArgumentException("Solid snapshot required");
        Pbuffer context=new Pbuffer(W,H,new PixelFormat().withDepthBits(24),null,null);
        try {
            context.makeCurrent();
            if(TEXTURED)for(int unit=0;unit<2;unit++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);textures[unit]=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,textures[unit]);
                int width=PATTERNED?(unit==0?64:16):2;
                ByteBuffer texels=BufferUtils.createByteBuffer(width*width*4);
                for(int y=0;y<width;y++)for(int x=0;x<width;x++) {
                    if(!PATTERNED)texels.putInt(-1);
                    else if(unit==0)texels.put((byte)(x*17)).put((byte)(y*29)).put((byte)((x+y)*11)).put((byte)(((x/3+y/5)&1)==0?255:0));
                    else texels.put((byte)(40+x*13)).put((byte)(40+y*13)).put((byte)(80+x*5+y*5)).put((byte)255);
                }
                texels.flip();
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,width,width,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,texels);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
                GL11.glTexEnvi(GL11.GL_TEXTURE_ENV,GL11.GL_TEXTURE_ENV_MODE,GL11.GL_MODULATE);
                GL11.glMatrixMode(GL11.GL_TEXTURE);GL11.glLoadIdentity();if(unit==1){GL11.glScalef(1F/256,1F/256,1F/256);GL11.glTranslatef(8,8,8);}
            }
            Map<Integer,Long> sizes=new HashMap<Integer,Long>();
            for(TerrainSceneData.Section s:scene.sections)if(s.owned)sizes.put(s.buffer,Math.max(sizes.containsKey(s.buffer)?sizes.get(s.buffer):0,s.offset+s.data.length));
            if(SINGLE_LAYER) {
                for(TerrainSceneData.Section s:scene.sections)if(s.owned) {
                    if(s.data.length!=(long)s.vertices*28)throw new AssertionError("Unexpected expanded vertex layout");
                    layerBytes=Math.addExact(layerBytes,s.data.length);
                }
                if(layerBytes/28>Integer.MAX_VALUE)throw new AssertionError("Vertex index overflow");
                layerBuffer=GL15.glGenBuffers();GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,layerBuffer);
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER,layerBytes,GL15.GL_STATIC_DRAW);
            }
            for(Map.Entry<Integer,Long> page:sizes.entrySet()) {
                int id=GL15.glGenBuffers();buffers.put(page.getKey(),id);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,id);GL15.glBufferData(GL15.GL_ARRAY_BUFFER,page.getValue(),GL15.GL_STATIC_DRAW);
                pageSizes.put(id,page.getValue());
                if(VAO) {
                    int vao=GL30.glGenVertexArrays();vaos.put(id,vao);GL30.glBindVertexArray(vao);
                    GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
                    GL11.glVertexPointer(3,GL11.GL_FLOAT,28,0L);GL11.glColorPointer(4,GL11.GL_UNSIGNED_BYTE,28,12L);
                    if(TEXTURED)for(int unit=0;unit<2;unit++) {
                        GL13.glClientActiveTexture(GL13.GL_TEXTURE0+unit);GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
                        GL11.glTexCoordPointer(2,unit==0?GL11.GL_FLOAT:GL11.GL_SHORT,28,unit==0?16L:24L);
                    }
                    GL13.glClientActiveTexture(GL13.GL_TEXTURE0);GL30.glBindVertexArray(0);
                }
            }
            if(SHADER) {
                RenderThreadGuard guard=RenderThreadGuard.captureCurrent();
                CacheBudget budget=new CacheBudget(16<<20,16<<20,16<<20);
                ledger=new ResourceLedger(guard,budget,(kind,id)->{
                    if(kind==RenderResourceKind.BUFFER)GL15.glDeleteBuffers(id);
                    else if(kind==RenderResourceKind.VERTEX_ARRAY)GL30.glDeleteVertexArrays(id);
                    else if(kind==RenderResourceKind.PROGRAM)GL20.glDeleteProgram(id);
                    else if(kind==RenderResourceKind.SHADER)GL20.glDeleteShader(id);
                },128);
                batch=new LwjglTerrainBatchRenderer(ledger,budget);
                stamp=new FrameStamp(1,1,new ClientEpochs().snapshot());
                for(int buffer:buffers.values())pages.put(buffer,batch.newPage(buffer));
                if(SINGLE_LAYER)layerPage=batch.newPage(layerBuffer);
                for(int i=0;i<2;i++){trackedTextures[i]=textures[i];trackedTextureEnabled[i]=TEXTURED;}
            }
            long layerCursor=0;
            for(TerrainSceneData.Section s:scene.sections)if(s.owned) {
                Mesh mesh=new Mesh();mesh.source=s;mesh.buffer=buffers.get(s.buffer);mesh.first=(int)(s.offset/28);
                if(VAO)mesh.vao=vaos.get(mesh.buffer);
                ByteBuffer source=BufferUtils.createByteBuffer(s.data.length);source.put(s.data).flip();
                if("SOLID".equals(scene.layer)) {
                ByteBuffer grouped=BufferUtils.createByteBuffer(TerrainFaceRanges.storageBytes(s.data.length));mesh.faces=TerrainFaceRanges.group(source,grouped,s.data.length);
                // Captured SOLID storage was already grouped; this must preserve byte order.
                for(int i=0;i<s.data.length;i++)if(grouped.get(i)!=source.get(i))throw new AssertionError("Captured layout not already grouped");
                }
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,mesh.buffer);GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER,s.offset,source);meshes.add(mesh);
                if(SINGLE_LAYER) {
                    mesh.layerFirst=(int)(layerCursor/28);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,layerBuffer);
                    source.rewind();GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER,layerCursor,source);layerCursor+=s.data.length;
                }
                if(SPATIAL) {
                    String key=Arrays.toString(s.region);long[] page=packedPages.get(key);
                    long capacity=8L*1024*1024;
                    if(s.data.length>capacity)throw new AssertionError("Oversized captured mesh");
                    if(page==null||page[1]+s.data.length>capacity) {
                        int id=GL15.glGenBuffers();packedBuffers.add(id);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,id);
                        GL15.glBufferData(GL15.GL_ARRAY_BUFFER,capacity,GL15.GL_STATIC_DRAW);
                        page=new long[]{id,0};packedPages.put(key,page);
                    }
                    mesh.packedBuffer=(int)page[0];mesh.packedFirst=(int)(page[1]/28);
                    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,mesh.packedBuffer);source.rewind();
                    GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER,page[1],source);page[1]+=s.data.length;
                }
            }
            if(!TerrainCamera.eye(scene.model,eye))throw new AssertionError("Eye unavailable");
            if(QUADS) {
                if(!TEXTURED)throw new IllegalArgumentException("Quad replay requires textured reference");
                quadRenderer=new QuadDescriptorNativeRenderer(scene);
                System.out.println("quad_descriptors="+quadRenderer.descriptors+" encoded_bytes="+quadRenderer.encodedBytes);
            }
            if(LISTS) {
                displayLists=new TerrainDisplayListReplay(scene,buffers,TEXTURED);
                System.out.println("display_list_compile_ms="+displayLists.compileNanos/1000000.0+" geometry_is_copied=true");
            }
            if(INDEXED) {
                indexedRenderer=new TerrainIndexedNativeRenderer(scene);
                System.out.println("indexed_vertex_bytes="+indexedRenderer.vertexBytes+" index_bytes="+indexedRenderer.indexBytes);
            }
            System.out.println("captured_owned_meshes="+meshes.size()+" total_visible="+scene.sections.length+" pages="+buffers.size()+" renderer="+GL11.glGetString(GL11.GL_RENDERER));
            if(SINGLE_LAYER)System.out.println("single_layer_vertex_bytes="+layerBytes+" visible_owned_snapshot_only=true construction_excluded_from_timing=true live_growth_and_full_frame_unproven=true");
            draw(-1);byte[] original=pixels();
            int[] costs=SINGLE_LAYER?new int[]{-9,-12,-10,-13}:INDEXED?new int[]{-1,-11}:SPATIAL&&SHARED_TRANSFORMS?new int[]{-9,-4,-10,-5}:SHARED_TRANSFORMS?new int[]{-1,-9,2048,-10}:LISTS?new int[]{-1,-7,-8}:QUADS?new int[]{-1,-6}:SPATIAL?new int[]{-1,-4,2048,-5}:SHADER?new int[]{-1,-2}:VAO?new int[]{-1,-3}:new int[]{0,128,256,512,1024,2048,4096,-1};
            long[] drawCounts=new long[costs.length], vertexCounts=new long[costs.length];
            boolean[] equal=new boolean[costs.length];
            for(int k=0;k<costs.length;k++) {
                draw(costs[k]);byte[] actual=pixels();equal[k]=Arrays.equals(original,actual);if(!equal[k]){
                    int max=0,channels=0,pixels=0;for(int p=0;p<actual.length;p+=4){boolean changed=false;for(int c=0;c<4;c++){
                        int error=Math.abs((actual[p+c]&255)-(original[p+c]&255));max=Math.max(max,error);if(error>0){channels++;changed=true;}
                    }if(changed)pixels++;}
                    System.out.println("pixel_difference max="+max+" channels="+channels+" pixels="+pixels);
                    String prefix=SINGLE_LAYER?"single-layer-replay":"captured-terrain";
                    save(original,"build/fps-target/"+prefix+"-reference.png");save(actual,"build/fps-target/"+prefix+"-shader.png");
                    if(!Boolean.getBoolean("ice.allowPixelMismatch"))throw new AssertionError("Pixels changed cost="+costs[k]);
                    System.out.println("DIAGNOSTIC_ONLY pixel mismatch explicitly allowed for timing; not an accepted renderer");
                }
                drawCounts[k]=calls;vertexCounts[k]=vertices;
            }
            if(INDEXED&&PATTERNED) {
                float[] saved=scene.model.clone();
                for(int view=0;view<24;view++) {
                    GL11.glMatrixMode(GL11.GL_MODELVIEW);load(saved);GL11.glRotatef((view-12)*2.0F,0,1,0);
                    matrix.clear();GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX,matrix);
                    for(int element=0;element<16;element++)scene.model[element]=matrix.get(element);
                    draw(-1);byte[] reference=pixels();draw(-11);
                    if(!Arrays.equals(reference,pixels()))throw new AssertionError("Indexed textured/alpha view mismatch "+view);
                }
                System.arraycopy(saved,0,scene.model,0,16);
                System.out.println("indexed_patterned_texture_alpha_views=24 exact_pixels=true");
            }
            if(PRODUCTION_FACE_PLANNER&&Boolean.getBoolean("ice.facePlannerViewChecks")) {
                float[] saved=scene.model.clone();
                for(int view=0;view<24;view++) {
                    GL11.glMatrixMode(GL11.GL_MODELVIEW);load(saved);GL11.glRotatef((view-12)*7.0F,0,1,0);
                    matrix.clear();GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX,matrix);
                    for(int element=0;element<16;element++)scene.model[element]=matrix.get(element);
                    if(!TerrainCamera.eye(scene.model,eye))throw new AssertionError("rotated eye unavailable");
                    draw(-9);byte[] reference=pixels();draw(-10);
                    if(!Arrays.equals(reference,pixels()))throw new AssertionError("Face plan pixel mismatch "+view);
                }
                System.arraycopy(saved,0,scene.model,0,16);TerrainCamera.eye(scene.model,eye);
                System.out.println("face_planner_rotated_views=24 exact_pixels=true");
            }
            // Warm every path, then rotate arm order to avoid crediting later JIT/clock warmup.
            for(int i=0;i<1600;i++)draw(costs[i%costs.length]);
            double[][] times=new double[costs.length][17];
            for(int r=0;r<17;r++)for(int k=0;k<costs.length;k++) {
                int arm=(k+r)%costs.length;GL11.glFinish();long start=System.nanoTime();
                for(int i=0;i<64;i++)draw(costs[arm]);GL11.glFinish();times[arm][r]=(System.nanoTime()-start)/64000000.0;
            }
            for(int k=0;k<costs.length;k++) {
                Arrays.sort(times[k]);System.out.println("cost="+costs[k]+" ms="+times[k][8]+" calls="+drawCounts[k]+" vertices="+vertexCounts[k]+" textured="+TEXTURED+" exact_pixels="+equal[k]);
            }
            // Pipeline throughput above includes GPU completion. A separate
            // drained-queue sample measures CPU submission without timing its
            // explicit completion wait. Neither is a complete game frame.
            if(Boolean.getBoolean("ice.measureCpuSubmission")) {
                double[][] cpu=new double[costs.length][17];
                for(int round=0;round<17;round++)for(int k=0;k<costs.length;k++) {
                    int arm=(k+round)%costs.length;long elapsed=0L;
                    for(int sample=0;sample<8;sample++) {
                        GL11.glFinish();long started=System.nanoTime();
                        draw(costs[arm]);elapsed+=System.nanoTime()-started;
                    }
                    cpu[arm][round]=elapsed/8000000.0;
                }
                GL11.glFinish();
                for(int k=0;k<costs.length;k++) {
                    Arrays.sort(cpu[k]);System.out.println("cost="+costs[k]+" cpu_submit_ms="+cpu[k][8]+" completion_wait_included=false drained_queue=true");
                }
            }
            if(GL11.glGetError()!=0)throw new AssertionError("GL error");
        } finally {if(indexedRenderer!=null)indexedRenderer.close();if(displayLists!=null)displayLists.close();if(quadRenderer!=null)quadRenderer.close();if(batch!=null){batch.close(true);ledger.destroyAll(1);}for(int vao:vaos.values())GL30.glDeleteVertexArrays(vao);for(int buffer:buffers.values())GL15.glDeleteBuffers(buffer);for(int buffer:packedBuffers)GL15.glDeleteBuffers(buffer);if(layerBuffer!=0)GL15.glDeleteBuffers(layerBuffer);for(int texture:textures)if(texture!=0)GL11.glDeleteTextures(texture);context.destroy();}
    }
    private static void save(byte[] rgba,String path)throws Exception{
        java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(W,H,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<H;y++)for(int x=0;x<W;x++){int p=(y*W+x)*4;image.setRGB(x,H-1-y,((rgba[p+3]&255)<<24)|((rgba[p]&255)<<16)|((rgba[p+1]&255)<<8)|(rgba[p+2]&255));}
        javax.imageio.ImageIO.write(image,"png",new java.io.File(path));
    }
    private static void draw(int cost) {
        GL11.glViewport(0,0,W,H);GL11.glClearColor(.1F,.15F,.2F,1);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT);
        GL20.glUseProgram(0);GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_LEQUAL);GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_CULL_FACE);GL11.glCullFace(GL11.GL_BACK);GL11.glFrontFace(GL11.GL_CCW);
        GL11.glDisable(GL11.GL_BLEND);GL11.glDisable(GL11.GL_LIGHTING);GL11.glDisable(GL11.GL_FOG);GL11.glDisable(GL11.GL_ALPHA_TEST);GL11.glShadeModel(GL11.GL_SMOOTH);
        if(PATTERNED){GL11.glEnable(GL11.GL_ALPHA_TEST);GL11.glAlphaFunc(GL11.GL_GREATER,.1F);}
        for(int unit=0;unit<2;unit++){GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);if(TEXTURED)GL11.glEnable(GL11.GL_TEXTURE_2D);else GL11.glDisable(GL11.GL_TEXTURE_2D);GL13.glClientActiveTexture(GL13.GL_TEXTURE0+unit);if(TEXTURED)GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);else GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);}
        GL13.glActiveTexture(GL13.GL_TEXTURE0);GL13.glClientActiveTexture(GL13.GL_TEXTURE0);GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
        GL11.glMatrixMode(GL11.GL_PROJECTION);load(scene.projection);GL11.glMatrixMode(GL11.GL_MODELVIEW);load(scene.model);
        calls=vertices=0;int bound=0;
        if(cost==-2||cost==-12||cost==-13){drawShader(cost!=-2,cost==-13);return;}
        if(cost==-6){drawQuadDescriptors();return;}
        if(cost==-7||cost==-8){drawDisplayLists(cost==-8);return;}
        if(cost==-11){drawIndexed();return;}
        boolean spatial=cost==-4||cost==-5;
        boolean sharedTransforms=cost==-9||cost==-10;
        int[] transformRegion=null;
        int faceCost=cost==-5||cost==-10?2048:cost;
        for(int i=0;i<meshes.size();) {
            Mesh head=meshes.get(i);int end=i+1;
            int headBuffer=spatial?head.packedBuffer:head.buffer;
            while(end<meshes.size()&&(spatial?meshes.get(end).packedBuffer:meshes.get(end).buffer)==headBuffer&&Arrays.equals(meshes.get(end).source.region,head.source.region))end++;
            if(!sharedTransforms||!Arrays.equals(transformRegion,head.source.region)) {
                if(sharedTransforms&&transformRegion!=null)GL11.glPopMatrix();
                GL11.glPushMatrix();GL11.glTranslatef((float)(head.source.region[0]-scene.camera[0]),(float)(head.source.region[1]-scene.camera[1]),(float)(head.source.region[2]-scene.camera[2]));
                transformRegion=head.source.region;
            }
            if(bound!=headBuffer){bound=headBuffer;if(cost==-3)GL30.glBindVertexArray(head.vao);else{GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,bound);GL11.glVertexPointer(3,GL11.GL_FLOAT,28,0L);GL11.glColorPointer(4,GL11.GL_UNSIGNED_BYTE,28,12L);if(TEXTURED){GL13.glClientActiveTexture(GL13.GL_TEXTURE0);GL11.glTexCoordPointer(2,GL11.GL_FLOAT,28,16L);GL13.glClientActiveTexture(GL13.GL_TEXTURE1);GL11.glTexCoordPointer(2,GL11.GL_SHORT,28,24L);GL13.glClientActiveTexture(GL13.GL_TEXTURE0);}}}
            int pendingFirst=0,pendingCount=0;
            for(int j=i;j<end;j++) {
                Mesh mesh=meshes.get(j);int mask=0,saved=0,segments=0;boolean previous=false;
                double x=eye[0]-(float)(mesh.source.region[0]-scene.camera[0]),y=eye[1]-(float)(mesh.source.region[1]-scene.camera[1]),z=eye[2]-(float)(mesh.source.region[2]-scene.camera[2]);
                if(faceCost>=0&&PLANNED_FACE_SPANS) {
                    int plan=mesh.faces.submissionPlan(x,y,z);
                    for(int spans=plan>>>24;spans>0;spans--,plan>>>=6) {
                        int count=mesh.faces.spanCount(plan);if(count==0)continue;
                        int first=(spatial?mesh.packedFirst:mesh.first)+mesh.faces.spanFirst(plan);
                        if(pendingCount>0&&pendingFirst+pendingCount!=first){emit(pendingFirst,pendingCount);pendingCount=0;}
                        if(pendingCount==0)pendingFirst=first;pendingCount+=count;
                    }
                    continue;
                }
                if(faceCost>=0&&!PRODUCTION_FACE_PLANNER)for(int face=0;face<7;face++) {
                    int count=mesh.faces.count(face);if(count==0)continue;
                    boolean visible=mesh.faces.visible(face,x,y,z);if(visible){mask|=1<<face;if(!previous)segments++;}else saved+=count;previous=visible;
                }
                boolean cull=faceCost>=0&&(saved>0)&&(segments<=1||saved>=(long)(segments-1)*faceCost);
                if(faceCost>=0&&PRODUCTION_FACE_PLANNER) {
                    if(faceCost!=2048)throw new IllegalArgumentException("Use sharedTransformReplay for production face plan");
                    mask=mesh.faces.submissionMask(x,y,z);cull=mask!=127;
                }
                for(int face=0;face<(cull?7:1);face++) {
                    int count=cull?mesh.faces.count(face):mesh.source.vertices;if(count==0||cull&&(mask&(1<<face))==0)continue;
                    int first=(spatial?mesh.packedFirst:mesh.first)+(cull?mesh.faces.first(face):0);
                    if(pendingCount>0&&pendingFirst+pendingCount!=first){emit(pendingFirst,pendingCount);pendingCount=0;}
                    if(pendingCount==0)pendingFirst=first;pendingCount+=count;
                }
            }
            if(pendingCount>0)emit(pendingFirst,pendingCount);if(!sharedTransforms)GL11.glPopMatrix();i=end;
        }
        if(sharedTransforms&&transformRegion!=null)GL11.glPopMatrix();
        if(cost==-3)GL30.glBindVertexArray(0);
    }
    private static void drawIndexed() {
        indexedRenderer.begin();
        for(int i=0;i<meshes.size();) {
            Mesh first=meshes.get(i);int end=i+1;
            while(end<meshes.size()&&meshes.get(end).buffer==first.buffer
                &&Arrays.equals(meshes.get(end).source.region,first.source.region))end++;
            int[] region=first.source.region;GL11.glPushMatrix();
            GL11.glTranslatef((float)(region[0]-scene.camera[0]),(float)(region[1]-scene.camera[1]),(float)(region[2]-scene.camera[2]));
            for(int j=i;j<end;j++){indexedRenderer.draw(meshes.get(j).source,TEXTURED);calls++;vertices+=meshes.get(j).source.vertices;}
            GL11.glPopMatrix();i=end;
        }
    }
    private static void drawDisplayLists(boolean grouped) {
        int[] region=null;
        for(TerrainDisplayListReplay.Draw draw:grouped?displayLists.regions:displayLists.meshes) {
            if(!Arrays.equals(region,draw.region)) {
                if(region!=null)GL11.glPopMatrix();
                region=draw.region;GL11.glPushMatrix();
                GL11.glTranslatef((float)(region[0]-scene.camera[0]),(float)(region[1]-scene.camera[1]),(float)(region[2]-scene.camera[2]));
            }
            GL11.glCallList(draw.list);calls++;vertices+=draw.vertices;
        }
        if(region!=null)GL11.glPopMatrix();
    }
    private static void drawQuadDescriptors() {
        int[] region=null; int first=0,count=0;
        for(Mesh mesh:meshes) {
            if(!Arrays.equals(region,mesh.source.region)) {
                if(count>0){quadRenderer.draw(first,count);calls++;vertices+=count*4;count=0;}
                if(region!=null)GL11.glPopMatrix();
                region=mesh.source.region;GL11.glPushMatrix();GL11.glTranslatef((float)(region[0]-scene.camera[0]),(float)(region[1]-scene.camera[1]),(float)(region[2]-scene.camera[2]));
            }
            for(QuadDescriptorNativeRenderer.Range range:quadRenderer.ranges(mesh.source)) {
                if(range.compact) {
                    if(count>0&&first+count!=range.first){quadRenderer.draw(first,count);calls++;vertices+=count*4;count=0;}
                    if(count==0)first=range.first;count+=range.count;
                } else {
                    if(count>0){quadRenderer.draw(first,count);calls++;vertices+=count*4;count=0;}
                    quadRenderer.legacy();GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,mesh.buffer);
                    GL11.glVertexPointer(3,GL11.GL_FLOAT,28,0L);GL11.glColorPointer(4,GL11.GL_UNSIGNED_BYTE,28,12L);
                    GL13.glClientActiveTexture(GL13.GL_TEXTURE0);GL11.glTexCoordPointer(2,GL11.GL_FLOAT,28,16L);
                    GL13.glClientActiveTexture(GL13.GL_TEXTURE1);GL11.glTexCoordPointer(2,GL11.GL_SHORT,28,24L);GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
                    emit(mesh.first+range.first*4,range.count*4);
                }
            }
        }
        if(count>0){quadRenderer.draw(first,count);calls++;vertices+=count*4;}
        if(region!=null)GL11.glPopMatrix();quadRenderer.legacy();
    }
    private static void drawShader(boolean singleLayer,boolean directional) {
        GL30.glBindVertexArray(0);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,0);GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER,0);
        EarlyGlStateTracker.beginProbe();EarlyGlStateTracker.useProgram(0);
        EarlyGlStateTracker.bindFramebuffer(GL30.GL_FRAMEBUFFER,0);
        EarlyGlStateTracker.bindBuffer(GL21.GL_PIXEL_PACK_BUFFER,0);
        EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER,0);
        EarlyGlStateTracker.bindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER,0);
        EarlyGlStateTracker.clientActiveTexture(GL13.GL_TEXTURE0);EarlyGlStateTracker.depthFunction(GL11.GL_LEQUAL);
        EarlyGlStateTracker.lightingEnabled(false);EarlyGlStateTracker.fogi(0x855A,0x855C);
        EarlyGlStateTracker.seedDrawState(0,textures[0],textures[1],false,GL11.GL_ONE,GL11.GL_ZERO,GL11.GL_ONE,GL11.GL_ZERO,false,true,false,15,1,1,1,1);
        EarlyGlStateTracker.seedCompatibilityState(0,true,0,0,2,trackedTextures,trackedTextureEnabled,GL14.GL_FUNC_ADD,GL14.GL_FUNC_ADD,GL11.GL_BACK,false,false,0,0,W,H);
        EarlyModelLightingState.seed(new int[EarlyModelLightingState.SIZE],new int[8],0);
        if(!batch.prepare(meshes.size(),stamp,EarlyGlStateTracker.compatibilitySnapshot(),0,false))throw new AssertionError(batch.detail());
        if(!singleLayer&&!batch.supportsCrossPage())throw new AssertionError("This probe requires native cross-page support");
        if(singleLayer&&!batch.preparePage(layerPage,layerBytes))throw new AssertionError("Single layer page admission");
        for(int i=0;i<meshes.size();i++){
            Mesh mesh=meshes.get(i);LwjglTerrainBatchRenderer.Page page=singleLayer?layerPage:pages.get(mesh.buffer);
            if(!singleLayer&&!batch.preparePage(page,pageSizes.get(mesh.buffer)))throw new AssertionError("Page admission");
            batch.offset(i,(float)(mesh.source.region[0]-scene.camera[0]),(float)(mesh.source.region[1]-scene.camera[1]),(float)(mesh.source.region[2]-scene.camera[2]));
            int first=singleLayer?mesh.layerFirst:mesh.first;
            if(directional) {
                double x=eye[0]-(float)(mesh.source.region[0]-scene.camera[0]),y=eye[1]-(float)(mesh.source.region[1]-scene.camera[1]),z=eye[2]-(float)(mesh.source.region[2]-scene.camera[2]);
                int plan=mesh.faces.submissionPlan(x,y,z);
                for(int spans=plan>>>24;spans>0;spans--,plan>>>=6) {
                    int count=mesh.faces.spanCount(plan);if(count==0)continue;
                    batch.command(page,count,first+mesh.faces.spanFirst(plan),i);vertices+=count;
                }
            } else {batch.command(page,mesh.source.vertices,first,i);vertices+=mesh.source.vertices;}
        }
        if(singleLayer&&batch.commandStrideBytes()!=16)throw new AssertionError("Single VBO unexpectedly promoted to bindless commands");
        batch.upload();
        try{batch.drawCombined(0,batch.commandCount());calls=1;}finally{batch.endNativeRun();}
    }
    private static void emit(int first,int count){GL11.glDrawArrays(GL11.GL_QUADS,first,count);calls++;vertices+=count;}
    private static void load(float[] values){matrix.clear();matrix.put(values).flip();GL11.glLoadMatrix(matrix);}
    private static byte[] pixels(){ByteBuffer b=BufferUtils.createByteBuffer(W*H*4);GL11.glReadPixels(0,0,W,H,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,b);byte[] out=new byte[b.remaining()];b.get(out);return out;}
}
