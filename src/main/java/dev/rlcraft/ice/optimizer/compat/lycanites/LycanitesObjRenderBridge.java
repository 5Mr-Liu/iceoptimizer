package dev.rlcraft.ice.optimizer.compat.lycanites;

import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import dev.rlcraft.ice.optimizer.client.ClientOptimizerRuntime;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.vecmath.Vector2f;
import javax.vecmath.Vector4f;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.vertex.VertexFormat;
import org.lwjgl.opengl.GL11;

/**
 * Exact, bounded display-list cache around Lycanites' reviewed OBJ and VBO
 * group implementations. Forge's surrounding pre/post model events remain in
 * their original call sites; only the stable inner geometry submission is
 * cached.
 */
public final class LycanitesObjRenderBridge {
    private static final String MODULE = "lycanites-obj-render";
    private static final String ORIGINAL_METHOD = "rlcraftIce$renderGroupImplOriginal";
    private static final int MAX_MODELS = 96;
    private static final int MAX_GROUPS = 2048;
    private static final int MAX_VARIANTS_PER_GROUP = 8;
    private static final int MAX_DISPLAY_LISTS = 1024;
    private static final int WARMUP_CALLS = 3;
    private static final long BYTES_PER_INDEX_ESTIMATE = 48L;
    private static final Object LOCK = new Object();
    private static final IdentityHashMap<Object, ModelEntry> MODELS =
        new IdentityHashMap<Object, ModelEntry>();
    private static final Map<Class<?>, Method> ORIGINALS =
        new ConcurrentHashMap<Class<?>, Method>();

    private static volatile MeshAccess meshAccess;
    private static long knownGlGeneration = Long.MIN_VALUE;
    private static long knownResourceGeneration = Long.MIN_VALUE;
    private static int groupCount;
    private static int displayListCount;
    private static volatile boolean activated;
    private static volatile boolean recoveryPending;

    private LycanitesObjRenderBridge() {
    }

    public static boolean tryRender(Object model, Object group, Vector4f color,
                                    Vector2f uvOffset, VertexFormat format) {
        if (!OptimizerBridge.isEnabled(MODULE) || model == null || group == null
            || color == null || uvOffset == null) return false;
        boolean renderStarted = false;
        synchronized (LOCK) {
            VariantEntry variant = null;
            try {
                refreshEpochs();
                MeshSignature signature = access(group).capture(group);
                if (signature == null || signature.indexCount <= 0) return false;
                ModelEntry modelEntry = MODELS.get(model);
                if (modelEntry == null) {
                    if (MODELS.size() >= MAX_MODELS) return false;
                    modelEntry = new ModelEntry();
                    MODELS.put(model, modelEntry);
                }
                GroupEntry groupEntry = modelEntry.groups.get(group);
                if (groupEntry == null) {
                    if (groupCount >= MAX_GROUPS) return false;
                    groupEntry = new GroupEntry(signature);
                    modelEntry.groups.put(group, groupEntry);
                    groupCount++;
                } else if (!groupEntry.signature.sameAs(signature)) {
                    releaseVariants(groupEntry, true);
                    groupEntry.signature = signature;
                }

                variant = groupEntry.find(color, uvOffset, format);
                if (variant == null) {
                    if (groupEntry.variantCount >= MAX_VARIANTS_PER_GROUP) return false;
                    groupEntry.add(new VariantEntry(color, uvOffset, format));
                    return false;
                }
                variant.lastUsedFrame = OptimizerBridge.currentFrameId();
                if (variant.displayList > 0) {
                    renderStarted = true;
                    GlStateManager.callList(variant.displayList);
                    recoverIfNeeded();
                    return true;
                }
                if (++variant.observations < WARMUP_CALLS || displayListCount >= MAX_DISPLAY_LISTS) {
                    return false;
                }

                long estimatedBytes = Math.max(4096L,
                    Math.min(16L * 1024L * 1024L, signature.indexCount * BYTES_PER_INDEX_ESTIMATE));
                CacheBudget.Reservation reservation =
                    ClientOptimizerRuntime.INSTANCE.tryReserve(BudgetKind.GPU, estimatedBytes);
                if (reservation == null) return false;
                int list = 0;
                boolean compiling = false;
                try {
                    list = GLAllocation.generateDisplayLists(1);
                    if (list <= 0) {
                        reservation.close();
                        return false;
                    }
                    GlStateManager.glNewList(list, GL11.GL_COMPILE);
                    compiling = true;
                    renderStarted = true;
                    invokeOriginal(model, group, color, uvOffset, format);
                    GlStateManager.glEndList();
                    compiling = false;
                    variant.displayList = list;
                    variant.reservation = reservation;
                    variant.glGeneration = knownGlGeneration;
                    variant.resourceGeneration = knownResourceGeneration;
                    displayListCount++;
                    GlStateManager.callList(list);
                    activate();
                    recoverIfNeeded();
                    return true;
                } catch (Throwable error) {
                    if (compiling) {
                        try { GlStateManager.glEndList(); } catch (Throwable ignored) { }
                    }
                    if (list > 0) {
                        try { GLAllocation.deleteDisplayLists(list); } catch (Throwable ignored) { }
                    }
                    reservation.close();
                    variant.observations = 0;
                    throw unwrap(error);
                }
            } catch (Throwable error) {
                recoveryPending = true;
                OptimizerBridge.failure(MODULE, error);
                if (variant != null && variant.displayList > 0) releaseVariant(variant, true);
                return renderStarted;
            }
        }
    }

