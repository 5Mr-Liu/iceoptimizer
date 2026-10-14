package dev.rlcraft.ice.optimizer.render.terrain;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.backend.*;
import dev.rlcraft.ice.optimizer.render.resource.*;
import java.util.*;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.BlockRenderLayer;
import org.lwjgl.opengl.*;
import static dev.rlcraft.ice.optimizer.render.terrain.TerrainOrderedRangesNativeProbe.*;

/** Calls the production render loop, including lookup, mixed fallback and restoration.
 * Synthetic submission cost only, never a substitute for whole-game frametimes. */
public final class TerrainSinglePassNativeProbe {
    private static final int N = 1024;
    private static long frame;
    public static void main(String[] args) throws Exception {
        Pbuffer context = new Pbuffer(64,64,new PixelFormat().withDepthBits(24),null,null);
        try {
            context.makeCurrent();
            OpenGlHelper.defaultTexUnit=GL13.GL_TEXTURE0;
            OpenGlHelper.lightmapTexUnit=GL13.GL_TEXTURE1;
            RenderThreadGuard guard=RenderThreadGuard.captureCurrent();
            CacheBudget budget=new CacheBudget(8L<<20,8L<<20,40L<<20);
            ResourceLedger ledger=ledger(guard,budget);
            LwjglTerrainArena arena=new LwjglTerrainArena(guard,ledger,budget,
                CapabilityReport.builder().pass(ModernCapability.MULTI_DRAW_INDIRECT).build(),32L<<20,1L);
            RenderChunk[] nativeChunks=new RenderChunk[N];
            TestChunk[] legacy=new TestChunk[64];
            try {
                arena.configureNativeBatching(true,true);
                for(int i=0;i<N;i++) {
                    nativeChunks[i]=vanillaChunk((i/128)*256);
                    uploadVanilla(arena,nativeChunks[i],40+i%200,90+i%100,60+i%160);
                }
                for(int i=0;i<legacy.length;i++) {
                    legacy[i]=chunk();legacy[i].vbo.id=GL15.glGenBuffers();legacy[i].vbo.count=4;
                    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,legacy[i].vbo.id);
                    GL15.glBufferData(GL15.GL_ARRAY_BUFFER,builder(-.7F,.5F,20+i,90+i,210-i,1).getByteBuffer(),GL15.GL_STATIC_DRAW);
                }
                for(boolean directional:new boolean[]{false,true}) for(int legacyEvery:new int[]{0,8,2,1}) {
                    RenderChunk[] sequence=new RenderChunk[N];
                    for(int i=0;i<N;i++) sequence[i]=i>0&&legacyEvery>0&&i%legacyEvery==0
                        ?legacy[i%legacy.length]:nativeChunks[(i/128)*128+((i*37)%128)];
                    Container container=new Container();
                    setupBlend(); arena.configureNativeBatching(false,false);
                    draw(arena,container,sequence,false);
                    byte[] reference=pixels();
                    setupBlend(); arena.configureNativeBatching(true,directional);
                    draw(arena,container,sequence,true);
                    require(Arrays.equals(reference,pixels()),"ordered mixed pixels "+legacyEvery);
                    setup();if(directional)GL11.glEnable(GL11.GL_CULL_FACE);
                    GL11.glEnable(GL11.GL_SCISSOR_TEST);GL11.glScissor(0,0,1,1);
                    for(int i=0;i<1000;i++)draw(arena,container,sequence,true);
                    double[] cpu=new double[701],completed=new double[31];
                    long calls=arena.getDrawCalls();
                    for(int i=0;i<cpu.length;i++) {
                        GL11.glFinish();long begin=System.nanoTime();
                        draw(arena,container,sequence,true);
                        cpu[i]=(System.nanoTime()-begin)/1e6;
                    }
                    long perFrame=(arena.getDrawCalls()-calls)/cpu.length;
                    for(int i=0;i<completed.length;i++) {
                        GL11.glFinish();long begin=System.nanoTime();
                        for(int j=0;j<32;j++)draw(arena,container,sequence,true);
                        GL11.glFinish();completed[i]=(System.nanoTime()-begin)/32e6;
                    }
                    Arrays.sort(cpu);Arrays.sort(completed);
                    require(GL11.glGetError()==0,"native GL error");
                    System.out.printf(Locale.ROOT,"legacy_every=%d directional=%s meshes=%d draws=%d cpu_p50_ms=%.6f cpu_p95_ms=%.6f cpu_p99_ms=%.6f completed_p50_ms=%.6f pixels_exact=true%n",
                        legacyEvery,directional,N,perFrame,cpu[350],cpu[665],cpu[693],completed[15]);
                    GL11.glDisable(GL11.GL_SCISSOR_TEST);
                }
            } finally {
                for(TestChunk chunk:legacy)if(chunk!=null&&chunk.vbo.id!=0) {
                    arena.release(chunk.vbo);GL15.glDeleteBuffers(chunk.vbo.id);
                }
                arena.close(true);GL11.glFinish();ledger.destroyAll(1L);
            }
            require(budget.snapshot().getGpuUsed()==0&&budget.snapshot().getHeapUsed()==0
                &&budget.snapshot().getDirectUsed()==0,"budget leak");
            System.out.println("renderer="+GL11.glGetString(GL11.GL_RENDERER));
        } finally { context.destroy(); }
    }
    private static void draw(LwjglTerrainArena arena,Container container,RenderChunk[] sequence,boolean nativePath) {
        List<RenderChunk> chunks=container.ice$renderChunks();
        for(int i=0;i<sequence.length;i++)chunks.add(sequence[i]);
        require(arena.render(container,BlockRenderLayer.SOLID,nativePath,false,++frame),"rejected mixed layer");
    }
}
