package dev.rlcraft.ice.optimizer.render.visibility;

import static org.junit.Assert.*;
import dev.rlcraft.ice.optimizer.compat.chunk.TerrainRenderListAccessor;
import dev.rlcraft.ice.optimizer.render.terrain.TerrainVertexSpace;
import dev.rlcraft.ice.optimizer.render.terrain.TerrainUploadContext;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.Random;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import org.junit.Test;
import sun.misc.Unsafe;

public final class CurrentFrameTerrainOcclusionTest {
    @Test public void adaptiveEmptyProbesNeverReuseHiddenDrawsAndRecoverForANewWall() throws Exception {
        CurrentFrameTerrainOcclusion source = new CurrentFrameTerrainOcclusion();
        TestChunk left = chunk(), right = chunk();
        source.uploaded(stage(source, left, -.9F, -.7F, .2F));
        source.uploaded(stage(source, right, .7F, .9F, .2F));
        Container view = new Container(left, right);
        for (int frame = 0; frame < 200; frame++) {
            view.reset(left, right);
            assertEquals(0, source.filterAdaptive(view, BlockRenderLayer.SOLID, matrices()));
            assertEquals(Arrays.<RenderChunk>asList(left, right), view.values);
        }
        assertTrue(source.getSkippedProbes() > 150);
        TestChunk wall = chunk(), behind = chunk();
        source.uploaded(stage(source, wall, -.95F, .95F, -.5F));
        source.uploaded(stage(source, behind, -.1F, .1F, .5F));
        boolean recovered = false;
        for (int frame = 0; frame < 32; frame++) {
            view.reset(wall, behind);
            if (source.filterAdaptive(view, BlockRenderLayer.SOLID, matrices()) == 1) {
                recovered = true; break;
            }
            assertEquals(Arrays.<RenderChunk>asList(wall, behind), view.values);
        }
        assertTrue(recovered);
        wall.compiled = new CompiledChunk(); // The previous positive result is now invalid.
        view.reset(wall, behind);
        assertEquals(0, source.filterAdaptive(view, BlockRenderLayer.SOLID, matrices()));
        assertEquals(Arrays.<RenderChunk>asList(wall, behind), view.values);
    }
    @Test public void gpuPacketKeepsCandidateIndicesAndCameraRelativeEvidence() throws Exception {
        CurrentFrameTerrainOcclusion source = new CurrentFrameTerrainOcclusion();
        TestChunk known = chunk(), missing = chunk();
        known.position = new BlockPos(1000000, 0, -2000000);
        source.uploaded(stage(source, known, -.5F, .5F, .2F));
        Container view = new Container(missing, known);
        view.viewX = 999999.75D; view.viewZ = -2000000.5D;
        ByteBuffer bounds = ByteBuffer.allocateDirect(128).order(java.nio.ByteOrder.nativeOrder());
        ByteBuffer faces = ByteBuffer.allocateDirect(128).order(java.nio.ByteOrder.nativeOrder());
        assertEquals(1, source.writeGpuInput(view, bounds, faces, 2, 1));
        assertEquals(128, bounds.remaining());
        assertEquals(0D, bounds.getDouble(24), 0D);
        assertEquals(1D, bounds.getDouble(64 + 24), 0D);
        assertTrue(bounds.getDouble(64) > -1D && bounds.getDouble(64) < 0D);
        assertTrue(faces.getDouble(16) > 0D && faces.getDouble(16) < 1D);
        assertSame(missing, view.values.get(0)); assertSame(known, view.values.get(1));
        known.compiled = new CompiledChunk();
        assertEquals(0, source.writeGpuInput(view, bounds, faces, 2, 1));
        assertEquals(0D, bounds.getDouble(64 + 24), 0D);
        assertEquals(0, faces.remaining());
    }
    @Test public void pendingBytesOnlyBecomeEvidenceAfterSuccessfulUpload() throws Exception {
        CurrentFrameTerrainOcclusion occlusion = new CurrentFrameTerrainOcclusion();
        TestChunk wall = chunk(), behind = chunk();
        VertexBuffer wallVbo = stage(occlusion,wall,-.95F,.95F,-.5F);
        VertexBuffer behindVbo = stage(occlusion,behind,-.1F,.1F,.5F);
        Container list = new Container(wall,behind);
        assertEquals(0,occlusion.filter(list,BlockRenderLayer.SOLID,matrices()));
        occlusion.legacyUploadStarting(wallVbo);
        occlusion.uploaded(wallVbo);
        occlusion.uploaded(behindVbo);
        assertEquals(2,occlusion.getMeshCount());
        assertEquals(1,occlusion.filter(list,BlockRenderLayer.SOLID,matrices()));
        assertEquals(Arrays.<RenderChunk>asList(wall),list.values);
        occlusion.release(wallVbo);
        list.reset(wall,behind);
        assertEquals(0,occlusion.filter(list,BlockRenderLayer.SOLID,matrices()));
        assertEquals(2,list.values.size());
        occlusion.clear();
        assertEquals(0,occlusion.getMeshCount());
        assertEquals(0,occlusion.getRetainedBytes());
    }

