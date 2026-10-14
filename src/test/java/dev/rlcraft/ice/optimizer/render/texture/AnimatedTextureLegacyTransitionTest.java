package dev.rlcraft.ice.optimizer.render.texture;

import static org.junit.Assert.*;
import dev.rlcraft.ice.optimizer.client.ModernRendererRuntime;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.compat.texture.AnimatedTextureUploadBridge;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.backend.CapabilityReport;
import dev.rlcraft.ice.optimizer.render.backend.ModernCapability;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.lang.reflect.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Actual bridge/stream flow: a full queue and failed fence query recover the prefix to legacy. */
public class AnimatedTextureLegacyTransitionTest {
    @Test public void recoveredLegacyFlushDoesNotAcceptTheNextCapturedCommand() throws Exception {
        RenderThreadGuard guard=RenderThreadGuard.captureCurrent();
        CacheBudget budget=new CacheBudget(8L<<20,8L<<20,8L<<20);
        ResourceLedger ledger=new ResourceLedger(guard,budget,new ResourceLedger.Destroyer(){
            public void destroy(RenderResourceKind kind,int id) { }
        },16);
        final List<Integer> submitted=new ArrayList<Integer>();
        LwjglAnimatedTextureUploadStream.LegacyUploader legacy=new LwjglAnimatedTextureUploadStream.LegacyUploader(){
            public void upload(int[][] data,int width,int height,int x,int y,boolean blur,boolean clamp){submitted.add(x);}
        };
        LwjglAnimatedTextureUploadStream stream=new LwjglAnimatedTextureUploadStream(guard,ledger,budget,
            CapabilityReport.builder().pass(ModernCapability.PIXEL_UNPACK_BUFFER).pass(ModernCapability.SYNC_FENCE).build(),legacy);
        ThreadLocal<?> stateLocal=(ThreadLocal<?>)field(AnimatedTextureUploadBridge.class,"STATE").get(null);
        stateLocal.remove();
        try {
            int[][] pixels={new int[64]};
            for(int command=0;command<4096;command++)assertTrue(stream.offer(pixels,8,8,command,0,false,false));
            final IllegalStateException fenceFailure=new IllegalStateException("injected fence query failure before submission");
            Object[] slots=(Object[])field(LwjglAnimatedTextureUploadStream.class,"streaming").get(stream);
            field(slots[0].getClass(),"fence").set(slots[0],new ResourceLedger.RetirementFence(){
                public boolean isSignaled(){throw fenceFailure;}
                public void destroy(){ }
            });
            Constructor<ModernRendererRuntime> constructor=ModernRendererRuntime.class.getDeclaredConstructor(ClientEpochs.class,CacheBudget.class);
            constructor.setAccessible(true);ModernRendererRuntime runtime=constructor.newInstance(new ClientEpochs(),budget);
            Object state=stateLocal.get();field(state.getClass(),"depth").setInt(state,1);
            Object scope=((Object[])field(state.getClass(),"scopes").get(state))[0];
            set(scope,"token",1L);set(scope,"modern",true);set(scope,"inSprite",true);set(scope,"eligible",true);
            set(scope,"resourceGeneration",1L);set(scope,"contextGeneration",1L);set(scope,"runtime",runtime);set(scope,"uploader",stream);
            EarlyGlStateTracker.bindBuffer(35052,0);
            Method upload=AnimatedTextureUploadBridge.class.getDeclaredMethod("tryUpload",int[][].class,
                int.class,int.class,int.class,int.class,boolean.class,boolean.class,boolean.class,boolean.class);
            upload.setAccessible(true);
            // This is the snapshot branch used by commitCaptured; the rejected command still belongs to its caller.
            assertFalse((Boolean)upload.invoke(null,pixels,8,8,4096,0,false,false,false,true));
            assertSame(fenceFailure,stream.getLastError());
            assertFalse(field(scope.getClass(),"modern").getBoolean(scope));
            assertEquals(4096,submitted.size());
            for(int command=0;command<submitted.size();command++)assertEquals(command,submitted.get(command).intValue());
            assertFalse("no command may remain behind a disabled stream",stream.hasCommands());
            legacy.upload(pixels,8,8,4096,0,false,false);
            AnimatedTextureUploadBridge.end(1L);
            assertEquals(4097,submitted.size());assertEquals(4096,submitted.get(4096).intValue());
            assertFalse(stream.hasCommands());
        } finally {
            stateLocal.remove();stream.close(false,1L);EarlyGlStateTracker.invalidate();
        }
    }
    private static Field field(Class<?> type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static void set(Object object,String name,Object value)throws Exception{field(object.getClass(),name).set(object,value);}
}
