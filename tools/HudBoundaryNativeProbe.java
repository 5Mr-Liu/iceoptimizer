package dev.rlcraft.ice.optimizer.client;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyMatrixStateTracker;
import dev.rlcraft.ice.optimizer.compat.hud.HudRenderBridge;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.hud.LwjglHudRenderer;
import dev.rlcraft.ice.optimizer.render.legacy.*;
import dev.rlcraft.ice.optimizer.render.resource.*;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.lang.reflect.*;
import java.nio.*;
import java.util.*;
import net.minecraftforge.fml.common.eventhandler.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Real Forge dispatch + native HUD state/output comparison in a hidden context. */
public final class HudBoundaryNativeProbe {
    private static final int SIZE=64;
    private static final Map<String,Long> CALLS=new TreeMap<String,Long>();
    private static boolean counting;
    private static String failOn;
    private static final RuntimeException INJECTED=new IllegalStateException("native boundary failure");
    private static int texture, foreignVao, foreignBuffer;
    private static ModernRendererRuntime runtime;
    private static LwjglHudRenderer hud;
    private static Object state,scope;
    private static int eventCalls;
    private static RuntimeException eventFailure;
    public static void count(String name) {
        if(name.equals(failOn)) { failOn=null; throw INJECTED; }
        if(counting) CALLS.put(name,CALLS.containsKey(name)?CALLS.get(name)+1:1L);
    }
    public static class ProbeEvent extends Event {
        private static final ListenerList LISTENERS=new ListenerList();
        @Override public boolean isCancelable(){return true;}
        @Override public ListenerList getListenerList(){return LISTENERS;}
    }
    public static void main(String[] args) throws Exception {
        boolean baseline=args.length!=0 && args[0].equals("baseline");
        Pbuffer context=new Pbuffer(SIZE,SIZE,new PixelFormat(),null,null);
        try {
            context.makeCurrent();
            RenderThreadGuard guard=RenderThreadGuard.captureCurrent();
            CacheBudget budget=new CacheBudget(1<<20,1<<20,1<<20);
            ResourceLedger ledger=new ResourceLedger(guard,budget,new LwjglResourceDestroyer(),128);
            ClientEpochs epochs=new ClientEpochs();runtime=new ModernRendererRuntime(epochs,budget);
            runtime.components.threadGuard=guard;
            runtime.components.glStateQueryWorkspace=new GlStateQueryWorkspace(budget);
            runtime.components.stateMirror=new GlStateMirror(2);
            runtime.components.legacyIsland=new LegacyGlIsland(guard,runtime.components.stateMirror,
                () -> { },new LwjglLegacyStateRestorer());
            hud=new LwjglHudRenderer(guard,ledger,budget,64);hud.prepare(1,1);
            runtime.components.hudRenderer=hud;
            field(runtime,"initialized").setBoolean(runtime,true);
            field(runtime,"currentStamp").set(runtime,new FrameStamp(1,1,epochs.snapshot()));
            Field mask=OptimizerRegistry.class.getDeclaredField("operationalMask");mask.setAccessible(true);
            mask.setLong(null,mask.getLong(null)|(1L<<OptimizationModule.LEGACY_GL_ISLAND.ordinal()));
            texture=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
            ByteBuffer tex=BufferUtils.createByteBuffer(16);
            tex.putInt(0xff8040ff).putInt(0xff20a0ff).putInt(0xff60ff40).putInt(0xff40c080).flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,2,2,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,tex);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
            foreignVao=GL30.glGenVertexArrays();foreignBuffer=GL15.glGenBuffers();
            configureForeignArrays();
            Field tls=HudRenderBridge.class.getDeclaredField("STATE");tls.setAccessible(true);
            state=((ThreadLocal<?>)tls.get(null)).get();scope=((Object[])field(state,"hudScopes").get(state))[0];
            Method reset=scope.getClass().getDeclaredMethod("reset",long.class);reset.setAccessible(true);reset.invoke(scope,1L);
            field(scope,"runtime").set(scope,runtime);field(scope,"renderer").set(scope,hud);
            field(scope,"modern").setBoolean(scope,true);
            field(scope,"resourceGeneration").setLong(scope,1);field(scope,"contextGeneration").setLong(scope,1);
            field(state,"hudDepth").setInt(state,1);
            EventBus bus=new EventBus((ignored,event,listeners,index,error) -> { });
            int busId=field(bus,"busID").getInt(bus);
            new ProbeEvent().getListenerList().register(busId,EventPriority.NORMAL,event -> {
                eventCalls++;
                if(eventFailure!=null)throw eventFailure;
                ((ProbeEvent)event).setCanceled(true);
                // Deliberately raw GL, so no wrapper can keep the tracker valid.
                GL11.glColor4f(.4F,.6F,.8F,1);
                GL11.glEnable(GL11.GL_BLEND);GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA,
                    GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ONE,GL11.GL_ZERO);
            });