    @Test public void sourceGenerationMovementFailureAndWorldClearInvalidateEvidence() throws Exception {
        CurrentFrameTerrainOcclusion occlusion = new CurrentFrameTerrainOcclusion();
        TestChunk wall = chunk(), behind = chunk();
        VertexBuffer wallVbo = stage(occlusion,wall,-.95F,.95F,-.5F);
        VertexBuffer behindVbo = stage(occlusion,behind,-.1F,.1F,.5F);
        occlusion.uploaded(wallVbo); occlusion.uploaded(behindVbo);
        Container list = new Container(wall,behind);
        CompiledChunk original = wall.compiled;
        wall.compiled = new CompiledChunk();
        assertEquals(0,occlusion.filter(list,BlockRenderLayer.SOLID,matrices()));
        wall.compiled = original;
        wall.position = new BlockPos(16,0,0);
        assertEquals(0,occlusion.filter(list,BlockRenderLayer.SOLID,matrices()));
        wall.position = BlockPos.ORIGIN;
        list.viewX = 3;
        assertEquals(0,occlusion.filter(list,BlockRenderLayer.SOLID,matrices()));
        list.viewX = 0;
        occlusion.release(wallVbo); // dispatcher exception/deletion has the same discard operation
        occlusion.uploaded(wallVbo);
        assertEquals(0,occlusion.filter(list,BlockRenderLayer.SOLID,matrices()));
        stage(occlusion,wall,-.95F,.95F,-.5F);
        occlusion.clear();
        assertEquals(0,occlusion.getMeshCount());
        assertEquals(0,occlusion.filter(list,BlockRenderLayer.SOLID,matrices()));
    }

    @Test public void compactionKeepsUnknownEntriesAndOriginalOrderWithoutDependingOnArenaOwnership() throws Exception {
        CurrentFrameTerrainOcclusion occlusion = new CurrentFrameTerrainOcclusion();
        TestChunk wall=chunk(), behind=chunk(), unknownA=chunk(), unknownB=chunk();
        occlusion.uploaded(stage(occlusion,wall,-.95F,.95F,-.5F));
        occlusion.uploaded(stage(occlusion,behind,-.1F,.1F,.5F));
        Container list = new Container(unknownA,behind,wall,unknownB);
        assertEquals(0,occlusion.filter(list,BlockRenderLayer.TRANSLUCENT,matrices()));
        assertEquals(1,occlusion.filter(list,BlockRenderLayer.SOLID,matrices()));
        assertEquals(Arrays.<RenderChunk>asList(unknownA,wall,unknownB),list.values);
    }