    private static void invokeOriginal(Object model, Object group, Vector4f color,
                                       Vector2f uvOffset, VertexFormat format) throws Throwable {
        Method method = ORIGINALS.get(model.getClass());
        if (method == null) {
            for (Method candidate : model.getClass().getMethods()) {
                if (ORIGINAL_METHOD.equals(candidate.getName())
                    && candidate.getParameterTypes().length == 4) {
                    candidate.setAccessible(true);
                    method = candidate;
                    ORIGINALS.put(model.getClass(), candidate);
                    break;
                }
            }
        }
        if (method == null) throw new NoSuchMethodException(model.getClass().getName() + '.' + ORIGINAL_METHOD);
        try {
            method.invoke(model, group, color, uvOffset, format);
        } catch (InvocationTargetException error) {
            throw error.getCause() == null ? error : error.getCause();
        }
    }

    private static MeshAccess access(Object group) throws Exception {
        MeshAccess current = meshAccess;
        if (current != null && current.objectClass == group.getClass()) return current;
        current = new MeshAccess(group.getClass());
        meshAccess = current;
        return current;
    }

    private static void refreshEpochs() {
        long gl = OptimizerBridge.currentGlContextGeneration();
        long resources = OptimizerBridge.currentResourceGeneration();
        if (gl == knownGlGeneration && resources == knownResourceGeneration) return;
        boolean canDelete = knownGlGeneration != Long.MIN_VALUE && knownGlGeneration == gl;
        releaseAll(canDelete);
        knownGlGeneration = gl;
        knownResourceGeneration = resources;
    }

    private static void releaseAll(boolean deleteLists) {
        for (ModelEntry model : MODELS.values()) {
            for (GroupEntry group : model.groups.values()) releaseVariants(group, deleteLists);
            model.groups.clear();
        }
        MODELS.clear();
        groupCount = 0;
        displayListCount = 0;
    }

    private static void releaseVariants(GroupEntry group, boolean deleteLists) {
        for (int i = 0; i < group.variantCount; i++) releaseVariant(group.variants[i], deleteLists);
        group.variantCount = 0;
    }

    private static void releaseVariant(VariantEntry variant, boolean deleteList) {
        if (variant.displayList > 0) {
            if (deleteList) {
                try { GLAllocation.deleteDisplayLists(variant.displayList); } catch (Throwable ignored) { }
            }
            variant.displayList = 0;
            if (displayListCount > 0) displayListCount--;
        }
        if (variant.reservation != null) {
            variant.reservation.close();
            variant.reservation = null;
        }
    }

    private static void activate() {
        if (!activated) {
            activated = true;
            OptimizerBridge.activate(MODULE, "Lycanites 稳定 OBJ/VBO 分组已进入有预算的 GPU 批处理缓存");
        }
    }

    private static void recoverIfNeeded() {
        if (recoveryPending) {
            recoveryPending = false;
            OptimizerBridge.success(MODULE);
        }
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof InvocationTargetException && ((InvocationTargetException) error).getCause() != null
            ? ((InvocationTargetException) error).getCause() : error;
    }