            setup();for(int i=0;i<3;i++){record(i);HudRenderBridge.barrier();}
            if(!baseline) {
                // The first actual primitive repairs a missing VAO binding;
                // the normal stream must not fall back merely for that gap.
                setup();seedHudWithoutVao();long modern=hud.getModernFlushes();
                record(0);HudRenderBridge.barrier();
                require(hud.getModernFlushes()==modern+1,"unknown initial VAO disabled modern HUD");
                verifyForeignState(1,1,1,1);
            }
            // Warm private VAOs before counting ordinary submissions.
            setup();CALLS.clear();counting=true;
            for(int i=0;i<12;i++){record(i);HudRenderBridge.barrier();}
            counting=false;Map<String,Long> warmCalls=new TreeMap<String,Long>(CALLS);
            if(!baseline) verifyForeignState(1,1,1,1);

            // Each run draws before and after a real Forge listener, with
            // multiple empty events between the two draw phases.
            setup();drawExpected();byte[] expected=pixels();
            setup();record(0);CALLS.clear();counting=true;
            require(HudRenderBridge.post(bus,new ProbeEvent()),"cancellation lost");
            for(int i=0;i<5;i++)require(HudRenderBridge.post(bus,new ProbeEvent()),"empty event cancellation");
            counting=false;Map<String,Long> eventCallsMap=new TreeMap<String,Long>(CALLS);
            require(eventCalls==6,"event duplicate");
            CALLS.clear();counting=true;record(1);HudRenderBridge.barrier();counting=false;
            Map<String,Long> nextDrawCalls=new TreeMap<String,Long>(CALLS);
            byte[] actual=pixels();require(Arrays.equals(expected,actual),"Forge boundary pixels changed");
            if(!baseline) verifyForeignState(.4F,.6F,.8F,1);

