package dev.rlcraft.ice.optimizer.compat.lycanites;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import dev.rlcraft.ice.optimizer.client.ClientOptimizerRuntime;
import dev.rlcraft.ice.optimizer.client.ModernRendererRuntime;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyMatrixStateTracker;
import dev.rlcraft.ice.optimizer.compat.renderlib.RenderLibRenderBridge;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.resource.RenderHandle;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;
import javax.vecmath.Vector2f;
import javax.vecmath.Vector4f;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.vertex.VertexFormat;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

/**
 * Generation-qualified shared VBO emitter for the two reviewed Lycanites OBJ
 * implementations.  Geometry is owned once by Mesh and reused by every
 * color/UV variant; surrounding Lycanites pre/post events stay untouched.
 */
public final class LycanitesObjRenderBridge {
    private static final String MODULE = "lycanites-obj-render";
    private static final String VBO_MODEL = "com.lycanitesmobs.client.obj.VBOModel";
    private static final int MAX_MODELS = 96;
    private static final int MAX_GROUPS = 2048;
    private static final int WARMUP_CALLS = 3;
    private static final long MAX_MESH_BYTES = 64L * 1024L * 1024L;
    private static final Object LOCK = new Object();
    private static final IdentityHashMap<Object, ModelEntry> MODELS =
        new IdentityHashMap<Object, ModelEntry>();

    private static volatile MeshAccess meshAccess;
    private static long knownGlGeneration = Long.MIN_VALUE;
    private static long knownResourceGeneration = Long.MIN_VALUE;
    private static int groupCount;
    private static volatile boolean activated;
    private static volatile boolean recoveryPending;

    private LycanitesObjRenderBridge() {
    }

    public static boolean tryRender(Object model, Object group, Vector4f color,
                                    Vector2f uvOffset, VertexFormat ignoredFormat) {
        if (!OptimizerBridge.isEnabled(MODULE) || model == null || group == null
            || color == null || uvOffset == null) return false;
        ModernRendererRuntime runtime = ClientOptimizerRuntime.INSTANCE.modernRenderer();
        if (runtime == null || !runtime.canEmitExternalModelMesh()) return false;
        RenderLibRenderBridge.markEligibleDraw();
        boolean issued = false;
        try {
            synchronized (LOCK) {
                refreshEpochs(runtime);
                MeshAccess access = access(group);
                MeshSignature signature = access.capture(group);
                if (signature == null || !signature.isTriangleMesh()) return false;
                long frame = OptimizerBridge.currentFrameId();
                ModelEntry modelEntry = MODELS.get(model);
                if (modelEntry == null) {
                    if (MODELS.size() >= MAX_MODELS
                        && !evictOldestModel(runtime)) return false;
                    modelEntry = new ModelEntry();
                    MODELS.put(model, modelEntry);
                }
                modelEntry.lastUsedFrame = frame;
                GroupEntry entry = modelEntry.groups.get(group);
                if (entry == null) {
                    if (groupCount >= MAX_GROUPS
                        && !evictOldestGroup(runtime)) return false;
                    entry = new GroupEntry(signature);
                    entry.lastUsedFrame = frame;
                    modelEntry.groups.put(group, entry);
                    groupCount++;
                    return false;
                }
                if (!entry.signature.sameAs(signature)) {
                    release(entry, true, false, runtime);
                    entry.reset(signature);
                    return false;
                }
                entry.lastUsedFrame = frame;
                if (entry.vbo <= 0) {
                    if (++entry.observations < WARMUP_CALLS
                        || !prepare(entry, group, access, runtime)) return false;
                }
                issued = emit(entry, color, uvOffset, isVboModel(model));
            }
            if (!issued) return false;
            runtime.recordExternalModelDraw();
            activate();
            recoverIfNeeded();
            return true;
        } catch (ThreadDeath fatal) {
            throw fatal;
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            boolean drawWasIssued = issued || error instanceof IssuedDrawFailure;
            recoveryPending = true;
            OptimizerBridge.failure(MODULE, unwrap(error));
            // Once glDrawArrays has been entered, the driver may already have
            // consumed the command even when LWJGL reports a failure.  Falling
            // through to the preserved implementation would then double draw
            // transparent geometry.  Pre-submit failures remain fail-open.
            return drawWasIssued;
        }
    }

