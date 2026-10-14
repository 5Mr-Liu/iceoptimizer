package dev.rlcraft.ice.optimizer.render.entity;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyMatrixStateTracker;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyModelLightingState;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.resource.LwjglRetirementFence;
import dev.rlcraft.ice.optimizer.render.resource.RenderHandle;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

/**
 * A certified model tree has immutable local vertices and changing poses.
 * Its merged VBO is built once; each submission uploads only a matrix palette.
 * A vertex-only compatibility shader leaves the original texture combiners,
 * alpha test, lightmap, hurt overlay and fragment fog in the fixed pipeline.
 */
final class LwjglModelPoseBatch {
    static final int PALETTE_SIZE = 128;
    static final int SOURCE_STRIDE = 24;
    static final int STRIDE = 28;
    private static final int PART_ATTRIBUTE = 7;
    private static final int MAX_MESHES = 96;
    private static final int MAX_SHAPES_PER_ROOT = 4;
    private static final int MIN_PARTS = 8;
    private static final int MAX_MESH_BYTES = 4 * 1024 * 1024;
    private final ResourceLedger resources;
    private final CacheBudget budget;
    private final ModelPoseUniforms scalarUniforms = new ModelPoseUniforms(new ModelPoseUniforms.Writer() {
        @Override public void set(int location, int value) { GL20.glUniform1i(location, value); }
    });
    private final LinkedHashMap<LwjglModelMeshCache.Entry, Mesh[]> meshes =
        new LinkedHashMap<LwjglModelMeshCache.Entry, Mesh[]>(32, 0.75F, true);
    private int meshCount;
    private final float[] poses = new float[PALETTE_SIZE * 16];
    private final float[] synchronizedPose = new float[16];
    private int baseMatrixDepth;
    private int materializedMatrixDepth;
    private FloatBuffer upload;
    private CacheBudget.Reservation uploadReservation;
    private RenderHandle program;
    private boolean attempted;
    private boolean unavailable;
    private Mesh active;
    private int next;
    private int pending;
    private int start;
    private int poseLocation;
    private int baseLocation;
    private int lightingLocation;
    private int normalizeLocation;
    private int rescaleLocation;
    private int twoSideLocation;
    private int localViewerLocation;
    private int separateLocation;
    private int radialFogLocation;
    private int colorMaterialLocation, colorFaceLocation, colorModeLocation;
    private int colorMaterial, colorFace, colorMode;
    private final int[] lightLocations = new int[8];
    private int paletteCapacity = 32;
    private boolean session;
    private int sessionBuffer;
    private int sessionVertexArray;
    private boolean sessionUsesVertexArray;
    private boolean stateKnown;
    private final int[] mirrored = new int[EarlyModelLightingState.SIZE];
    private final int[] seedTexgen = new int[8];
    private boolean stateSupported;
    private int savedProgram;
    private boolean vertexTwoSide;
    private int lighting, normalize, rescale, twoSide, localViewer, separate, radialFog;
    private final int[] lights = new int[8];
    long draws;
    long parts;
    long fallbacks;
    String detail = "not-used";

    LwjglModelPoseBatch(ResourceLedger resources, CacheBudget budget) {
        this.resources = resources;
        this.budget = budget;
    }