    @Test public void arrayListSubclassUsesTheSameConservativeCompaction() throws Exception {
        CurrentFrameTerrainOcclusion occlusion=new CurrentFrameTerrainOcclusion();
        TestChunk wall=chunk(),behind=chunk();
        occlusion.uploaded(stage(occlusion,wall,-.95F,.95F,-.5F));
        occlusion.uploaded(stage(occlusion,behind,-.1F,.1F,.5F));
        Container list=new Container(new ArrayList<RenderChunk>() { },behind,wall);
        assertEquals(1,occlusion.filter(list,BlockRenderLayer.SOLID,matrices()));
        assertEquals(Arrays.<RenderChunk>asList(wall),list.values);
    }

    @Test public void nestedUploadCapacityPreservesExistingPendingTransactions() throws Exception {
        CurrentFrameTerrainOcclusion occlusion=new CurrentFrameTerrainOcclusion();
        VertexBuffer[] pending=new VertexBuffer[8];
        TestChunk[] chunks=new TestChunk[8];
        for(int i=0;i<pending.length;i++) {
            chunks[i]=chunk();
            pending[i]=stage(occlusion,chunks[i],-.1F,.1F,.5F);
        }
        TestChunk additional=chunk();
        BufferBuilder builder=new BufferBuilder(28);
        builder.begin(7,DefaultVertexFormats.BLOCK);
        TerrainUploadContext.begin(BlockRenderLayer.SOLID,additional,additional.compiled);
        occlusion.capture(TerrainUploadContext.take(),builder,
            (VertexBuffer)unsafe().allocateInstance(VertexBuffer.class));
        for(VertexBuffer vbo:pending)occlusion.uploaded(vbo);
        assertEquals(8,occlusion.getMeshCount());
        for(TestChunk chunk:chunks)assertNotNull(chunk.compiled);
    }

    @Test public void rotatingAcrossSourceBoundsNearPlaneKeepsValidWallCoverage() throws Exception {
        movingWallCoverage(2,20);
    }

    @Test public void walkingInsideSourceBoundsKeepsValidWallCoverage() throws Exception {
        movingWallCoverage(0,0);
    }

    private static void movingWallCoverage(double movedZ,double yaw) throws Exception {
        CurrentFrameTerrainOcclusion occlusion=new CurrentFrameTerrainOcclusion();
        TestChunk wall=chunk(),behind=chunk(),unknown=chunk();
        occlusion.uploaded(stage(occlusion,wall,wallWithNearPlaneBounds()));
        occlusion.uploaded(stage(occlusion,behind,-.1F,.1F,-10F));
        Container list=new Container(unknown,behind,wall);
        list.viewZ=2;
        assertEquals(1,occlusion.filter(list,BlockRenderLayer.SOLID,perspective(0)));
        assertEquals(Arrays.<RenderChunk>asList(unknown,wall),list.values);
        list.reset(unknown,behind,wall);
        list.viewZ=movedZ;
        assertEquals(1,occlusion.filter(list,BlockRenderLayer.SOLID,perspective(yaw)));
        // The clipped source is itself never eligible to disappear.
        assertEquals(Arrays.<RenderChunk>asList(unknown,wall),list.values);
    }

    @Test public void nearPlaneIntersectingTargetRemainsVisibleBehindAnotherWall() throws Exception {
        CurrentFrameTerrainOcclusion occlusion=new CurrentFrameTerrainOcclusion();
        TestChunk wall=chunk(),clipped=chunk();
        occlusion.uploaded(stage(occlusion,wall,-8F,8F,-2F));
        occlusion.uploaded(stage(occlusion,clipped,wallWithNearPlaneBounds()));
        Container list=new Container(wall,clipped);
        assertEquals(0,occlusion.filter(list,BlockRenderLayer.SOLID,perspective(0)));
        assertEquals(Arrays.<RenderChunk>asList(wall,clipped),list.values);
    }

