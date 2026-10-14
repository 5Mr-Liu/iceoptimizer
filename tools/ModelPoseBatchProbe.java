import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyMatrixStateTracker;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyModelBatchBarrier;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.entity.LwjglModelMeshCache;
import dev.rlcraft.ice.optimizer.render.entity.ModelMeshPayload;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.resource.*;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.nio.*;
import java.util.Arrays;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Offline native validation of the actual production palette renderer. */
public final class ModelPoseBatchProbe {
    private static final int PARTS = 70, SIZE = 128;
    private static int scenario;
    private static boolean timing;
    private static boolean stateOnly;
    private static boolean displayLists;
    private static int legacyLists;
    private static final boolean RELATIVE = Boolean.getBoolean("ice.relativeModelPoses");
    private static final byte[] NO_PIXELS = new byte[0];
    public static void main(String[] args) throws Exception {
        Pbuffer context = new Pbuffer(SIZE, SIZE, new PixelFormat().withDepthBits(24), null, null);
        try {
            context.makeCurrent();
            RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
            CacheBudget budget = new CacheBudget(4 << 20, 4 << 20, 4 << 20);
            ResourceLedger ledger = new ResourceLedger(guard, budget, new ResourceLedger.Destroyer() {
                public void destroy(RenderResourceKind kind, int id) {
                    if (kind == RenderResourceKind.BUFFER) GL15.glDeleteBuffers(id);
                    else if (kind == RenderResourceKind.PROGRAM) GL20.glDeleteProgram(id);
                    else if (kind == RenderResourceKind.SHADER) GL20.glDeleteShader(id);
                    else if (kind == RenderResourceKind.VERTEX_ARRAY) GL30.glDeleteVertexArrays(id);
                }
            }, 128);
            LwjglModelMeshCache cache = new LwjglModelMeshCache(guard, ledger, budget);
            ClientEpochs epochs = new ClientEpochs(); epochs.nextFrame();
            FrameStamp stamp = new FrameStamp(1, 1, epochs.snapshot());
            int original = GL15.glGenBuffers();
            try {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, original);
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, 256, GL15.GL_STATIC_DRAW);
                seed(original);
                LwjglModelMeshCache.Entry[] entries = new LwjglModelMeshCache.Entry[PARTS];
                for (int i = 0; i < entries.length; i++) {
                    ModelMeshPayload payload = new ModelMeshPayload(i + 1, quad(),
                        DefaultVertexFormats.POSITION_TEX_NORMAL, GL11.GL_QUADS, 4, 1, 1);
                    if (!cache.accept(payload, stamp)) throw new AssertionError("upload");
                    entries[i] = cache.findStateBatchEntry(i + 1, stamp);
                    if (entries[i] == null) throw new AssertionError("uploaded pose entry not ready");
                }
                cache.configurePoseBatching(false);
                ModelMeshPayload legacyPayload = new ModelMeshPayload(500, quad(),
                    DefaultVertexFormats.POSITION_TEX_NORMAL, GL11.GL_QUADS, 4, 1, 1);
                if (!cache.accept(legacyPayload, stamp)) throw new AssertionError("legacy upload");
                LwjglModelMeshCache.Entry legacyEntry = cache.find(500, stamp);
                if (!legacyEntry.isValidated()
                    || cache.findStateBatchEntry(500, stamp) != legacyEntry)
                    throw new AssertionError("uploaded immediate entry not ready");
                setup();
                byte[] poseUploadPixels = draw(cache,
                    new LwjglModelMeshCache.Entry[]{entries[0]}, stamp, false);
                setup();
                byte[] immediateUploadPixels = draw(cache,
                    new LwjglModelMeshCache.Entry[]{legacyEntry}, stamp, false);
                if (!Arrays.equals(poseUploadPixels, immediateUploadPixels))
                    throw new AssertionError("immediate upload changed output without readback");
                cache.invalidate(500);
                if (legacyEntry.isValidated() || cache.find(500, stamp) != null)
                    throw new AssertionError("retired immediate entry remained usable");
                if (!cache.accept(legacyPayload, stamp)
                    || cache.findStateBatchEntry(500, stamp) == null)
                    throw new AssertionError("replacement immediate entry not ready");
                cache.configurePoseBatching(true);
                System.out.println("entry_admission PASS pose_upload_ready=true immediate_upload_ready=true readback_required=false replacement_ready=true pixels_equal=true");
                GL11.glVertexPointer(3, GL11.GL_FLOAT, 24, 0L);
                GL11.glNormalPointer(GL11.GL_BYTE, 24, 20L);
                GL11.glTexCoordPointer(2, GL11.GL_FLOAT, 24, 12L);
                GL20.glVertexAttribPointer(7, 2, GL11.GL_FLOAT, false, 24, 8L);
                GL20.glDisableVertexAttribArray(7);
                for (scenario = 0; scenario < 14; scenario++) {
                    setup(); cache.seedPoseState();
                    byte[] expected = draw(cache, entries, stamp, false);
                    setup(); cache.seedPoseState();
                    long draws = cache.getPoseBatchDraws(), parts = cache.getPoseBatchParts();
                    byte[] actual = draw(cache, entries, stamp, true);
                    int max = 0, changed = 0;
                    for (int i = 0; i < actual.length; i++) {
                        int difference = Math.abs((actual[i] & 255) - (expected[i] & 255));
                        max = Math.max(max, difference); if (difference > 0) changed++;
                    }
                    if (max > 1) { for (int px=0, n=0;px<actual.length && n<12;px+=4) if (Math.abs((actual[px]&255)-(expected[px]&255))>1) {System.out.println("pixel="+(px/4)+" expected="+(expected[px]&255)+","+(expected[px+1]&255)+","+(expected[px+2]&255)+" actual="+(actual[px]&255)+","+(actual[px+1]&255)+","+(actual[px+2]&255));n++;} }
                    if (max > 1) throw new AssertionError("pixel mismatch scenario=" + scenario + " max=" + max + " channels=" + changed);
                    long drawCount = cache.getPoseBatchDraws() - draws;
                    long partCount = cache.getPoseBatchParts() - parts;
                    boolean fallback = scenario == 9;
                    int expectedDraws = scenario == 7 && !RELATIVE ? 2 : 1;
                    int expectedParts = scenario == 7 && RELATIVE ? 35 : PARTS;
                    if (!fallback && (drawCount != expectedDraws || partCount != expectedParts))
                        throw new AssertionError("not batched: " + cache.getPoseBatchDetail() + " draws=" + drawCount + " parts=" + partCount);
                    if (fallback && drawCount != 0) throw new AssertionError("texgen did not fallback");
                    if (GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING) != original
                        || EarlyGlStateTracker.arrayBufferBinding() != original
                        || GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) != 0)
                        throw new AssertionError("binding restore");
                    IntBuffer attr = BufferUtils.createIntBuffer(4);
                    GL20.glGetVertexAttrib(7, GL20.GL_VERTEX_ATTRIB_ARRAY_ENABLED, attr);
                    if (attr.get(0) != 0) throw new AssertionError("generic attribute enable leaked");
                    if (GL11.glGetError() != GL11.GL_NO_ERROR) throw new AssertionError("GL error scenario=" + scenario);
                    System.out.println("scenario=" + scenario + " max_channel_error=" + max + " palette_draws=" + drawCount + " parts=" + partCount);
                }
                verifyInvalidation(cache, entries);
                verifyScopeExit(cache, entries, stamp);
                verifyVisibilityVariants(cache, entries, stamp, ledger);
                if (args.length == 0 || !"--correctness".equals(args[0])) {
                benchmark(cache, entries, stamp);
                for (int count : new int[]{2,4,6,7,8,16,32}) benchmark(cache, Arrays.copyOf(entries,count), stamp);
                for (int density : new int[]{1,6,24}) {
                    LwjglModelMeshCache.Entry[] dense = new LwjglModelMeshCache.Entry[128];
                    for (int i=0;i<dense.length;i++) {
                        byte[] geometry = new byte[96*density];
                        for(int face=0;face<density;face++) System.arraycopy(quad(),0,geometry,96*face,96);
                        int id=1000+density*200+i;
                        ModelMeshPayload p=new ModelMeshPayload(id,geometry,DefaultVertexFormats.POSITION_TEX_NORMAL,GL11.GL_QUADS,4*density,1,1);
                        if(!cache.accept(p,stamp)) throw new AssertionError("density upload");
                        dense[i]=cache.findStateBatchEntry(id,stamp);
                    }
                    System.out.println("density_quads_per_part="+density);
                    benchmark(cache,dense,stamp);
                }
                }
                System.out.println("model_pose_batch PASS parts=70 lighting=true normals=true fog=true color_barrier=true texgen_fallback=true renderer=" + GL11.glGetString(GL11.GL_RENDERER));
            } finally {
                cache.close(true); GL11.glFinish(); ledger.destroyAll(1);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0); GL15.glDeleteBuffers(original);
            }
            if (budget.snapshot().getGpuUsed() != 0 || budget.snapshot().getDirectUsed() != 0)
                throw new AssertionError("resource budget leaked");
        } finally { context.destroy(); }
    }

    private static void setup() {
        GL11.glViewport(0, 0, SIZE, SIZE);
        GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glLoadIdentity();
        EarlyMatrixStateTracker.seed(GL11.GL_MODELVIEW, floats(1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1),
            floats(1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1));
        if (scenario >= 12) {
            // Intentionally raw/unmirrored native base, as supplied by other mods.
            GL11.glTranslatef(.07F,-.09F,-.13F);
            GL11.glRotatef(23F,1F,2F,3F);
            GL11.glScalef(.73F,1.13F,.91F);
        }
        GL11.glDisable(GL11.GL_TEXTURE_2D); GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_BLEND); GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LIGHTING); GL11.glDisable(GL11.GL_FOG);
        GL11.glDisable(GL11.GL_COLOR_MATERIAL); GL11.glDisable(GL11.GL_NORMALIZE);
        GL11.glDisable(GL12.GL_RESCALE_NORMAL); GL11.glDisable(GL11.GL_TEXTURE_GEN_S);
        GL11.glColor4f(.25F, .5F, .75F, .6F);
        if (scenario == 1) { GL11.glEnable(GL11.GL_BLEND); GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA); }
        if ((scenario >= 2 && scenario <= 6) || scenario == 11 || scenario >= 12) {
            GL11.glEnable(GL11.GL_LIGHTING); GL11.glEnable(GL11.GL_LIGHT0); GL11.glEnable(GL11.GL_LIGHT1);
            GL11.glLight(GL11.GL_LIGHT0, GL11.GL_POSITION, floats(.3F,.6F,1,0));
            GL11.glLight(GL11.GL_LIGHT1, GL11.GL_POSITION, floats(-.4F,.2F,1,0));
            GL11.glLight(GL11.GL_LIGHT0, GL11.GL_DIFFUSE, floats(.7F,.6F,.5F,1));
            GL11.glLight(GL11.GL_LIGHT1, GL11.GL_DIFFUSE, floats(.2F,.3F,.4F,1));
            GL11.glMaterial(GL11.GL_FRONT_AND_BACK, GL11.GL_AMBIENT_AND_DIFFUSE, floats(.7F,.5F,.3F,.6F));
            if (scenario == 3 || scenario == 5 || scenario == 12) GL11.glEnable(GL11.GL_NORMALIZE);
            if (scenario == 4) GL11.glEnable(GL12.GL_RESCALE_NORMAL);
            if (scenario == 5) {
                GL11.glEnable(GL11.GL_COLOR_MATERIAL);
                GL11.glColorMaterial(GL11.GL_FRONT_AND_BACK, GL11.GL_AMBIENT_AND_DIFFUSE);
                GL11.glColor4f(.8F,.2F,.15F,.45F);
            }
            if (scenario == 6) { GL11.glLightModeli(GL11.GL_LIGHT_MODEL_TWO_SIDE, 1); }
        }
        if (scenario == 8 || scenario == 10) {
            GL11.glEnable(GL11.GL_FOG); GL11.glFogi(GL11.GL_FOG_MODE, GL11.GL_LINEAR);
            GL11.glFogf(GL11.GL_FOG_START, 0); GL11.glFogf(GL11.GL_FOG_END, .8F);
            GL11.glFog(GL11.GL_FOG_COLOR, floats(.2F,.4F,.6F,1));
        }
        if (GLContext.getCapabilities().GL_NV_fog_distance) {
            int distanceMode = scenario == 10 ? 0x855B : 0x855C;
            GL11.glFogi(0x855A, distanceMode); EarlyGlStateTracker.fogi(0x855A, distanceMode);
        }
        if (scenario == 9) GL11.glEnable(GL11.GL_TEXTURE_GEN_S);
        GL11.glClearColor(0,0,0,0); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
    }

    private static byte[] draw(LwjglModelMeshCache cache, LwjglModelMeshCache.Entry[] entries,
                               FrameStamp stamp, boolean batched) {
        batched = batched && !displayLists;
        if (batched && !(stateOnly ? cache.beginStateBatch() : cache.beginStateBatch(entries, entries.length))) throw new AssertionError("begin");
        try {
            for (int i = 0; i < entries.length; i++) {
                if (scenario == 7 && i == 35) {
                    EarlyModelBatchBarrier.barrier(); GL11.glColor4f(.9F,.1F,.2F,.4F);
                }
                push();
                float x = (i % 10 - 4.5F) * .17F, y = (i / 10 - 3) * .24F, z = i * .008F;
                if (displayLists) { GL11.glTranslatef(x,y,z); GL11.glRotatef(i * 3,0,1,0); }
                else {
                    if (!EarlyModelBatchBarrier.deferTranslate(x,y,z)) { GL11.glTranslatef(x,y,z); EarlyMatrixStateTracker.translate(x,y,z); }
                    if (!EarlyModelBatchBarrier.deferRotate(i * 3,0,1,0)) { GL11.glRotatef(i * 3,0,1,0); EarlyMatrixStateTracker.rotate(i * 3,0,1,0); }
                }
                if (scenario == 11) {
                    if (displayLists) { GL11.glRotatef(i * 2,1,0,0); GL11.glRotatef(i * 5,0,0,1); }
                    else {
                        if (!EarlyModelBatchBarrier.deferRotate(i * 2,1,0,0)) { GL11.glRotatef(i * 2,1,0,0); EarlyMatrixStateTracker.rotate(i * 2,1,0,0); }
                        if (!EarlyModelBatchBarrier.deferRotate(i * 5,0,0,1)) { GL11.glRotatef(i * 5,0,0,1); EarlyMatrixStateTracker.rotate(i * 5,0,0,1); }
                    }
                    push();
                    if (displayLists) GL11.glTranslatef(.01F,.02F,0F);
                    else if (!EarlyModelBatchBarrier.deferTranslate(.01F,.02F,0F)) { GL11.glTranslatef(.01F,.02F,0F); EarlyMatrixStateTracker.translate(.01F,.02F,0F); }
                }
                if (scenario >= 3 && scenario <= 6) {
                    float sx = scenario == 4 ? .7F : 1.3F;
                    if (displayLists) GL11.glScalef(sx,.7F,.7F);
                    else if (!EarlyModelBatchBarrier.deferScale(sx,.7F,.7F)) { GL11.glScalef(sx,.7F,.7F); EarlyMatrixStateTracker.scale(sx,.7F,.7F); }
                }
                try {
                    if (displayLists) GL11.glCallList(legacyLists + i);
                    else if (!(batched ? cache.drawInStateBatch(entries[i]) : cache.drawCurrent(entries[i], stamp)))
                        throw new AssertionError("draw");
                } finally {
                    if (scenario == 11) { pop(); }
                    pop();
                }
            }
        } finally { if (batched) cache.endStateBatch(); }
        if (timing) return NO_PIXELS;
        ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        GL11.glReadPixels(0,0,SIZE,SIZE,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
        byte[] bytes = new byte[pixels.remaining()]; pixels.get(bytes); return bytes;
    }

    private static void verifyInvalidation(LwjglModelMeshCache cache, LwjglModelMeshCache.Entry[] entries) {
        scenario=2; setup(); cache.seedPoseState();
        int depth=GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH);
        if(!cache.beginStateBatch(entries, entries.length)) throw new AssertionError("invalidation begin");
        try {
            if(!EarlyModelBatchBarrier.deferTranslate(.1F,.2F,.3F)) throw new AssertionError("parent defer");
            push();
            if(!EarlyModelBatchBarrier.deferScale(2F,3F,4F)) throw new AssertionError("child defer");
            push();
            if(!EarlyModelBatchBarrier.deferTranslate(.2F,.1F,0F)) throw new AssertionError("grandchild defer");
            float[] expected=EarlyMatrixStateTracker.modelView();
            if (RELATIVE) try {
                expected=new float[16];
                if(!(Boolean)EarlyMatrixStateTracker.class.getMethod("copyRelativePose",float[].class,int.class)
                    .invoke(null,expected,0)) throw new AssertionError("relative pose unavailable");
            } catch (ReflectiveOperationException failure) { throw new RuntimeException(failure); }
            if(!cache.drawInStateBatch(entries[0])) throw new AssertionError("deferred draw");
            EarlyMatrixStateTracker.invalidate();
            FloatBuffer actual=BufferUtils.createFloatBuffer(16); GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX,actual);
            for(int i=0;i<16;i++) if(Math.abs(actual.get(i)-expected[i])>0.00001F) throw new AssertionError("invalidated top not synchronized");
            if(EarlyModelBatchBarrier.deferTranslate(1F,0F,0F)) throw new AssertionError("deferred after invalidation");
            long ordinaryDraws = cache.getStateBatchDraws();
            if(!cache.drawInStateBatch(entries[1]) || cache.getStateBatchDraws() != ordinaryDraws + 1)
                throw new AssertionError("ordinary draw stopped after pose matrix invalidation");
            GL11.glPopMatrix(); GL11.glPopMatrix();
            GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX,actual);
            if(Math.abs(actual.get(12)-.1F)>.00001F || Math.abs(actual.get(13)-.2F)>.00001F || actual.get(0)!=1F)
                throw new AssertionError("saved native parent was stale");
        } finally { cache.endStateBatch(); }
        if(GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH)!=depth) throw new AssertionError("native stack depth leaked");
        System.out.println("nested_matrix_invalidation=true native_parent_restored=true native_stack_depth_equal=true");
    }
    private static void verifyScopeExit(LwjglModelMeshCache cache, LwjglModelMeshCache.Entry[] entries, FrameStamp stamp) throws Exception {
        scenario=2; setup(); cache.seedPoseState();
        int depth=GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH);
        push(); // This native level belongs to the caller, before the palette scope.
        if(!cache.beginStateBatch(entries,entries.length)) throw new AssertionError("scope exit begin");
        try {
            EarlyModelBatchBarrier.deferTranslate(.1F,.2F,.3F);
            if(!cache.drawInStateBatch(entries[0])) throw new AssertionError("scope exit draw");
            if(EarlyModelBatchBarrier.deferPop()) throw new AssertionError("scope consumed caller level");
            GL11.glPopMatrix(); EarlyMatrixStateTracker.popMatrix();
            if(EarlyMatrixStateTracker.isModelViewKnown()) throw new AssertionError("scope invented the caller's older matrix");
        } finally {cache.endStateBatch();}
        if(GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH)!=depth) throw new AssertionError("cross-scope depth");
        setup(); cache.seedPoseState();
        java.lang.reflect.Field pf=LwjglModelMeshCache.class.getDeclaredField("poseBatch"); pf.setAccessible(true);
        Object renderer=pf.get(cache); java.lang.reflect.Field capacity=renderer.getClass().getDeclaredField("paletteCapacity"); capacity.setAccessible(true);
        int old=capacity.getInt(renderer);
        try {
            capacity.setInt(renderer,32);
            byte[] expected=draw(cache,entries,stamp,false); setup(); cache.seedPoseState();
            long before=cache.getPoseBatchDraws(); byte[] actual=draw(cache,entries,stamp,true);
            if(!Arrays.equals(expected,actual) || cache.getPoseBatchDraws()-before!=3) throw new AssertionError("32 palette split");
        } finally {capacity.setInt(renderer,old);}
        dev.rlcraft.ice.optimizer.compat.gl.EarlyModelLightingState.invalidate();
        long before=cache.getPoseBatchDraws();
        draw(cache,entries,stamp,true);
        if(cache.getPoseBatchDraws()!=before+1)
            throw new AssertionError("actual pose consumer did not recover native state");
        System.out.println("cross_scope_pop=true palette32_segments=true unknown_state_recovered_by_consumer=true");
    }
    private static void verifyVisibilityVariants(LwjglModelMeshCache cache, LwjglModelMeshCache.Entry[] entries,
                                                 FrameStamp stamp, ResourceLedger ledger) {
        scenario=2; setup(); cache.seedPoseState();
        LwjglModelMeshCache.Entry[] first=Arrays.copyOf(entries,8), second=Arrays.copyOf(entries,16);
        draw(cache,first,stamp,true); draw(cache,second,stamp,true);
        long created=ledger.snapshot().getCreated();
        long partsBefore=cache.getPoseBatchParts();
        for(int i=0;i<24;i++) draw(cache, (i&1)==0 ? first : second, stamp,true);
        if(ledger.snapshot().getCreated()!=created || cache.getPoseBatchParts()-partsBefore!=12*(8+16))
            throw new AssertionError("visibility shape cache rebuilt a warmed variant");
        System.out.println("alternating_visible_parts_8_16=true warm_gpu_creations=0 alternating_palette_parts=288");
    }
    private static void benchmark(LwjglModelMeshCache cache, LwjglModelMeshCache.Entry[] entries, FrameStamp stamp) {
        scenario=11; setup(); cache.seedPoseState(); timing=true;
        try {
            boolean compareLists = Boolean.getBoolean("ice.compareDisplayLists");
            if (compareLists) {
                legacyLists = GL11.glGenLists(entries.length);
                if (legacyLists == 0) throw new AssertionError("legacy list allocation");
                try {
                    java.lang.reflect.Field geometryField = entries[0].getClass().getDeclaredField("geometry");
                    geometryField.setAccessible(true);
                    for (int i = 0; i < entries.length; i++) {
                        ModelMeshPayload payload = (ModelMeshPayload) geometryField.get(entries[i]);
                        ByteBuffer vertices = ByteBuffer.wrap(payload.getVertices()).order(ByteOrder.nativeOrder());
                        GL11.glNewList(legacyLists + i, GL11.GL_COMPILE);
                        GL11.glBegin(GL11.GL_QUADS);
                        while (vertices.hasRemaining()) {
                            float x=vertices.getFloat(), y=vertices.getFloat(), z=vertices.getFloat();
                            float u=vertices.getFloat(), v=vertices.getFloat();
                            byte nx=vertices.get(), ny=vertices.get(), nz=vertices.get(); vertices.get();
                            GL11.glNormal3b(nx,ny,nz); GL11.glTexCoord2f(u,v); GL11.glVertex3f(x,y,z);
                        }
                        GL11.glEnd(); GL11.glEndList();
                    }
                } catch (ReflectiveOperationException failure) { throw new RuntimeException(failure); }
                timing=false; displayLists=true; setup();
                byte[] reference=draw(cache,entries,stamp,false);
                displayLists=false; setup(); cache.seedPoseState();
                byte[] candidate=draw(cache,entries,stamp,true);
                int error=0;
                for(int i=0;i<reference.length;i++) error=Math.max(error,Math.abs((reference[i]&255)-(candidate[i]&255)));
                if(error>1) throw new AssertionError("display-list pixel difference="+error);
                System.out.println("display_list_pixel_error="+error+" parts="+entries.length);
                timing=true;
            }
            try {
                java.lang.reflect.Field pf=LwjglModelMeshCache.class.getDeclaredField("poseBatch"); pf.setAccessible(true);
                Object renderer=pf.get(cache); java.lang.reflect.Method capture=renderer.getClass().getDeclaredMethod("captureState"); capture.setAccessible(true);
                GL11.glFinish(); long start=System.nanoTime();
                for(int i=0;i<1000;i++) capture.invoke(renderer);
                System.out.println("capture_state_us="+((System.nanoTime()-start)/1000000.0));
            } catch(Exception e) {throw new RuntimeException(e);}
            int modes=compareLists?3:2;
            long[][] nanos = new long[modes][11];
            for (int warm=0;warm<500;warm++) for (int mode=0; mode<modes; mode++) {
                stateOnly=mode==0; displayLists=mode==2;
                draw(cache,entries,stamp,true);
            }
            for (int sample=0;sample<11;sample++) for (int turn=0;turn<modes;turn++) {
                int mode=(sample+turn)%modes;
                stateOnly=mode==0; displayLists=mode==2;
                GL11.glFinish();
                long start=System.nanoTime();
                for (int repeat=0;repeat<100;repeat++) draw(cache,entries,stamp,true);
                nanos[mode][sample]=(System.nanoTime()-start)/100;
                GL11.glFinish();
            }
            for(long[] values:nanos) Arrays.sort(values);
            System.out.println("cpu_submit_parts="+entries.length+" state_batch_us="+(nanos[0][5]/1000.0)+" palette_us="+(nanos[1][5]/1000.0)
                +(compareLists?" display_list_us="+(nanos[2][5]/1000.0):""));
        } finally {
            timing=false; stateOnly=false; displayLists=false;
            if(legacyLists!=0) { GL11.glDeleteLists(legacyLists,entries.length); legacyLists=0; }
        }
    }
    private static void push() {
        if (displayLists) { GL11.glPushMatrix(); return; }
        if (!EarlyModelBatchBarrier.deferPush()) {
            GL11.glPushMatrix(); EarlyMatrixStateTracker.pushMatrix();
        }
    }
    private static void pop() {
        if (displayLists) { GL11.glPopMatrix(); return; }
        if (!EarlyModelBatchBarrier.deferPop()) {
            GL11.glPopMatrix(); EarlyMatrixStateTracker.popMatrix();
        }
    }
    private static FloatBuffer floats(float... values) { FloatBuffer b=BufferUtils.createFloatBuffer(values.length); b.put(values).flip(); return b; }
    private static byte[] quad() {
        ByteBuffer b = ByteBuffer.allocate(96).order(ByteOrder.nativeOrder());
        for (float[] xy : new float[][]{{-.065F,-.075F},{.065F,-.075F},{.065F,.075F},{-.065F,.075F}}) {
            b.putFloat(xy[0]).putFloat(xy[1]).putFloat(0).putFloat(0).putFloat(0);
            b.put((byte)0).put((byte)0).put((byte)127).put((byte)0);
        } return b.array();
    }
    private static void seed(int original) {
        EarlyGlStateTracker.useProgram(0); EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER, original);
        EarlyGlStateTracker.clientActiveTexture(GL13.GL_TEXTURE0); EarlyGlStateTracker.depthFunction(GL11.GL_LESS);
        EarlyGlStateTracker.seedDrawState(0,0,0,false,GL11.GL_ONE,GL11.GL_ZERO,GL11.GL_ONE,GL11.GL_ZERO,false,true,false,15,.25F,.5F,.75F,.6F);
    }
}