    private static final class ModelEntry {
        private final IdentityHashMap<Object, GroupEntry> groups =
            new IdentityHashMap<Object, GroupEntry>();
    }

    private static final class GroupEntry {
        private MeshSignature signature;
        private final VariantEntry[] variants = new VariantEntry[MAX_VARIANTS_PER_GROUP];
        private int variantCount;

        private GroupEntry(MeshSignature signature) { this.signature = signature; }

        private VariantEntry find(Vector4f color, Vector2f uv, VertexFormat format) {
            int cx = Float.floatToRawIntBits(color.x);
            int cy = Float.floatToRawIntBits(color.y);
            int cz = Float.floatToRawIntBits(color.z);
            int cw = Float.floatToRawIntBits(color.w);
            int ux = Float.floatToRawIntBits(uv.x);
            int uy = Float.floatToRawIntBits(uv.y);
            for (int i = 0; i < variantCount; i++) {
                VariantEntry value = variants[i];
                if (value.colorX == cx && value.colorY == cy && value.colorZ == cz && value.colorW == cw
                    && value.uvX == ux && value.uvY == uy && value.format == format) return value;
            }
            return null;
        }

        private void add(VariantEntry value) { variants[variantCount++] = value; }
    }

    private static final class VariantEntry {
        private final int colorX;
        private final int colorY;
        private final int colorZ;
        private final int colorW;
        private final int uvX;
        private final int uvY;
        private final VertexFormat format;
        private int observations = 1;
        private int displayList;
        private long glGeneration;
        private long resourceGeneration;
        private long lastUsedFrame;
        private CacheBudget.Reservation reservation;

        private VariantEntry(Vector4f color, Vector2f uv, VertexFormat format) {
            colorX = Float.floatToRawIntBits(color.x);
            colorY = Float.floatToRawIntBits(color.y);
            colorZ = Float.floatToRawIntBits(color.z);
            colorW = Float.floatToRawIntBits(color.w);
            uvX = Float.floatToRawIntBits(uv.x);
            uvY = Float.floatToRawIntBits(uv.y);
            this.format = format;
        }
    }

    private static final class MeshSignature {
        private final Object mesh;
        private final Object indices;
        private final Object vertices;
        private final Object normals;
        private final int indexCount;
        private final int vertexCount;
        private final int vbo;

        private MeshSignature(Object mesh, Object indices, Object vertices, Object normals,
                              int indexCount, int vertexCount, int vbo) {
            this.mesh = mesh;
            this.indices = indices;
            this.vertices = vertices;
            this.normals = normals;
            this.indexCount = indexCount;
            this.vertexCount = vertexCount;
            this.vbo = vbo;
        }

        private boolean sameAs(MeshSignature other) {
            return other != null && mesh == other.mesh && indices == other.indices
                && vertices == other.vertices && normals == other.normals
                && indexCount == other.indexCount && vertexCount == other.vertexCount && vbo == other.vbo;
        }
    }

    private static final class MeshAccess {
        private final Class<?> objectClass;
        private final Field mesh;
        private final Field indices;
        private final Field vertices;
        private final Field normals;
        private final Field vbo;

        private MeshAccess(Class<?> objectClass) throws Exception {
            this.objectClass = objectClass;
            mesh = field(objectClass, "mesh");
            Class<?> meshClass = mesh.getType();
            indices = field(meshClass, "indices");
            vertices = field(meshClass, "vertices");
            normals = field(meshClass, "normals");
            vbo = field(meshClass, "vbo");
        }

        private MeshSignature capture(Object group) throws Exception {
            Object meshValue = mesh.get(group);
            if (meshValue == null) return null;
            Object indexValue = indices.get(meshValue);
            Object vertexValue = vertices.get(meshValue);
            Object normalValue = normals.get(meshValue);
            int indexCount = indexValue instanceof int[] ? ((int[]) indexValue).length : 0;
            int vertexCount = vertexValue instanceof Object[] ? ((Object[]) vertexValue).length : 0;
            return new MeshSignature(meshValue, indexValue, vertexValue, normalValue,
                indexCount, vertexCount, vbo.getInt(meshValue));
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