    @Test public void individualQuadCrossingCameraPlaneStillCannotHideTargets() throws Exception {
        CurrentFrameTerrainOcclusion occlusion=new CurrentFrameTerrainOcclusion();
        TestChunk clippedWall=chunk(),behind=chunk();
        occlusion.uploaded(stage(occlusion,clippedWall,-8F,8F,-1F));
        occlusion.uploaded(stage(occlusion,behind,-.1F,.1F,-10F));
        Container list=new Container(clippedWall,behind);
        assertEquals(0,occlusion.filter(list,BlockRenderLayer.SOLID,perspective(20)));
        assertEquals(Arrays.<RenderChunk>asList(clippedWall,behind),list.values);
    }

    @Test public void subCellSourcesSkipRasterButBecomeSourcesWhenViewMagnifiesThem() throws Exception {
        CurrentFrameTerrainOcclusion occlusion = new CurrentFrameTerrainOcclusion();
        TestChunk wall = chunk(), behind = chunk();
        occlusion.uploaded(stage(occlusion,wall,-.005F,.005F,-.5F));
        occlusion.uploaded(stage(occlusion,behind,-.0001F,.0001F,.5F));
        Container list = new Container(behind,wall);
        assertEquals(0,occlusion.filter(list,BlockRenderLayer.SOLID,matrices()));
        assertEquals(0,occlusion.getRasterizedQuads());
        assertEquals(Arrays.<RenderChunk>asList(behind,wall),list.values);
        float[] zoom = SoftwareOcclusionRasterTest.identity(); zoom[0] = zoom[5] = 100;
        RenderMatrixBridge.capture(FloatBuffer.wrap(SoftwareOcclusionRasterTest.identity()),FloatBuffer.wrap(zoom),
            IntBuffer.wrap(new int[]{0,0,800,600}));
        assertEquals(1,occlusion.filter(list,BlockRenderLayer.SOLID,RenderMatrixBridge.snapshot()));
        assertEquals(Arrays.<RenderChunk>asList(wall),list.values);
        assertTrue(occlusion.getRasterizedQuads() > 0);
    }

    @Test public void onDemandCandidatesMatchStableNearDepthOrderIncludingTies() throws Exception {
        CurrentFrameTerrainOcclusion occlusion = new CurrentFrameTerrainOcclusion();
        Field storage = CurrentFrameTerrainOcclusion.class.getDeclaredField("work"); storage.setAccessible(true);
        Object[] work = (Object[])storage.get(occlusion);
        Field index = work[0].getClass().getDeclaredField("index"); index.setAccessible(true);
        Field bounds = work[0].getClass().getDeclaredField("bounds"); bounds.setAccessible(true);
        final double[] depths = new double[work.length];
        Integer[] expected = new Integer[work.length];
        Random random = new Random(0xCAAD1DA7EL);
        for (int i = 0; i < work.length; i++) {
            depths[i] = random.nextInt(32) / 32.0;
            index.setInt(work[i],i);
            ((SoftwareOcclusionRaster.Bounds)bounds.get(work[i])).nearest = depths[i];
            expected[i] = i;
        }
        Arrays.sort(expected,new Comparator<Integer>() {
            @Override public int compare(Integer a,Integer b) {
                int result = Double.compare(depths[a],depths[b]);
                return result != 0 ? result : Integer.compare(a,b);
            }
        });
        Method build = CurrentFrameTerrainOcclusion.class.getDeclaredMethod("heapNearFirst",int.class);
        Method sift = CurrentFrameTerrainOcclusion.class.getDeclaredMethod("sift",int.class,int.class);
        build.setAccessible(true); sift.setAccessible(true); build.invoke(occlusion,work.length);
        for (int read = 0, end = work.length - 1; end >= 0; read++,end--) {
            assertEquals(expected[read].intValue(),index.getInt(work[0]));
            Object first = work[0]; work[0] = work[end]; work[end] = first;
            sift.invoke(occlusion,0,end);
        }
    }