    boolean begin(LwjglModelMeshCache.Entry[] entries, int count) {
        active = null;
        next = pending = 0;
        stateKnown = false;
        if (unavailable || budget == null) return false;
        if (count < MIN_PARTS) { detail = "small-tree"; return false; }
        boolean matrixScope = false;
        try {
            if (!initialize(entries[0].resourceHandle())) return false;
            // A previous HUD/legacy callback deliberately invalidates the full
            // mirror, and mods may use raw GL matrix calls before this tree.
            // Read only the native model-view top actually consumed by poses;
            // projection/texture and the caller's older stack levels stay native.
            upload.clear(); upload.limit(16);
            GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, upload);
            matrixScope = true;
            if (!EarlyMatrixStateTracker.beginModelViewScope(
                GL11.glGetInteger(GL11.GL_MATRIX_MODE), upload)) {
                detail = "matrix-unavailable";
                return false;
            }
            if (!EarlyModelLightingState.isKnown()) seedStateFromDriver();
            captureState();
            if (!stateSupported) return false;
            Mesh[] shapes = meshes.get(entries[0]);
            Mesh mesh = null;
            if (shapes != null) for (int index = 0; index < shapes.length; index++) {
                Mesh candidate = shapes[index];
                if (candidate != null && candidate.matches(entries, count)) {
                    mesh = candidate;
                    // Keep the usual visibility shape first without allocating a lookup key.
                    System.arraycopy(shapes, 0, shapes, 1, index);
                    shapes[0] = mesh;
                    break;
                }
            }
            if (mesh == null) {
                mesh = build(entries, count);
                if (mesh == null) { detail = "mesh-budget"; return false; }
                if (shapes == null) shapes = new Mesh[MAX_SHAPES_PER_ROOT];
                Mesh oldestShape = shapes[shapes.length - 1];
                if (oldestShape != null) { retire(oldestShape); meshCount--; }
                System.arraycopy(shapes, 0, shapes, 1, shapes.length - 1);
                shapes[0] = mesh;
                meshes.put(entries[0], shapes);
                meshCount++;
                if (meshCount > MAX_MESHES) {
                    Iterator<Map.Entry<LwjglModelMeshCache.Entry, Mesh[]>> iterator =
                        meshes.entrySet().iterator();
                    Mesh[] oldest = iterator.next().getValue();
                    for (Mesh expired : oldest) if (expired != null) {
                        retire(expired);
                        meshCount--;
                    }
                    iterator.remove();
                }
            }
            active = mesh;
            baseMatrixDepth = EarlyMatrixStateTracker.modelViewDepth();
            materializedMatrixDepth = 0;
            detail = "ready";
            return true;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            unavailable = true;
            detail = "init:" + failure.getClass().getSimpleName();
            fallbacks++;
            return false;
        } finally {
            if (matrixScope && active == null) EarlyMatrixStateTracker.endModelViewScope();
        }
    }

    boolean active() { return active != null; }

    /** ModelStateBatchBridge already checked this draw against the preflight order. */
    boolean enqueue(LwjglModelMeshCache cache) {
        if (pending == paletteCapacity) flush(cache);
        if (!EarlyMatrixStateTracker.copyModelView(poses, pending * 16)) return false;
        if (pending == 0) start = next;
        next++;
        pending++;
        return true;
    }

    void flush(LwjglModelMeshCache cache) {
        if (pending == 0) return;
        int count = pending;
        // Consume before issuing anything: failure cleanup must never replay a draw.
        pending = 0;
        boolean issued = false;

        Throwable failure = null;

        if (!stateKnown) captureState();
        boolean supported = stateSupported;
        if (supported) {
            try {
                if (!session) {
                    sessionBuffer = EarlyGlStateTracker.arrayBufferBinding();
                    sessionUsesVertexArray = active.vertexArray != null;
                    if (sessionUsesVertexArray) {
                        sessionVertexArray = LwjglModelMeshCache.currentVertexArray();
                    } else {
                        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
                    }
                    session = true;
                    if (sessionUsesVertexArray) GL30.glBindVertexArray(active.vertexArray.getNativeId());
                    GL20.glUseProgram(program.getNativeId());
                    if (twoSide != 0 && !vertexTwoSide) GL11.glEnable(0x8643);
                    if (!sessionUsesVertexArray || !active.arraysConfigured) {
                        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, active.handle.getNativeId());
                        GL11.glVertexPointer(3, GL11.GL_FLOAT, STRIDE, 0L);
                        GL11.glTexCoordPointer(2, GL11.GL_FLOAT, STRIDE, 12L);
                        GL11.glNormalPointer(GL11.GL_BYTE, STRIDE, 20L);
                        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
                        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
                        GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
                        GL20.glVertexAttribPointer(PART_ATTRIBUTE, 1, GL11.GL_FLOAT, false, STRIDE, 24L);
                        GL20.glEnableVertexAttribArray(PART_ATTRIBUTE);
                        active.arraysConfigured = true;
                    }
                    scalarUniforms.set(0, lightingLocation, lighting);
                    scalarUniforms.set(1, normalizeLocation, normalize);
                    scalarUniforms.set(2, rescaleLocation, rescale);
                    scalarUniforms.set(3, twoSideLocation, twoSide);
                    scalarUniforms.set(4, localViewerLocation, localViewer);
                    scalarUniforms.set(5, separateLocation, separate);
                    scalarUniforms.set(6, radialFogLocation, radialFog);
                    scalarUniforms.set(7, colorMaterialLocation, colorMaterial);
                    scalarUniforms.set(8, colorFaceLocation, colorFace);
                    scalarUniforms.set(9, colorModeLocation, colorMode);
                    for (int i = 0; i < 8; i++) scalarUniforms.set(10 + i, lightLocations[i], lights[i]);
                }
                upload.clear();
                upload.put(poses, 0, count * 16).flip();
                GL20.glUniformMatrix4(poseLocation, false, upload);
                scalarUniforms.set(18, baseLocation, start);
                issued = true;
                GL11.glDrawArrays(GL11.GL_QUADS, active.first[start],
                    active.first[start + count] - active.first[start]);
                draws++;
                parts += count;
                detail = "active";
            } catch (Throwable error) {
                failure = error;
                unavailable = true;
                detail = "draw:" + error.getClass().getSimpleName();
            } finally {
                if (failure != null) {
                    try { release(); }
                    catch (Throwable restoreFailure) {
                        restoreFailure.addSuppressed(failure);
                        throwUnchecked(restoreFailure);
                    }
                }
            }
        }
        if (failure != null) {
            FatalErrors.rethrowIfFatal(failure);
            if (issued) throwUnchecked(failure);
        }
        if (!issued) {
            fallbacks += count;
            detail = supported ? "draw-fallback" : "state-fallback";
            // Model-view may already have been popped by the tree walker.
            // Replay only the unsubmitted segment with its captured poses.
            int mode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            try {
                for (int i = 0; i < count; i++) {
                    upload.clear();
                    upload.put(poses, i * 16, 16).flip();
                    GL11.glLoadMatrix(upload);
                    cache.drawStateBatchImmediate(active.entries[start + i]);
                }
            } finally {
                GL11.glPopMatrix();
                GL11.glMatrixMode(mode);
            }
        }
    }

    /** End a material interval before its caller changes any native state. */
    void release() {
        if (!session) return;
        session = false;
        Throwable failure = null;
        boolean popped = false;
        try { GL20.glUseProgram(savedProgram); }
        catch (Throwable error) { failure = error; }
        try { if (twoSide != 0 && !vertexTwoSide) GL11.glDisable(0x8643); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        try {
            if (sessionUsesVertexArray) {
                GL30.glBindVertexArray(sessionVertexArray);
                EarlyGlStateTracker.bindVertexArray(sessionVertexArray);
            } else { GL11.glPopClientAttrib(); popped = true; }
        }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        try { LwjglModelMeshCache.restoreArrayBufferBinding(sessionBuffer, popped); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        if (failure != null) {
            EarlyGlStateTracker.invalidate();
            throwUnchecked(failure);
        }
    }
    void end() {
        active = null; next = pending = 0; materializedMatrixDepth = 0;
        EarlyMatrixStateTracker.endModelViewScope();
    }

    void invalidateState() { stateKnown = false; }

    /** Rebuild only levels owned by this scope; the caller's older native levels are untouched. */
    void synchronizeMatrix() {
        int depth = EarlyMatrixStateTracker.modelViewDepth();
        if (upload == null || depth < baseMatrixDepth) return;
        int mode = EarlyMatrixStateTracker.currentMode();
        if (mode != GL11.GL_MODELVIEW) GL11.glMatrixMode(GL11.GL_MODELVIEW);
        try {
            while (materializedMatrixDepth > 0) {
                GL11.glPopMatrix();
                materializedMatrixDepth--;
            }
            for (int level = baseMatrixDepth; level <= depth; level++) {
                if (level > baseMatrixDepth) {
                    GL11.glPushMatrix();
                    materializedMatrixDepth++;
                }
                if (!EarlyMatrixStateTracker.copyModelViewLevel(level, synchronizedPose))
                    throw new IllegalStateException("model pose stack invalidated during materialization");
                upload.clear(); upload.put(synchronizedPose).flip();
                GL11.glLoadMatrix(upload);
            }
        } finally { if (mode != GL11.GL_MODELVIEW) GL11.glMatrixMode(mode); }
    }

    /** Software-only snapshot, shared by all palette segments in the material interval. */
    private void captureState() {
        savedProgram = EarlyGlStateTracker.modelProgramBinding();
        stateSupported = !unavailable && savedProgram == 0 && EarlyModelLightingState.copyTo(mirrored);
        detail = "untracked-state";
        if (stateSupported) {
            lighting = mirrored[0]; normalize = mirrored[1]; rescale = mirrored[2];
            twoSide = mirrored[3]; localViewer = mirrored[4]; separate = mirrored[5];
            colorMaterial = mirrored[6]; colorFace = mirrored[7]; colorMode = mirrored[8];
            vertexTwoSide = mirrored[9] != 0;
            stateSupported = mirrored[10] != GL14.GL_FOG_COORDINATE && mirrored[11] == 0;
            if (!stateSupported) detail = "unsupported-vertex-state";
            for (int i = 0; i < 8; i++) lights[i] = mirrored[12 + i];
            int fogMode = EarlyGlStateTracker.fogDistanceMode();
            radialFog = fogMode == 0x855B ? 1 : fogMode == 0x2502 ? 2 : 0;
        }
        stateKnown = true;
    }

    /** Called only by a real palette consumer when wrapper lighting is unknown. */
    void seedStateFromDriver() {
        mirrored[0] = GL11.glIsEnabled(GL11.GL_LIGHTING) ? 1 : 0;
        mirrored[1] = GL11.glIsEnabled(GL11.GL_NORMALIZE) ? 1 : 0;
        mirrored[2] = GL11.glIsEnabled(GL12.GL_RESCALE_NORMAL) ? 1 : 0;
        mirrored[3] = GL11.glGetInteger(GL11.GL_LIGHT_MODEL_TWO_SIDE);
        mirrored[4] = GL11.glGetInteger(GL11.GL_LIGHT_MODEL_LOCAL_VIEWER);
        mirrored[5] = GL11.glGetInteger(GL12.GL_LIGHT_MODEL_COLOR_CONTROL) == GL12.GL_SEPARATE_SPECULAR_COLOR ? 1 : 0;
        mirrored[6] = GL11.glIsEnabled(GL11.GL_COLOR_MATERIAL) ? 1 : 0;
        mirrored[7] = GL11.glGetInteger(GL11.GL_COLOR_MATERIAL_FACE);
        mirrored[8] = GL11.glGetInteger(GL11.GL_COLOR_MATERIAL_PARAMETER);
        mirrored[9] = GL11.glIsEnabled(0x8643) ? 1 : 0;
        mirrored[10] = GL11.glGetInteger(GL14.GL_FOG_COORDINATE_SOURCE);
        mirrored[11] = 0;
        for (int i = 0; i < 8; i++) mirrored[12 + i] = GL11.glIsEnabled(GL11.GL_LIGHT0 + i) ? 1 : 0;
        int units = Math.min(8, GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS));
        int previous = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        Arrays.fill(seedTexgen, 0);
        try {
            for (int i = 0; i < units; i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                for (int coordinate = 0; coordinate < 4; coordinate++)
                    if (GL11.glIsEnabled(GL11.GL_TEXTURE_GEN_S + coordinate)) seedTexgen[i] |= 1 << coordinate;
                mirrored[11] |= seedTexgen[i];
            }
        } finally { GL13.glActiveTexture(previous); }
        EarlyModelLightingState.seed(mirrored, seedTexgen, previous - GL13.GL_TEXTURE0);
        stateKnown = false;
    }
    private boolean initialize(RenderHandle generation) {
        if (attempted) return program != null;
        attempted = true;
        int components = GLContext.getCapabilities().OpenGL20
            ? GL11.glGetInteger(GL20.GL_MAX_VERTEX_UNIFORM_COMPONENTS) : 0;
        if (components < 768
            || GL11.glGetInteger(GL20.GL_MAX_VERTEX_ATTRIBS) <= PART_ATTRIBUTE) {
            detail = "GL2-palette-unavailable";
            unavailable = true;
            return false;
        }
        paletteCapacity = components >= 2560 ? 128 : components >= 1536 ? 64 : 32;
        uploadReservation = budget.tryReserve(BudgetKind.DIRECT, PALETTE_SIZE * 16 * 4);
        if (uploadReservation == null) {
            attempted = false;
            detail = "palette-budget";
            return false;
        }
        CacheBudget.Reservation shaderToken = resources.reserveNativeObject(RenderResourceKind.SHADER);
        CacheBudget.Reservation programToken = resources.reserveNativeObject(RenderResourceKind.PROGRAM);
        if (shaderToken == null || programToken == null) {
            if (shaderToken != null) shaderToken.close();
            if (programToken != null) programToken.close();
            uploadReservation.close();
            uploadReservation = null;
            attempted = false;
            detail = "program-budget";
            return false;
        }
        int shader = 0;
        int nativeProgram = 0;
        try {
            upload = BufferUtils.createFloatBuffer(PALETTE_SIZE * 16);
            shader = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
            GL20.glShaderSource(shader, ModelPoseShader.source(paletteCapacity));
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE)
                throw new IllegalStateException(GL20.glGetShaderInfoLog(shader, 8192));
            nativeProgram = GL20.glCreateProgram();
            GL20.glAttachShader(nativeProgram, shader);
            GL20.glBindAttribLocation(nativeProgram, PART_ATTRIBUTE, "icePart");
            GL20.glLinkProgram(nativeProgram);
            if (GL20.glGetProgrami(nativeProgram, GL20.GL_LINK_STATUS) == GL11.GL_FALSE)
                throw new IllegalStateException(GL20.glGetProgramInfoLog(nativeProgram, 8192));
            GL20.glDetachShader(nativeProgram, shader);
            poseLocation = GL20.glGetUniformLocation(nativeProgram, "icePose[0]");
            baseLocation = GL20.glGetUniformLocation(nativeProgram, "iceBase");
            lightingLocation = GL20.glGetUniformLocation(nativeProgram, "iceLighting");
            normalizeLocation = GL20.glGetUniformLocation(nativeProgram, "iceNormalize");
            rescaleLocation = GL20.glGetUniformLocation(nativeProgram, "iceRescale");
            twoSideLocation = GL20.glGetUniformLocation(nativeProgram, "iceTwoSide");
            localViewerLocation = GL20.glGetUniformLocation(nativeProgram, "iceLocalViewer");
            separateLocation = GL20.glGetUniformLocation(nativeProgram, "iceSeparate");
            radialFogLocation = GL20.glGetUniformLocation(nativeProgram, "iceRadialFog");
            colorMaterialLocation = GL20.glGetUniformLocation(nativeProgram, "iceColorMaterial");
            colorFaceLocation = GL20.glGetUniformLocation(nativeProgram, "iceColorFace");
            colorModeLocation = GL20.glGetUniformLocation(nativeProgram, "iceColorMode");
            for (int i = 0; i < 8; i++) lightLocations[i] =
                GL20.glGetUniformLocation(nativeProgram, "iceLights[" + i + "]");
            program = resources.registerReservedObject(RenderResourceKind.PROGRAM,
                nativeProgram, generation.getResourceGeneration(), generation.getContextGeneration(), programToken);
            if (program == null) throw new IllegalStateException("program registration");
            programToken = null;
            nativeProgram = 0;
            return true;
        } finally {
            if (shader != 0) GL20.glDeleteShader(shader);
            shaderToken.close();
            if (nativeProgram != 0) GL20.glDeleteProgram(nativeProgram);
            if (programToken != null) programToken.close();
        }
    }

    private RenderHandle createVertexArray(RenderHandle generation) {
        CacheBudget.Reservation token = resources.reserveNativeObject(RenderResourceKind.VERTEX_ARRAY);
        if (token == null) return null;
        int id = 0;
        try {
            id = GL30.glGenVertexArrays();
            if (id <= 0) throw new IllegalStateException("pose vertex array allocation failed");
            RenderHandle vertexArray = resources.registerReservedObject(RenderResourceKind.VERTEX_ARRAY, id,
                generation.getResourceGeneration(), generation.getContextGeneration(), token);
            if (vertexArray == null) throw new IllegalStateException("pose vertex array registration failed");
            id = 0;
            token = null;
            return vertexArray;
        } finally {
            if (id > 0) GL30.glDeleteVertexArrays(id);
            if (token != null) token.close();
        }
    }

    private Mesh build(LwjglModelMeshCache.Entry[] entries, int count) {
        long vertices = 0;
        for (int i = 0; i < count; i++) vertices += entries[i].vertexCount();
        long bytes = vertices * STRIDE;
        if (bytes <= 0 || bytes > MAX_MESH_BYTES) return null;
        CacheBudget.Reservation gpu = resources.reserveGpu(bytes);
        CacheBudget.Reservation direct = budget.tryReserve(BudgetKind.DIRECT, bytes);
        if (gpu == null || direct == null) {
            if (gpu != null) gpu.close();
            if (direct != null) direct.close();
            return null;
        }
        int previous = EarlyGlStateTracker.arrayBufferBinding();
        int nativeBuffer = 0;
        RenderHandle handle = null;
        RenderHandle vertexArray = null;
        try {
            RenderHandle gen = entries[0].resourceHandle();
            // Reserve the small immutable layout before reading and merging
            // source buffers. Budget pressure must not rebuild a doomed mesh.
            if (GLContext.getCapabilities().OpenGL30) {
                vertexArray = createVertexArray(gen);
                if (vertexArray == null) return null;
            }
            ByteBuffer data = BufferUtils.createByteBuffer((int) bytes);
            int[] first = new int[count + 1];
            // The cache owns the original immutable capture bytes. Copy them
            // directly into their final region, avoiding a GPU-to-CPU round trip.
            for (int i = 0; i < count; i++) {
                LwjglModelMeshCache.Entry entry = entries[i];
                first[i + 1] = first[i] + entry.vertexCount();
                int offset = first[i] * STRIDE;
                data.position(offset).limit(offset + entry.vertexCount() * SOURCE_STRIDE);
                entry.copyVerticesTo(data);
                data.limit(data.capacity());
                expandVertices(data, offset, entry.vertexCount(), i);
            }
            data.clear();
            nativeBuffer = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, nativeBuffer);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
            handle = resources.registerReserved(RenderResourceKind.BUFFER,
                nativeBuffer, bytes, gen.getResourceGeneration(), gen.getContextGeneration(), gpu);
            if (handle == null) throw new IllegalStateException("mesh registration");
            nativeBuffer = 0;
            gpu = null;
            Mesh mesh = new Mesh(Arrays.copyOf(entries, count), first, handle, vertexArray);
            handle = vertexArray = null;
            return mesh;
        } finally {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previous);
            if (vertexArray != null) retire(vertexArray);
            if (handle != null) retire(handle);
            if (nativeBuffer != 0) GL15.glDeleteBuffers(nativeBuffer);
            if (gpu != null) gpu.close();
            direct.close();
        }
    }

    /** Overlap-safe widening keeps original packed UV/normal bytes exact. */
    static void expandVertices(ByteBuffer data, int offset, int count, int part) {
        for (int vertex = count - 1; vertex >= 0; vertex--) {
            int source = offset + vertex * SOURCE_STRIDE;
            int target = offset + vertex * STRIDE;
            for (int byteIndex = SOURCE_STRIDE - 1; byteIndex >= 0; byteIndex--)
                data.put(target + byteIndex, data.get(source + byteIndex));
            data.putFloat(target + SOURCE_STRIDE, part);
        }
    }

    void reset(boolean contextValid) {
        if (contextValid) release();
        else session = false;
        end();
        if (contextValid) {
            for (Mesh[] shapes : meshes.values())
                for (Mesh mesh : shapes) if (mesh != null) retire(mesh);
            if (program != null) retire(program);
        }
        meshes.clear();
        meshCount = 0;
        upload = null;
        if (uploadReservation != null) uploadReservation.close();
        uploadReservation = null;
        program = null;
        scalarUniforms.reset();
        attempted = unavailable = false;
        EarlyModelLightingState.invalidate();
        detail = "not-used";
    }

    private void retire(RenderHandle handle) {
        resources.retire(handle, LwjglRetirementFence.afterCurrentCommands(resources));
    }

    private void retire(Mesh mesh) {
        if (mesh.vertexArray != null) retire(mesh.vertexArray);
        retire(mesh.handle);
    }

    private static void throwUnchecked(Throwable failure) {
        FatalErrors.rethrowIfFatal(failure);
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
        throw new IllegalStateException("model pose batch", failure);
    }

    private static Throwable appendFailure(Throwable first, Throwable next) {
        if (first == null) return next;
        Throwable fatal = FatalErrors.findFatal(next);
        if (fatal != null && FatalErrors.findFatal(first) == null) {
            if (fatal != first) fatal.addSuppressed(first);
            return fatal;
        }
        if (first != next) first.addSuppressed(next);
        return first;
    }

    private static final class Mesh {
        final LwjglModelMeshCache.Entry[] entries;
        final int[] first;
        final RenderHandle handle;
        final RenderHandle vertexArray;
        boolean arraysConfigured;
        Mesh(LwjglModelMeshCache.Entry[] entries, int[] first, RenderHandle handle,
             RenderHandle vertexArray) {
            this.entries = entries; this.first = first; this.handle = handle;
            this.vertexArray = vertexArray;
        }
        boolean matches(LwjglModelMeshCache.Entry[] expected, int count) {
            if (entries.length != count) return false;
            for (int i = 0; i < count; i++)
                if (entries[i] != expected[i]) return false;
            return true;
        }
    }
}






