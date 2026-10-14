package dev.rlcraft.ice.optimizer.render.terrain;
import dev.rlcraft.ice.optimizer.compat.gl.*;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.backend.*;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.resource.*;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import dev.rlcraft.ice.optimizer.render.visibility.*;
import dev.rlcraft.ice.optimizer.render.visibility.RenderMatrixBridge;
import java.nio.*;
import java.util.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.chunk.*;
import net.minecraft.client.renderer.vertex.*;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import static dev.rlcraft.ice.optimizer.render.terrain.TerrainSubmissionNativeProbe.*;
/** Scratch 1.0.25 native-vs-F6-individual proof, no game launch. */
public final class TerrainAlgorithmNativeProbe {
 static final boolean CUBES=Boolean.getBoolean("ice.cubes");
 static final boolean PERSISTENT=Boolean.getBoolean("ice.persistent");
 static final int W=1280,H=720, QUADS=Integer.getInteger("ice.quads",CUBES?1536:1024);
 static final int[] COUNTS={384,128,128};
 static final BlockRenderLayer[] LAYERS={BlockRenderLayer.SOLID,BlockRenderLayer.CUTOUT,BlockRenderLayer.TRANSLUCENT};
 static final FrameStamp STAMP=new FrameStamp(1,1,new ClientEpochs().snapshot());
 static final int[] textures=new int[32];static final boolean[] textureEnabled=new boolean[32];
 static long frame;
 public static void main(String[] args)throws Exception{
  Pbuffer context=new Pbuffer(W,H,new PixelFormat().withDepthBits(24),null,null);
  try{context.makeCurrent();OpenGlHelper.defaultTexUnit=GL13.GL_TEXTURE0;OpenGlHelper.lightmapTexUnit=GL13.GL_TEXTURE1;
   if(args.length>0&&args[0].equals("standard")) {java.lang.reflect.Field f=ContextCapabilities.class.getField("GL_NV_bindless_multi_draw_indirect");f.setAccessible(true);f.setBoolean(GLContext.getCapabilities(),false);}
   System.out.println("renderer="+GL11.glGetString(GL11.GL_RENDERER)+" version="+GL11.glGetString(GL11.GL_VERSION));
   texture(0,false);texture(1,true);
   for(int every:new int[]{0,2}){Engine e=new Engine(every);try{e.compare();if(!Boolean.getBoolean("ice.onlypixels"))e.bench();}finally{e.close();}}
   require(GL11.glGetError()==0,"GL final error");System.out.println("realistic_native_complete=true");
  }finally{context.destroy();}
 }
 static final class Engine{
  final CacheBudget budget=new CacheBudget(128L<<20,128L<<20,384L<<20);
  final RenderThreadGuard guard=RenderThreadGuard.captureCurrent();final ResourceLedger ledger=ledger(guard,budget);
  final LwjglTerrainArena arena=new LwjglTerrainArena(guard,ledger,budget,CapabilityReport.builder().pass(ModernCapability.MULTI_DRAW_INDIRECT).pass(ModernCapability.PERSISTENT_MAPPING).build(),256L<<20,1);
  final CurrentFrameTerrainOcclusion evidence=new CurrentFrameTerrainOcclusion();
  final TestChunk[][] chunks=new TestChunk[3][];final boolean[][] external=new boolean[3][];final Container container=new Container();final int mixed;
  Engine(int every)throws Exception{mixed=every;arena.configureNativeBatching(!Boolean.getBoolean("ice.noBatch"),CUBES&&!Boolean.getBoolean("ice.noDirectional"));
   for(int l=0;l<3;l++){chunks[l]=new TestChunk[COUNTS[l]];external[l]=new boolean[COUNTS[l]];
    drawOrder[l]=new RenderChunk[COUNTS[l]];
    for(int i=0;i<COUNTS[l];i++){TestChunk c=chunk();chunks[l][i]=c;c.position=new BlockPos((i%12)*16,(i/96)*16,(i/12%8)*16);
     RenderChunk drawChunk=Boolean.getBoolean("ice.exactChunks")?exactChunk(c):c;drawOrder[l][i]=drawChunk;
     BufferBuilder b=CUBES?cubeGeometry(c,i,l):geometry(c,i,l);boolean ext=every>0&&i%every==1;if(l==0&&Boolean.getBoolean("ice.visibility"))stage(evidence,c,b);external[l][i]=ext;
     if(ext){c.vbo.id=GL15.glGenBuffers();c.vbo.count=b.getVertexCount();GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,c.vbo.id);GL15.glBufferData(GL15.GL_ARRAY_BUFFER,b.getByteBuffer(),GL15.GL_STATIC_DRAW);}
     else{TerrainUploadContext.begin(LAYERS[l],drawChunk,c.compiled);require(arena.upload(TerrainUploadContext.take(),b,c.vbo,STAMP,PERSISTENT,PERSISTENT,false),"upload l="+l+" i="+i);}
    }
   }
   if(Boolean.getBoolean("ice.shuffle")) for(int l=0;l<chunks.length;l++) {
    // Keep storage/lifecycle arrays untouched; permute the submitted order only.
    Collections.shuffle(Arrays.asList(drawOrder[l]),new Random(129+l));
   }
   container.viewX=96;container.viewY=80;container.viewZ=220;
   System.out.println("scene mixed_every="+mixed+" persistent="+PERSISTENT+" meshes=640 vertices="+(640L*QUADS*4)+" exact_chunks="+Boolean.getBoolean("ice.exactChunks")+" resolution="+W+"x"+H+" physical="+arena.getAllocatedBufferBytes());
  }
  final RenderChunk[][] drawOrder=new RenderChunk[3][];
  void draw(boolean enabled,int view){
   camera(view);arena.configureNativeBatching(enabled&&!Boolean.getBoolean("ice.noBatch"),CUBES&&!Boolean.getBoolean("ice.noDirectional"));long id=++frame;arena.configureVisibility(STAMP,enabled&&Boolean.getBoolean("ice.visibility")?evidence:null,enabled&&Boolean.getBoolean("ice.visibility")?matrices():null);
   for(int l=0;l<Integer.getInteger("ice.layers",3);l++){
    state(l);container.ice$renderChunks().addAll(Arrays.asList(drawOrder[l]==null?chunks[l]:drawOrder[l]));
    require(arena.render(container,LAYERS[l],enabled,enabled,id),"draw "+enabled+" "+l);
   }
   arena.endFrame(true);
  }
  void compare(){
   for (int view = 0; view < 6; view++) { draw(false,view);byte[] a=read();long culled=arena.getDirectionalCulledVertices();long multi=arena.getMultiDrawCalls();draw(true,view);byte[] b=read();System.out.println("culled_vertices="+(arena.getDirectionalCulledVertices()-culled)+" multidraw_calls="+(arena.getMultiDrawCalls()-multi));if(Boolean.getBoolean("ice.requireMulti"))require(arena.getMultiDrawCalls()>multi,"new ordered multi-draw path was not exercised");if(Boolean.getBoolean("ice.pixels")){save(a,"off-"+mixed);save(b,"on-"+mixed);}int n=0,max=0,visible=0;
   for(int i=0;i<a.length;i++){int d=Math.abs((a[i]&255)-(b[i]&255));if(d>1)n++;max=Math.max(max,d);if(a[i]!=0)visible++;}
   int drawn=0;for(int px=0;px<a.length;px+=4){if(Math.abs((a[px]&255)-26)>1||Math.abs((a[px+1]&255)-38)>1||Math.abs((a[px+2]&255)-51)>1)drawn++;} require(drawn>W*H/16,"empty view");System.out.println("drawn_pixels="+drawn+" layers="+Integer.getInteger("ice.layers",3));require(Boolean.getBoolean("ice.requireExact") ? max == 0 : n < a.length/10,"pixel divergence n="+n+" max="+max);
   System.out.println("pixel max_error="+max+" channels_error_gt1="+n+" nonzero="+visible+" batch_calls="+arena.getShaderBatchDrawCalls()+" batch_commands="+arena.getShaderBatchCommands()+" detail="+arena.getShaderBatchDetail()); }
  }
  void bench(){System.out.println("gpu_visibility frames="+arena.getGpuVisibilityFrames()+" candidates="+arena.getGpuVisibilityCandidates()+" faces="+arena.getGpuVisibilityFaces());
   for(int i=0;i<600;i++){draw(true,i%6);draw(false,i%6);}GL11.glFinish();
   double[] off=new double[15],on=new double[15];
   for(int r=0;r<15;r++){if(r%2==0){off[r]=time(false);on[r]=time(true);}else{on[r]=time(true);off[r]=time(false);}}
   System.out.println("frame_glfinish_ms mixed_every="+mixed+" off="+Arrays.toString(off)+" on="+Arrays.toString(on));
   Arrays.sort(off);Arrays.sort(on);System.out.println("median off_ms="+off[7]+" on_ms="+on[7]+" ratio="+on[7]/off[7]);
   try {System.out.println("layout_sets="+arena.getClass().getMethod("getArrayLayoutSets").invoke(arena)+" layout_reuses="+arena.getClass().getMethod("getArrayLayoutReuses").invoke(arena));}catch(NoSuchMethodException older){}catch(Exception failure){throw new RuntimeException(failure);}
   require(GL11.glGetError()==0,"benchmark GL error");
  }
  double time(boolean on){GL11.glFinish();long t=System.nanoTime();for(int i=0;i<64;i++)draw(on,i%6);GL11.glFinish();return (System.nanoTime()-t)/64000000.;}
  void close(){for(int l=0;l<3;l++)for(int i=0;i<chunks[l].length;i++){arena.release(chunks[l][i].vbo);if(external[l][i])GL15.glDeleteBuffers(chunks[l][i].vbo.id);}arena.close(true);GL11.glFinish();ledger.destroyAll(1);require(budget.snapshot().getGpuUsed()==0,"gpu leak");}
 }

 // Exact production class, with only the required offline fields initialized.
 static RenderChunk exactChunk(TestChunk source)throws Exception {
  java.lang.reflect.Field f=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);
  RenderChunk chunk=(RenderChunk)((sun.misc.Unsafe)f.get(null)).allocateInstance(RenderChunk.class);
  setChunkField(chunk,"position",new BlockPos.MutableBlockPos(source.position.getX(),source.position.getY(),source.position.getZ()));
  setChunkField(chunk,"compiledChunk",source.compiled);
  VertexBuffer[] buffers=new VertexBuffer[BlockRenderLayer.values().length];Arrays.fill(buffers,source.vbo);
  setChunkField(chunk,"vertexBuffers",buffers);
  FloatBuffer matrix=BufferUtils.createFloatBuffer(16);float scale=1.000001F, shift=8F*scale-8F;
  matrix.put(new float[]{scale,0,0,0,0,scale,0,0,0,0,scale,0,shift,shift,shift,1}).flip();
  setChunkField(chunk,"modelviewMatrix",matrix);
  return chunk;
 }
 static void setChunkField(RenderChunk chunk,String name,Object value)throws Exception {
  java.lang.reflect.Field f=RenderChunk.class.getDeclaredField(name);f.setAccessible(true);f.set(chunk,value);
 }

 static final float[][][] CUBE_FACES={{{0,0,1},{0,0,0},{1,0,0},{1,0,1}},{{0,1,0},{0,1,1},{1,1,1},{1,1,0}},{{1,0,0},{0,0,0},{0,1,0},{1,1,0}},{{0,0,1},{1,0,1},{1,1,1},{0,1,1}},{{0,0,0},{0,0,1},{0,1,1},{0,1,0}},{{1,0,1},{1,0,0},{1,1,0},{1,1,1}}};
 static BufferBuilder cubeGeometry(TestChunk c,int idx,int layer){
  BufferBuilder b=new BufferBuilder(QUADS*112);b.begin(GL11.GL_QUADS,DefaultVertexFormats.BLOCK);BlockPos p=c.position;b.setTranslation(-p.getX(),-p.getY(),-p.getZ());
  for(int q=0;q<QUADS;q++){int cube=q/6;float x=(cube%8)*2f+.2f+layer*.1f,z=(cube/8%8)*2f+.2f+layer*.1f,y=(cube/64)*3.5f+.2f+layer*.1f;
   for(int v=0;v<4;v++){float[] f=CUBE_FACES[q%6][v];b.pos(p.getX()+x+f[0]*.9f,p.getY()+y+f[1]*.9f,p.getZ()+z+f[2]*.9f).color(80+idx%120,120+cube%110,70+idx%90,layer==2?160:255).tex(v==1||v==2?1:0,v>=2?1:0).lightmap(80+cube%160,64+idx%176).endVertex();}
  }b.finishDrawing();return b;
 }

 static BufferBuilder geometry(TestChunk c,int idx,int layer){
  BufferBuilder b=new BufferBuilder(QUADS*112);b.begin(GL11.GL_QUADS,DefaultVertexFormats.BLOCK);
  BlockPos p=c.position;b.setTranslation(-p.getX(),-p.getY(),-p.getZ());
  for(int q=0;q<QUADS;q++){int gx=q%16,gz=q/16%16;float y=q/256*3.5f+layer*.04f;int alpha=layer==2?160:255;
   for(int v=0;v<4;v++){float x=gx+(v==1||v==2?1:0),z=gz+(v>=2?1:0);
    b.pos(p.getX()+x,p.getY()+y,p.getZ()+z).color(80+idx%120,120+q%110,70+idx%90,alpha).tex(v==1||v==2?1:0,v>=2?1:0).lightmap(80+q%160,64+idx%176).endVertex();}}
  b.finishDrawing();return b;
 }
 static void camera(int view){
  GL11.glViewport(0,0,W,H);GL11.glDisable(GL11.GL_SCISSOR_TEST);GL11.glDepthMask(true);GL11.glClearColor(.1f,.15f,.2f,1);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT);
  GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glLoadIdentity();double ymax=Math.tan(Math.toRadians(65)*.5);GL11.glFrustum(-ymax*W/H,ymax*W/H,-ymax,ymax,1,512);
  GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glLoadIdentity();GL11.glRotatef(22+view*.2f,1,0,0);GL11.glRotatef(view*.15f,0,1,0);
  GL20.glUseProgram(0);GL30.glBindVertexArray(0);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,0);GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER,0);
  GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_LESS);if(CUBES)GL11.glEnable(GL11.GL_CULL_FACE);else GL11.glDisable(GL11.GL_CULL_FACE);GL11.glCullFace(GL11.GL_BACK);GL11.glFrontFace(GL11.GL_CCW);GL11.glDisable(GL11.GL_LIGHTING);GL11.glShadeModel(GL11.GL_SMOOTH);
  if(Boolean.parseBoolean(System.getProperty("ice.fog","true")))GL11.glEnable(GL11.GL_FOG);else GL11.glDisable(GL11.GL_FOG);GL11.glFogi(GL11.GL_FOG_MODE,GL11.GL_LINEAR);GL11.glFogf(GL11.GL_FOG_START,160);GL11.glFogf(GL11.GL_FOG_END,400);GL11.glFog(GL11.GL_FOG_COLOR,(FloatBuffer)BufferUtils.createFloatBuffer(4).put(new float[]{.1f,.15f,.2f,1}).flip());
  for(int i=1;i>=0;i--){GL13.glActiveTexture(GL13.GL_TEXTURE0+i);GL11.glEnable(GL11.GL_TEXTURE_2D);GL11.glBindTexture(GL11.GL_TEXTURE_2D,textures[i]);GL13.glClientActiveTexture(GL13.GL_TEXTURE0+i);GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);GL11.glMatrixMode(GL11.GL_TEXTURE);GL11.glLoadIdentity();if(i==1){GL11.glScalef(1f/256,1f/256,1f/256);GL11.glTranslatef(8,8,8);}}
  GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
  EarlyGlStateTracker.invalidate();EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER,0);EarlyGlStateTracker.bindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER,0);EarlyGlStateTracker.useProgram(0);EarlyGlStateTracker.bindVertexArray(0);EarlyModelLightingState.seed(new int[EarlyModelLightingState.SIZE],new int[8],0);
 }
 static void state(int l){boolean blend=l==2;GL11.glDepthMask(!blend);if(blend)GL11.glEnable(GL11.GL_BLEND);else GL11.glDisable(GL11.GL_BLEND);GL11.glBlendFunc(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA);GL11.glEnable(GL11.GL_ALPHA_TEST);GL11.glAlphaFunc(GL11.GL_GREATER,.1f);
  EarlyGlStateTracker.depthMask(!blend);EarlyGlStateTracker.depthFunction(GL11.GL_LESS);EarlyGlStateTracker.lightingEnabled(false);EarlyGlStateTracker.cullEnabled(CUBES);EarlyGlStateTracker.clientActiveTexture(GL13.GL_TEXTURE0);
  EarlyGlStateTracker.seedDrawState(0,textures[0],textures[1],blend,GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA,true,!blend,CUBES,15,1,1,1,1);
  EarlyGlStateTracker.seedHudState(true,true,0,0,W,H);EarlyGlStateTracker.seedCompatibilityState(0,true,0,0,2,textures,textureEnabled,GL14.GL_FUNC_ADD,GL14.GL_FUNC_ADD,GL11.GL_BACK,false,false,0,0,W,H);
 }
 static void texture(int unit,boolean light){GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);textures[unit]=GL11.glGenTextures();textureEnabled[unit]=true;GL11.glBindTexture(GL11.GL_TEXTURE_2D,textures[unit]);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,light?GL11.GL_LINEAR:GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,light?GL11.GL_LINEAR:GL11.GL_NEAREST);ByteBuffer b=BufferUtils.createByteBuffer(16*16*4);for(int y=0;y<16;y++)for(int x=0;x<16;x++){int v=light?80+(x+y)*175/30:(CUBES?210:((x^y)%2==0?220:170));b.put((byte)v).put((byte)v).put((byte)v).put((byte)255);}b.flip();GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,16,16,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,b);}
 static void save(byte[] b,String label){try{java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(W,H,java.awt.image.BufferedImage.TYPE_INT_ARGB);for(int y=0;y<H;y++)for(int x=0;x<W;x++){int i=(y*W+x)*4;image.setRGB(x,H-1-y,0xff000000|((b[i]&255)<<16)|((b[i+1]&255)<<8)|(b[i+2]&255));}javax.imageio.ImageIO.write(image,"png",new java.io.File(System.getProperty("ice.output",".")+"/"+label+".png"));}catch(Exception e){throw new RuntimeException(e);}}
 static RenderMatrixBridge.Snapshot matrices(){FloatBuffer m=BufferUtils.createFloatBuffer(16),p=BufferUtils.createFloatBuffer(16);GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX,m);GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX,p);RenderMatrixBridge.capture(m,p,IntBuffer.wrap(new int[]{0,0,W,H}));return RenderMatrixBridge.snapshot();}
 static void stage(CurrentFrameTerrainOcclusion e,TestChunk c,BufferBuilder b)throws Exception{java.lang.reflect.Method method=TerrainGpuSubmissionNativeProbe.class.getDeclaredMethod("stage",CurrentFrameTerrainOcclusion.class,TestChunk.class,BufferBuilder.class);method.setAccessible(true);method.invoke(null,e,c,b);}
 static byte[] read(){ByteBuffer b=BufferUtils.createByteBuffer(W*H*4);GL11.glReadPixels(0,0,W,H,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,b);byte[] r=new byte[b.remaining()];b.get(r);return r;}
}