    @Test public void compilerSnapshotIsReusedAtRenderThreadEntryAndPublishedAfterUpload() throws Exception {
        CurrentFrameTerrainOcclusion occlusion = new CurrentFrameTerrainOcclusion();
        occlusion.publishTextures(OpaqueTerrainTexturesSnapshotTest.atlas());
        TestChunk wall = chunk(), behind = chunk();
        BufferBuilder builder = builder(new BufferBuilder(112), -.95F, .95F, -.5F);
        Thread worker = new Thread(() -> occlusion.prepare(BlockRenderLayer.SOLID, builder, wall, wall.compiled));
        worker.start(); worker.join();
        Field preparedField = CurrentFrameTerrainOcclusion.class.getDeclaredField("prepared");
        preparedField.setAccessible(true);
        Map<?,?> prepared = (Map<?,?>)preparedField.get(occlusion);
        Object snapshot = prepared.get(builder);
        assertNotNull(snapshot);
        occlusion.prepare(BlockRenderLayer.SOLID, builder, wall, wall.compiled);
        assertSame("main dispatcher entry must not rebuild the snapshot", snapshot, prepared.get(builder));
        VertexBuffer vbo = (VertexBuffer)unsafe().allocateInstance(VertexBuffer.class);
        TerrainUploadContext.begin(BlockRenderLayer.SOLID, wall, wall.compiled);
        occlusion.capture(TerrainUploadContext.take(), builder, vbo);
        assertTrue(prepared.isEmpty());
        assertEquals(0, occlusion.getMeshCount());
        occlusion.uploaded(vbo);
        occlusion.uploaded(stage(occlusion,behind,-.1F,.1F,.5F));
        assertEquals(1,occlusion.filter(new Container(wall,behind),BlockRenderLayer.SOLID,matrices()));
    }