    private static boolean prepare(GroupEntry entry, Object group,
                                   MeshAccess access,
                                   ModernRendererRuntime runtime) throws Exception {
        Object mesh = entry.signature.mesh;
        int existing = access.rawVbo(mesh);
        CacheBudget.Reservation reservation = null;
        RenderHandle handle = null;
        boolean created = false;
        boolean detached = false;
        boolean published = false;
        boolean deleteCompleted = false;
        boolean accepted = false;
        boolean creationUncertain = false;
        boolean creationCallReturned = false;
        Throwable failure = null;
        try {
            preparation: {
                if (existing > 0) {
                    MeshSignature borrowed = access.capture(group);
                    if (borrowed == null || borrowed.mesh != mesh
                        || !borrowed.isTriangleMesh() || borrowed.vbo != existing) {
                        break preparation;
                    }
                    entry.signature = borrowed;
                    entry.vbo = existing;
                    entry.access = access;
                    entry.ownedHandle = null;
                    entry.glGeneration = knownGlGeneration;
                    entry.resourceGeneration = knownResourceGeneration;
                    accepted = true;
                    break preparation;
                }
                if (existing == 0 && !access.clearVbo(mesh, 0)) {
                    throw new IllegalStateException(
                        "Lycanites invalid VBO sentinel could not be reset");
                }
                long bytes = Math.multiplyExact(
                    (long) entry.signature.indexCount, 24L);
                if (bytes <= 0L || bytes > MAX_MESH_BYTES) break preparation;
                long chargedBytes = Math.max(4096L, bytes);
                reservation = ClientOptimizerRuntime.INSTANCE.tryReserve(
                    BudgetKind.GPU, chargedBytes);
                if (reservation == null) break preparation;

                existing = access.getVbo(mesh);
                creationCallReturned = true;
                created = existing > 0;
                if (!created) break preparation;
                MeshSignature built = access.capture(group);
                if (!compatibleAfterBuild(entry.signature, built, existing)) {
                    break preparation;
                }

                // Mesh.getVbo() publishes its raw name into a public-lifecycle
                // object. Detach it before ledger adoption so Mesh.delete()
                // and the ICE ledger can never both own the same GL name.
                detached = access.clearVbo(mesh, existing);
                if (!detached) {
                    throw new IllegalStateException(
                        "Lycanites VBO detachment failed");
                }
                MeshSignature detachedSignature = access.capture(group);
                if (!compatibleAfterBuild(built, detachedSignature, -1)) {
                    throw new IllegalStateException(
                        "Lycanites mesh changed during VBO publication");
                }
                handle = runtime.adoptExternalModelBuffer(existing,
                    chargedBytes, knownResourceGeneration, knownGlGeneration,
                    reservation);
                if (handle == null) break preparation;

                entry.signature = detachedSignature;
                entry.vbo = existing;
                entry.access = access;
                entry.ownedHandle = handle;
                entry.glGeneration = knownGlGeneration;
                entry.resourceGeneration = knownResourceGeneration;
                reservation = null; // ResourceLedger owns the charge now.
                published = true;
                accepted = true;
            }
        } catch (Throwable error) {
            failure = error;
        } finally {
            if (!published && !created && reservation != null) {
                // If Mesh.getVbo escaped, its internal glGenBuffers call may
                // have allocated a name which was never returned or stored.
                creationUncertain = !creationCallReturned;
                try {
                    int partial = access.rawVbo(mesh);
                    if (partial > 0) {
                        existing = partial;
                        created = true;
                    } else if (partial == 0 && !access.clearVbo(mesh, 0)) {
                        throw new IllegalStateException(
                            "Lycanites failed VBO sentinel could not be reset");
                    }
                } catch (Throwable error) {
                    // getVbo() may have published a native name immediately
                    // before failing. If the field cannot be inspected, keep
                    // the reservation charged rather than assuming no object.
                    creationUncertain = true;
                    failure = appendFailure(failure, error);
                }
            }
            if (!published && created) {
                boolean safeToDelete = detached;
                if (!safeToDelete) {
                    try { safeToDelete = access.clearVbo(mesh, existing); }
                    catch (Throwable error) {
                        failure = appendFailure(failure, error);
                    }
                }
                if (safeToDelete) {
                    try {
                        GL15.glDeleteBuffers(existing);
                        deleteCompleted = true;
                    } catch (Throwable error) {
                        // Outcome is uncertain. Never retry this raw name and
                        // deliberately retain the pre-allocation reservation.
                        failure = appendFailure(failure, error);
                    }
                }
            }
            try { OpenGlHelper.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0); }
            catch (Throwable error) {
                EarlyGlStateTracker.invalidate();
                failure = appendFailure(failure, error);
            }
            if (reservation != null && !creationUncertain
                && (!created || deleteCompleted)) {
                try { reservation.close(); }
                catch (Throwable error) {
                    failure = appendFailure(failure, error);
                }
            }
        }
        if (failure != null) rethrow(failure);
        return accepted;
    }

    /** Returns false only before glDrawArrays is called. */
    private static boolean emit(GroupEntry entry, Vector4f color,
                                Vector2f uvOffset, boolean vboStyle) {
        EarlyGlStateTracker.Snapshot state = EarlyGlStateTracker.snapshot();
        int originalMode = EarlyMatrixStateTracker.currentMode();
        if (state == null || !state.hasDrawState() || !state.hasArrayBufferBinding()
            || state.getArrayBuffer() != 0 || state.getClientActiveTexture() != 0
            || state.getProgram() != 0 || originalMode < 0) return false;
        boolean offset = uvOffset.x != 0.0F || uvOffset.y != 0.0F;
        boolean textureModeSelected = false;
        boolean texturePushed = false;
        boolean issued = false;
        int arrays = 0;
        Throwable failure = null;
        try {
            GlStateManager.color(color.x, color.y, color.z, color.w);
            if (offset) {
                GlStateManager.matrixMode(GL11.GL_TEXTURE);
                textureModeSelected = true;
                GlStateManager.pushMatrix();
                texturePushed = true;
                GlStateManager.translate((double) uvOffset.x * 0.01D,
                    (double) -uvOffset.y * 0.01D, 0.0D);
                GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            }
            OpenGlHelper.glBindBuffer(GL15.GL_ARRAY_BUFFER, entry.vbo);
            arrays = prepareArrays();
            issued = true;
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, entry.signature.indexCount);
        } catch (Throwable error) {
            failure = error;
        } finally {
            if (arrays != 0) try { releaseArrays(arrays); }
            catch (Throwable error) {
                EarlyGlStateTracker.invalidate();
                failure = appendFailure(failure, error);
            }
            try { OpenGlHelper.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0); }
            catch (Throwable error) {
                EarlyGlStateTracker.invalidate();
                failure = appendFailure(failure, error);
            }
            if (textureModeSelected) {
                if (texturePushed) {
                    try {
                        GlStateManager.matrixMode(GL11.GL_TEXTURE);
                        GlStateManager.popMatrix();
                    } catch (Throwable error) {
                        EarlyMatrixStateTracker.invalidate();
                        failure = appendFailure(failure, error);
                    }
                }
                try {
                    GlStateManager.matrixMode(
                        vboStyle ? GL11.GL_MODELVIEW : originalMode);
                } catch (Throwable error) {
                    EarlyMatrixStateTracker.invalidate();
                    failure = appendFailure(failure, error);
                }
            }
            try {
                if (vboStyle) GlStateManager.resetColor();
                else GlStateManager.color(state.getRed(), state.getGreen(),
                    state.getBlue(), state.getAlpha());
            } catch (Throwable error) {
                EarlyGlStateTracker.invalidate();
                failure = appendFailure(failure, error);
            }
        }
        if (failure != null) {
            if (failure instanceof ThreadDeath) throw (ThreadDeath) failure;
            if (failure instanceof VirtualMachineError) {
                throw (VirtualMachineError) failure;
            }
            if (issued) throw new IssuedDrawFailure(failure);
            rethrow(failure);
        }
        return true;
    }

    private static int prepareArrays() {
        int enabled = 0;
        try {
            GL11.glVertexPointer(3, GL11.GL_FLOAT, 24, 0L);
            enabled |= 1;
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glTexCoordPointer(2, GL11.GL_FLOAT, 24, 12L);
            enabled |= 2;
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL11.glNormalPointer(GL11.GL_BYTE, 24, 20L);
            enabled |= 4;
            GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
            return enabled;
        } catch (Throwable error) {
            try { releaseArrays(enabled); }
            catch (Throwable cleanupFailure) {
                error = appendFailure(error, cleanupFailure);
            }
            rethrow(error);
            return 0;
        }
    }

    private static void releaseArrays(int enabled) {
        Throwable failure = null;
        if ((enabled & 1) != 0) try {
            GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
        } catch (Throwable error) { failure = appendFailure(failure, error); }
        if ((enabled & 2) != 0) try {
            GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        } catch (Throwable error) { failure = appendFailure(failure, error); }
        if ((enabled & 4) != 0) try {
            GL11.glDisableClientState(GL11.GL_NORMAL_ARRAY);
        } catch (Throwable error) { failure = appendFailure(failure, error); }
        try { OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        if (failure != null) rethrow(failure);
    }

    private static MeshAccess access(Object group) throws Exception {
        MeshAccess current = meshAccess;
        if (current != null && current.objectClass == group.getClass()) return current;
        current = new MeshAccess(group.getClass());
        meshAccess = current;
        return current;
    }

    private static void refreshEpochs(ModernRendererRuntime runtime) {
        long gl = OptimizerBridge.currentGlContextGeneration();
        long resources = OptimizerBridge.currentResourceGeneration();
        if (gl == knownGlGeneration && resources == knownResourceGeneration) return;
        boolean contextChanged = knownGlGeneration != Long.MIN_VALUE
            && gl != knownGlGeneration;
        boolean canRetire = knownGlGeneration != Long.MIN_VALUE
            && !contextChanged;
        releaseAll(canRetire, contextChanged, runtime);
        knownGlGeneration = gl;
        knownResourceGeneration = resources;
    }

    private static void releaseAll(boolean retireOwned,
                                   boolean invalidateBorrowed,
                                   ModernRendererRuntime runtime) {
        Throwable failure = null;
        for (ModelEntry model : MODELS.values()) {
            for (GroupEntry entry : model.groups.values()) {
                try { release(entry, retireOwned, invalidateBorrowed, runtime); }
                catch (Throwable error) {
                    failure = appendFailure(failure, error);
                }
            }
            model.groups.clear();
        }
        MODELS.clear();
        groupCount = 0;
        if (failure != null) rethrow(failure);
    }

    private static void release(GroupEntry entry, boolean retireOwned,
                                boolean invalidateBorrowed,
                                ModernRendererRuntime runtime) {
        Throwable failure = null;
        try {
            if (entry.vbo > 0 && entry.ownedHandle != null) {
                if (retireOwned && entry.glGeneration == knownGlGeneration
                    && entry.resourceGeneration == knownResourceGeneration
                    && (runtime == null
                        || !runtime.retireExternalModelBuffer(entry.ownedHandle))) {
                    throw new IllegalStateException(
                        "Lycanites VBO ledger retirement rejected");
                }
            } else if (entry.vbo > 0 && invalidateBorrowed
                && entry.access != null
                && !entry.access.clearVbo(entry.signature.mesh, entry.vbo)) {
                throw new IllegalStateException(
                    "Lycanites borrowed VBO invalidation lost ownership");
            }
        } catch (Throwable error) {
            failure = error;
        } finally {
            entry.vbo = 0;
            entry.ownedHandle = null;
            entry.access = null;
        }
        if (failure != null) rethrow(failure);
    }

    /**
     * Called immediately before the owning modern component graph destroys or
     * abandons its ledger. ICE-owned names were detached from Mesh at creation;
     * only borrowed names need field invalidation after a lost GL context.
     */
    public static void releaseRendererGraph(boolean contextValid) {
        synchronized (LOCK) {
            Throwable failure = null;
            for (ModelEntry model : MODELS.values()) {
                for (GroupEntry entry : model.groups.values()) {
                    if (!contextValid && entry.ownedHandle == null
                        && entry.vbo > 0 && entry.access != null) {
                        try {
                            if (!entry.access.clearVbo(entry.signature.mesh,
                                entry.vbo)) {
                                throw new IllegalStateException(
                                    "Lycanites lost-context VBO invalidation failed");
                            }
                        } catch (Throwable error) {
                            failure = appendFailure(failure, error);
                        }
                    }
                    entry.vbo = 0;
                    entry.ownedHandle = null;
                    entry.access = null;
                }
                model.groups.clear();
            }
            MODELS.clear();
            groupCount = 0;
            meshAccess = null;
            knownGlGeneration = Long.MIN_VALUE;
            knownResourceGeneration = Long.MIN_VALUE;
            if (failure != null) {
                recoveryPending = true;
                OptimizerBridge.failure(MODULE, failure);
            }
        }
    }

    /** Retires cached detached names when an in-place graph reset keeps the ledger. */
    public static void invalidateRendererResources(
        ModernRendererRuntime runtime) {
        synchronized (LOCK) {
            try {
                releaseAll(true, false, runtime);
            } finally {
                meshAccess = null;
                knownGlGeneration = Long.MIN_VALUE;
                knownResourceGeneration = Long.MIN_VALUE;
            }
        }
    }

    private static boolean evictOldestModel(ModernRendererRuntime runtime) {
        Object oldestKey = null;
        ModelEntry oldest = null;
        for (Map.Entry<Object, ModelEntry> candidate : MODELS.entrySet()) {
            if (oldest == null || candidate.getValue().lastUsedFrame
                < oldest.lastUsedFrame) {
                oldestKey = candidate.getKey();
                oldest = candidate.getValue();
            }
        }
        if (oldest == null) return false;
        MODELS.remove(oldestKey);
        groupCount -= oldest.groups.size();
        Throwable failure = null;
        for (GroupEntry entry : oldest.groups.values()) {
            try { release(entry, true, false, runtime); }
            catch (Throwable error) { failure = appendFailure(failure, error); }
        }
        oldest.groups.clear();
        if (groupCount < 0) groupCount = 0;
        if (failure != null) rethrow(failure);
        return true;
    }

    private static boolean evictOldestGroup(ModernRendererRuntime runtime) {
        Object oldestModelKey = null;
        ModelEntry oldestModel = null;
        Object oldestGroupKey = null;
        GroupEntry oldestGroup = null;
        for (Map.Entry<Object, ModelEntry> model : MODELS.entrySet()) {
            for (Map.Entry<Object, GroupEntry> group
                : model.getValue().groups.entrySet()) {
                if (oldestGroup == null || group.getValue().lastUsedFrame
                    < oldestGroup.lastUsedFrame) {
                    oldestModelKey = model.getKey();
                    oldestModel = model.getValue();
                    oldestGroupKey = group.getKey();
                    oldestGroup = group.getValue();
                }
            }
        }
        if (oldestGroup == null) return false;
        oldestModel.groups.remove(oldestGroupKey);
        groupCount--;
        if (oldestModel.groups.isEmpty()) MODELS.remove(oldestModelKey);
        release(oldestGroup, true, false, runtime);
        return true;
    }

    private static boolean isVboModel(Object model) {
        Class<?> type = model.getClass();
        while (type != null) {
            if (VBO_MODEL.equals(type.getName())) return true;
            type = type.getSuperclass();
        }
        return false;
    }

    private static void activate() {
        if (!activated) {
            activated = true;
            OptimizerBridge.activate(MODULE,
                "Lycanites OBJ 分组已复用 generation-qualified 共享 VBO 网格");
        }
    }

    private static void recoverIfNeeded() {
        if (recoveryPending) {
            recoveryPending = false;
            OptimizerBridge.success(MODULE);
        }
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof IssuedDrawFailure && error.getCause() != null) {
            error = error.getCause();
        }
        return error instanceof InvocationTargetException
            && ((InvocationTargetException) error).getCause() != null
                ? ((InvocationTargetException) error).getCause() : error;
    }

    /** Pure data gate shared with regression tests; it performs no GL work. */
    static boolean validMeshData(int[] indices, Object[] vertices, Object[] normals) {
        if (indices == null || vertices == null || indices.length <= 0
            || (indices.length % 3) != 0 || vertices.length <= 0
            || (normals != null && normals.length != indices.length)) return false;
        for (int i = 0; i < indices.length; i++) {
            int vertex = indices[i];
            if (vertex < 0 || vertex >= vertices.length || vertices[vertex] == null
                || (normals != null && normals[i] == null)) return false;
        }
        return true;
    }

    private static boolean compatibleAfterBuild(MeshSignature before,
                                                MeshSignature after,
                                                int expectedVbo) {
        return before != null && after != null && after.isTriangleMesh()
            && before.group == after.group && before.mesh == after.mesh
            && before.indices == after.indices && before.vertices == after.vertices
            && (before.normals == null || before.normals == after.normals)
            && before.indexCount == after.indexCount
            && before.vertexCount == after.vertexCount
            && (before.normals == null || before.normalCount == after.normalCount)
            && after.vbo == expectedVbo;
    }

    private static Throwable appendFailure(Throwable first, Throwable next) {
        if (first == null) return next;
        Throwable nextFatal = FatalErrors.findFatal(next);
        if (nextFatal != null && FatalErrors.findFatal(first) == null) {
            if (nextFatal != first) nextFatal.addSuppressed(first);
            return nextFatal;
        }
        if (next != null && first != next) first.addSuppressed(next);
        return first;
    }

    private static void rethrow(Throwable failure) {
        FatalErrors.rethrowIfFatal(failure);
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) throw (Error) failure;
        throw new IllegalStateException("Lycanites OBJ render bridge failed",
            failure);
    }

    private static final class IssuedDrawFailure extends RuntimeException {
        private IssuedDrawFailure(Throwable cause) { super(cause); }
    }

    private static final class ModelEntry {
        private final IdentityHashMap<Object, GroupEntry> groups =
            new IdentityHashMap<Object, GroupEntry>();
        private long lastUsedFrame;
    }

    private static final class GroupEntry {
        private MeshSignature signature;
        private int observations = 1;
        private int vbo;
        private MeshAccess access;
        private RenderHandle ownedHandle;
        private long glGeneration;
        private long resourceGeneration;
        private long lastUsedFrame;

        private GroupEntry(MeshSignature signature) { this.signature = signature; }

        private void reset(MeshSignature next) {
            signature = next;
            observations = 1;
            vbo = 0;
            access = null;
            ownedHandle = null;
            glGeneration = 0L;
            resourceGeneration = 0L;
        }
    }

    private static final class MeshSignature {
        private final Object group;
        private final Object mesh;
        private final Object indices;
        private final Object vertices;
        private final Object normals;
        private final int indexCount;
        private final int vertexCount;
        private final int normalCount;
        private final int vbo;

        private MeshSignature(Object group, Object mesh, Object indices,
                              Object vertices, Object normals, int indexCount,
                              int vertexCount, int normalCount, int vbo) {
            this.group = group;
            this.mesh = mesh;
            this.indices = indices;
            this.vertices = vertices;
            this.normals = normals;
            this.indexCount = indexCount;
            this.vertexCount = vertexCount;
            this.normalCount = normalCount;
            this.vbo = vbo;
        }

        private boolean isTriangleMesh() {
            return validMeshData((int[]) indices, (Object[]) vertices,
                normals == null ? null : (Object[]) normals);
        }

        private boolean sameAs(MeshSignature other) {
            return other != null && group == other.group && mesh == other.mesh
                && indices == other.indices && vertices == other.vertices
                && normals == other.normals && indexCount == other.indexCount
                && vertexCount == other.vertexCount
                && normalCount == other.normalCount && vbo == other.vbo;
        }
    }

    private static final class MeshAccess {
        private final Class<?> objectClass;
        private final Field mesh;
        private final Field indices;
        private final Field vertices;
        private final Field normals;
        private final Field vbo;
        private final Method getVbo;

        private MeshAccess(Class<?> objectClass) throws Exception {
            this.objectClass = objectClass;
            mesh = field(objectClass, "mesh");
            Class<?> meshClass = mesh.getType();
            indices = field(meshClass, "indices");
            vertices = field(meshClass, "vertices");
            normals = field(meshClass, "normals");
            vbo = field(meshClass, "vbo");
            getVbo = meshClass.getMethod("getVbo");
            getVbo.setAccessible(true);
        }

        private MeshSignature capture(Object group) throws Exception {
            Object meshValue = mesh.get(group);
            if (meshValue == null) return null;
            Object indexValue = indices.get(meshValue);
            Object vertexValue = vertices.get(meshValue);
            Object normalValue = normals.get(meshValue);
            if (!(indexValue instanceof int[]) || !(vertexValue instanceof Object[])
                || (normalValue != null && !(normalValue instanceof Object[]))) return null;
            return new MeshSignature(group, meshValue, indexValue, vertexValue,
                normalValue, ((int[]) indexValue).length,
                ((Object[]) vertexValue).length,
                normalValue == null ? 0 : ((Object[]) normalValue).length,
                vbo.getInt(meshValue));
        }

        private int rawVbo(Object meshValue) throws Exception {
            return meshValue == null ? -1 : vbo.getInt(meshValue);
        }

        private int getVbo(Object meshValue) throws Exception {
            if (meshValue == null) return -1;
            try {
                Object result = getVbo.invoke(meshValue);
                return result instanceof Integer
                    ? ((Integer) result).intValue() : -1;
            } catch (InvocationTargetException error) {
                Throwable cause = error.getCause();
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw error;
            }
        }

        private boolean clearVbo(Object meshValue, int expected)
            throws Exception {
            if (meshValue == null || vbo.getInt(meshValue) != expected) {
                return false;
            }
            vbo.setInt(meshValue, -1);
            return vbo.getInt(meshValue) == -1;
        }

        private static Field field(Class<?> owner, String name) throws Exception {
            Class<?> current = owner;
            while (current != null) {
                try {
                    Field value = current.getDeclaredField(name);
                    value.setAccessible(true);
                    return value;
                } catch (NoSuchFieldException ignored) {
                    current = current.getSuperclass();
                }
            }
            throw new NoSuchFieldException(owner.getName() + '.' + name);
        }
    }
}