            // Querying unit zero must restore a caller on another server unit;
            // eligibility still rejects that draw until the real caller returns.
            GL13.glActiveTexture(GL13.GL_TEXTURE1);GL13.glClientActiveTexture(GL13.GL_TEXTURE1);
            EarlyGlStateTracker.invalidate();require(runtime.resynchronizeHudState(),"nonzero units query");
            require(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)==GL13.GL_TEXTURE1,"active unit lost");
            require(GL11.glGetInteger(GL13.GL_CLIENT_ACTIVE_TEXTURE)==GL13.GL_TEXTURE1,"client unit lost");
            GL13.glActiveTexture(GL13.GL_TEXTURE0);GL13.glClientActiveTexture(GL13.GL_TEXTURE0);

            eventFailure=new IllegalArgumentException("original Forge failure");
            try { HudRenderBridge.post(bus,new ProbeEvent());throw new AssertionError("listener exception lost"); }
            catch(IllegalArgumentException expectedFailure){require(expectedFailure==eventFailure,"listener exception replaced");}
            require(eventCalls==7,"exception event duplicated");eventFailure=null;
            require(field(state,"legacyDepth").getInt(state)==0,"legacy recursion state leaked");
            long restorationFailures=runtime.components.legacyIsland.getRestorationFailures();
            if(!baseline) {
                require(restorationFailures==0,"unnecessary legacy restoration still executed");
                require(get(warmCalls,"glPushClientAttrib")==0 && get(warmCalls,"glPopClientAttrib")==0,"client stack still used");
                require(get(warmCalls,"glVertexPointer")==0 && get(warmCalls,"glTexCoordPointer")==0,"layout reset in warm path");
                require(get(eventCallsMap,"glGetInteger")==0,"empty Forge events queried GL");
                require(get(nextDrawCalls,"glGetInteger")==7 && get(nextDrawCalls,"glIsEnabled")==1,"minimal deferred state query changed");
            }
            if(!baseline) {
                int savedVao=foreignVao;foreignVao=0;configureForeignArrays();setup();
                record(0);HudRenderBridge.barrier();verifyForeignState(1,1,1,1);
                foreignVao=savedVao;
                setup();seedHudWithoutVao();
                require(hud.recordQuad(-.5F,-.5F,0,0,0,.5F,.5F,1,1),"unknown-state record");
                require(hud.flush(EarlyGlStateTracker.hudSnapshot())==LwjglHudRenderer.FlushResult.LEGACY_STATE,
                    "submission guessed unknown restore VAO");
                verifyForeignState(1,1,1,1);
                for(String nativeCall:new String[]{"glBufferData","glBufferStorage","glMapBufferRange","glDrawArrays","glBegin"}) {
                    setup();
                    for(int primitive=0;primitive<(nativeCall.equals("glBegin")?1:9);primitive++)
                        require(hud.recordQuad(-.5F,-.5F,0,0,0,.5F,.5F,1,1),"failure record");
                    failOn=nativeCall;
                    LwjglHudRenderer.FlushResult result=hud.flush(EarlyGlStateTracker.hudSnapshot());
                    require(hud.getLastError()==INJECTED,"injected native failure not retained");
                    boolean beforeDraw=nativeCall.equals("glBufferData") || nativeCall.equals("glBufferStorage") || nativeCall.equals("glMapBufferRange");
                    require(result==(beforeDraw?LwjglHudRenderer.FlushResult.FAILED_BEFORE_DRAW:
                        LwjglHudRenderer.FlushResult.FAILED_AFTER_DRAW),"uncertain native draw replayed");
                    verifyForeignState(1,1,1,1);
                }
            }
            field(state,"hudDepth").setInt(state,0);hud.close(true);runtime.components.glStateQueryWorkspace.close();
            GL30.glBindVertexArray(0);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,0);
            GL11.glFinish();ledger.collect(1,4096);ledger.collect(1,4096);ledger.destroyAll(1);
            require(budget.snapshot().getGpuUsed()==0 && budget.snapshot().getDirectUsed()==0
                && budget.snapshot().getHeapUsed()==0,"HUD resources leaked");
            GL30.glDeleteVertexArrays(foreignVao);GL15.glDeleteBuffers(foreignBuffer);GL11.glDeleteTextures(texture);
            require(GL11.glGetError()==0,"GL error");
            System.out.println("PASS baseline="+baseline+" pixels="+actual.length+" equal=true foreign_arrays_color_checked="+(!baseline)+" zero_resource_bytes=true restoration_failures="+restorationFailures);
            System.out.println("warm_12_batches="+warmCalls);
            System.out.println("six_events_including_pending_flush="+eventCallsMap);
            System.out.println("first_subsequent_draw="+nextDrawCalls);
        } finally {context.destroy();}
    }
    private static void configureForeignArrays() {
        GL30.glBindVertexArray(foreignVao);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,foreignBuffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER,4096L,GL15.GL_STATIC_DRAW);
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);GL11.glVertexPointer(3,GL11.GL_FLOAT,48,0L);
        GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);GL11.glColorPointer(4,GL11.GL_FLOAT,48,12L);
        GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);GL11.glNormalPointer(GL11.GL_FLOAT,48,28L);
        for(int unit=0;unit<2;unit++) {
            GL13.glClientActiveTexture(GL13.GL_TEXTURE0+unit);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);GL11.glTexCoordPointer(2,GL11.GL_FLOAT,48,40L);
        }
        GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
    }
    private static void setup() {
        GL20.glUseProgram(0);GL30.glBindVertexArray(foreignVao);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,foreignBuffer);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
        GL11.glEnable(GL11.GL_TEXTURE_2D);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
        GL11.glViewport(0,0,SIZE,SIZE);GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glLoadIdentity();
        GL11.glDisable(GL11.GL_LIGHTING);GL11.glDisable(GL11.GL_FOG);GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_CULL_FACE);GL11.glDisable(GL11.GL_BLEND);
        GL11.glColorMask(true,true,true,true);GL11.glColor4f(1,1,1,1);
        GL11.glClearColor(0,0,0,0);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        EarlyGlStateTracker.invalidate();EarlyMatrixStateTracker.invalidate();
        require(runtime.resynchronizeHudState(),"initial native state query");
    }
    private static void record(int side) {
        float x=side%2==0?-.9F:.1F;
        require(HudRenderBridge.tryTexturedRectFloat(null,x,-.8F,0,0,1,1,0),"HUD geometry declined");
    }
    private static void seedHudWithoutVao() {
        EarlyGlStateTracker.beginProbe();EarlyGlStateTracker.useProgram(0);
        EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER,foreignBuffer);
        EarlyGlStateTracker.activeTexture(GL13.GL_TEXTURE0);
        EarlyGlStateTracker.clientActiveTexture(GL13.GL_TEXTURE0);
        EarlyGlStateTracker.bindTexture(texture);EarlyGlStateTracker.texture2dEnabled(true);
        EarlyGlStateTracker.viewport(0,0,SIZE,SIZE);
    }
    private static void drawExpected() {
        immediate(-.9F);
        GL11.glColor4f(.4F,.6F,.8F,1);GL11.glEnable(GL11.GL_BLEND);
        GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ONE,GL11.GL_ZERO);
        immediate(.1F);
    }
    private static void immediate(float x) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0,1F/256);GL11.glVertex3f(x,.2F,0);
        GL11.glTexCoord2f(1F/256,1F/256);GL11.glVertex3f(x+1,.2F,0);
        GL11.glTexCoord2f(1F/256,0);GL11.glVertex3f(x+1,-.8F,0);
        GL11.glTexCoord2f(0,0);GL11.glVertex3f(x,-.8F,0);GL11.glEnd();
    }
    private static void verifyForeignState(float red,float green,float blue,float alpha) {
        FloatBuffer color=BufferUtils.createFloatBuffer(16);GL11.glGetFloat(GL11.GL_CURRENT_COLOR,color);
        require(Math.abs(color.get(0)-red)<1e-6F && Math.abs(color.get(1)-green)<1e-6F
            && Math.abs(color.get(2)-blue)<1e-6F && Math.abs(color.get(3)-alpha)<1e-6F,"current color changed");
        require(GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING)==foreignVao,"foreign VAO lost");
        require(GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING)==foreignBuffer,"foreign VBO lost");
        require(GL11.glGetInteger(GL11.GL_VERTEX_ARRAY_STRIDE)==48,"foreign vertex layout lost");
        require(GL11.glIsEnabled(GL11.GL_COLOR_ARRAY) && GL11.glIsEnabled(GL11.GL_NORMAL_ARRAY),"foreign arrays disabled");
        for(int unit=0;unit<2;unit++) {
            GL13.glClientActiveTexture(GL13.GL_TEXTURE0+unit);
            require(GL11.glIsEnabled(GL11.GL_TEXTURE_COORD_ARRAY),"foreign texture array disabled");
            require(GL11.glGetInteger(GL11.GL_TEXTURE_COORD_ARRAY_STRIDE)==48,"foreign texcoord layout changed");
        }
        GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
    }
    private static byte[] pixels(){ByteBuffer pixels=BufferUtils.createByteBuffer(SIZE*SIZE*4);GL11.glReadPixels(0,0,SIZE,SIZE,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);byte[] out=new byte[pixels.remaining()];pixels.get(out);return out;}
    private static Field field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f;}
    private static long get(Map<String,Long> map,String key){return map.containsKey(key)?map.get(key):0;}
    private static void require(boolean condition,String reason){if(!condition)throw new AssertionError(reason);}
}