    @Test public void clearDuringCompilerCaptureCannotPublishTheOldSnapshot() throws Exception {
        CurrentFrameTerrainOcclusion occlusion = new CurrentFrameTerrainOcclusion();
        occlusion.publishTextures(OpaqueTerrainTexturesSnapshotTest.atlas());
        TestChunk chunk = chunk();
        final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch resume = new java.util.concurrent.CountDownLatch(1);
        BufferBuilder builder = builder(new BufferBuilder(112) {
            @Override public ByteBuffer getByteBuffer() {
                entered.countDown();
                try { resume.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
                return super.getByteBuffer();
            }
        }, -.9F, .9F, -.5F);
        Thread worker = new Thread(() -> occlusion.prepare(BlockRenderLayer.SOLID,builder,chunk,chunk.compiled));
        worker.start();
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        occlusion.clear(); resume.countDown(); worker.join();
        VertexBuffer vbo=(VertexBuffer)unsafe().allocateInstance(VertexBuffer.class);
        TerrainUploadContext.begin(BlockRenderLayer.SOLID,chunk,chunk.compiled);
        occlusion.capture(TerrainUploadContext.take(),builder,vbo); occlusion.uploaded(vbo);
        assertEquals(0,occlusion.getMeshCount());
        // World/context resets keep the immutable atlas snapshot usable.
        occlusion.prepare(BlockRenderLayer.SOLID,builder,chunk,chunk.compiled);
        TerrainUploadContext.begin(BlockRenderLayer.SOLID,chunk,chunk.compiled);
        occlusion.capture(TerrainUploadContext.take(),builder,vbo); occlusion.uploaded(vbo);
        assertEquals(1,occlusion.getMeshCount());
    }

    @Test public void movingChunkDuringPreparationCannotAttachOldGeometryToTheNewPosition() throws Exception {
        CurrentFrameTerrainOcclusion occlusion=new CurrentFrameTerrainOcclusion();
        occlusion.publishTextures(OpaqueTerrainTexturesSnapshotTest.atlas());
        TestChunk wall=chunk(),behind=chunk();
        BufferBuilder builder=builder(new BufferBuilder(112) {
            @Override public ByteBuffer getByteBuffer() {
                wall.position=new BlockPos(16,0,0);
                return super.getByteBuffer();
            }
        },-.95F,.95F,-.5F);
        occlusion.prepare(BlockRenderLayer.SOLID,builder,wall,wall.compiled);
        VertexBuffer vbo=(VertexBuffer)unsafe().allocateInstance(VertexBuffer.class);
        TerrainUploadContext.begin(BlockRenderLayer.SOLID,wall,wall.compiled);
        occlusion.capture(TerrainUploadContext.take(),builder,vbo);occlusion.uploaded(vbo);
        occlusion.uploaded(stage(occlusion,behind,-.1F,.1F,.5F));
        assertEquals(0,occlusion.filter(new Container(wall,behind),BlockRenderLayer.SOLID,matrices()));
    }

    @Test public void nextAtlasPublicationInvalidatesPreparedAndUploadedEvidence() throws Exception {
        CurrentFrameTerrainOcclusion occlusion=new CurrentFrameTerrainOcclusion();
        occlusion.publishTextures(OpaqueTerrainTexturesSnapshotTest.atlas());
        TestChunk chunk=chunk(); BufferBuilder builder=builder(new BufferBuilder(112),-.9F,.9F,-.5F);
        occlusion.prepare(BlockRenderLayer.SOLID,builder,chunk,chunk.compiled);
        occlusion.publishTextures(OpaqueTerrainTexturesSnapshotTest.atlas());
        VertexBuffer vbo=(VertexBuffer)unsafe().allocateInstance(VertexBuffer.class);
        TerrainUploadContext.begin(BlockRenderLayer.SOLID,chunk,chunk.compiled);
        occlusion.capture(TerrainUploadContext.take(),builder,vbo);occlusion.uploaded(vbo);
        assertEquals(0,occlusion.getMeshCount());
    }

    private static BufferBuilder builder(BufferBuilder builder,float min,float max,float z) {
        builder.begin(7,DefaultVertexFormats.BLOCK);
        float[] quad=SoftwareOcclusionRasterTest.quad(min,min,max,max,z);
        for(int i=0;i<4;i++)builder.pos(quad[i*3],quad[i*3+1],quad[i*3+2]).color(255,255,255,255)
            .tex(i==1||i==2?.9:.1,i>=2?.9:.1).lightmap(0,0).endVertex();
        builder.finishDrawing(); return builder;
    }

    private static TerrainOccluderMesh wallWithNearPlaneBounds() {
        ByteBuffer bytes=TerrainOccluderMeshTest.buffer(2);
        TerrainOccluderMeshTest.put(bytes,SoftwareOcclusionRasterTest.quad(-8,-8,8,8,-4));
        // Real unsupported sloped geometry extends the source bounds across
        // the camera plane; it must not disqualify the separate opaque wall.
        TerrainOccluderMeshTest.put(bytes,new float[]{-8,-8,-16,8,-8,-16,8,8,0,-8,8,0});
        return TerrainOccluderMeshTest.capture(bytes,new TerrainOccluderMesh.Opacity(){
            @Override public boolean opaque(float u0,float v0,float u1,float v1){return true;}
        });
    }

    private static RenderMatrixBridge.Snapshot perspective(double yaw) {
        float[] model=SoftwareOcclusionRasterTest.identity();
        double angle=Math.toRadians(yaw);
        model[0]=model[10]=(float)Math.cos(angle);
        model[2]=-(float)Math.sin(angle);model[8]=(float)Math.sin(angle);
        float[] projection=new float[16];
        projection[0]=projection[5]=1;projection[10]=-100.1F/99.9F;
        projection[11]=-1;projection[14]=-.2F*100F/99.9F;
        RenderMatrixBridge.capture(FloatBuffer.wrap(model),FloatBuffer.wrap(projection),
            IntBuffer.wrap(new int[]{0,0,800,600}));
        return RenderMatrixBridge.snapshot();
    }

    private static RenderMatrixBridge.Snapshot matrices() {
        float[] identity=SoftwareOcclusionRasterTest.identity();
        RenderMatrixBridge.capture(FloatBuffer.wrap(identity),FloatBuffer.wrap(identity),
            IntBuffer.wrap(new int[]{0,0,800,600}));
        return RenderMatrixBridge.snapshot();
    }

    @SuppressWarnings("unchecked")
    private static VertexBuffer stage(CurrentFrameTerrainOcclusion target,TestChunk chunk,
                                      float min,float max,float z) throws Exception {
        ByteBuffer bytes=TerrainOccluderMeshTest.buffer(1);
        TerrainOccluderMeshTest.put(bytes,SoftwareOcclusionRasterTest.quad(min,min,max,max,z));
        TerrainOccluderMesh mesh=TerrainOccluderMeshTest.capture(bytes,new TerrainOccluderMesh.Opacity(){
            @Override public boolean opaque(float u0,float v0,float u1,float v1){return true;}
        });
        return stage(target,chunk,mesh);
    }

    @SuppressWarnings("unchecked")
    private static VertexBuffer stage(CurrentFrameTerrainOcclusion target,TestChunk chunk,
                                      TerrainOccluderMesh mesh) throws Exception {
        BufferBuilder builder=new BufferBuilder(28);
        builder.setTranslation(-chunk.position.getX(),-chunk.position.getY(),-chunk.position.getZ());
        TerrainVertexSpace space=TerrainVertexSpace.capture(builder,chunk);
        assertNotNull(space);
        Class<?> entry=Class.forName(CurrentFrameTerrainOcclusion.class.getName()+"$Entry");
        Constructor<?> constructor=entry.getDeclaredConstructor(RenderChunk.class,
            CompiledChunk.class,TerrainOccluderMesh.class,TerrainVertexSpace.class);
        constructor.setAccessible(true);
        Object value=constructor.newInstance(chunk,chunk.compiled,mesh,space);
        Field pending=CurrentFrameTerrainOcclusion.class.getDeclaredField("pending");
        pending.setAccessible(true);
        VertexBuffer vbo=(VertexBuffer)unsafe().allocateInstance(VertexBuffer.class);
        ((Map<VertexBuffer,Object>)pending.get(target)).put(vbo,value);
        return vbo;
    }

    private static Unsafe unsafe() throws Exception {
        Field field=Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return (Unsafe)field.get(null);
    }
    private static TestChunk chunk() throws Exception {
        TestChunk chunk=(TestChunk)unsafe().allocateInstance(TestChunk.class);
        chunk.position=BlockPos.ORIGIN; chunk.compiled=new CompiledChunk(); return chunk;
    }
    private static final class TestChunk extends RenderChunk {
        private BlockPos position;
        private CompiledChunk compiled;
        private TestChunk(){super(null,null,0);}
        @Override public BlockPos getPosition(){return position;}
        @Override public CompiledChunk getCompiledChunk(){return compiled;}
    }
    private static final class Container implements TerrainRenderListAccessor {
        final ArrayList<RenderChunk> values;
        double viewX,viewZ;
        Container(RenderChunk...chunks){this(new ArrayList<RenderChunk>(),chunks);}
        Container(ArrayList<RenderChunk> values,RenderChunk...chunks){this.values=values;reset(chunks);}
        void reset(RenderChunk...chunks){values.clear();values.addAll(Arrays.asList(chunks));}
        @Override public List<RenderChunk> ice$renderChunks(){return values;}
        @Override public boolean ice$initialized(){return true;}
        @Override public double ice$viewEntityX(){return viewX;}
        @Override public double ice$viewEntityY(){return 0;}
        @Override public double ice$viewEntityZ(){return viewZ;}
    }
}
