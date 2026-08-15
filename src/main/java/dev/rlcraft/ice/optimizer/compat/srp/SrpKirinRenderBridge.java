package dev.rlcraft.ice.optimizer.compat.srp;

import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import dev.rlcraft.ice.optimizer.client.ClientOptimizerRuntime;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.List;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;

/**
 * Adaptive exact branch batching for the reviewed SRP 1.9.11 model trees.
 * A batch root may animate every entity/frame; only its descendants are frozen
 * into the outer display list, and those descendants are validated before each
 * submission. Any drift is handled in the same call by the exact tree walker.
 */
public final class SrpKirinRenderBridge {
    private static final String MODULE = "srp-static-mesh";
    private static final int MAX_CACHED_ROOTS = 96;
    private static final int MAX_TREE_NODES = 512;
    private static final int MIN_BATCH_NODES = 3;
    private static final int WARMUP_CALLS = 4;
    private static final int RETRY_COOLDOWN_CALLS = 40;
    private static final long BYTES_PER_NODE_ESTIMATE = 256L;
    private static final Object CACHE_LOCK = new Object();
    private static final IdentityHashMap<ModelRenderer, RootEntry> CACHE =
        new IdentityHashMap<ModelRenderer, RootEntry>();

    private static Field compiledField;
    private static Field displayListField;
    private static boolean fieldsResolved;
    private static long knownContextGeneration = Long.MIN_VALUE;
    private static volatile boolean activated;
    private static volatile boolean recoveryPending;

    private SrpKirinRenderBridge() {
    }

    public static boolean tryRender(ModelRenderer root, float scale) {
        if (!OptimizerBridge.isEnabled(MODULE) || root == null) return false;
        RootEntry entry = null;
        boolean renderStarted = false;
        try {
            resolveFields();
            synchronized (CACHE_LOCK) {
                long generation = OptimizerBridge.currentGlContextGeneration();
                if (generation != knownContextGeneration) resetForContext(generation);
                if (root.isHidden || !root.showModel) return true;

                entry = CACHE.get(root);
                if (entry == null) {
                    if (CACHE.size() >= MAX_CACHED_ROOTS) return false;
                    entry = captureRoot(root, generation);
                    if (entry == null) return false;
                    CACHE.put(root, entry);
                    return false;
                }
                if (!entry.refresh()) {
                    discardRoot(root, entry, true);
                    return false;
                }
                entry.prepareBatches(scale);
                if (entry.batchCount == 0) return false;

                renderStarted = true;
                renderNode(entry.root, scale);
            }
            if (!activated) {
                activated = true;
                OptimizerBridge.activate(MODULE, "SRP 多模型动态关节下的静态分支已合并提交");
            }
            if (recoveryPending) {
                recoveryPending = false;
                OptimizerBridge.success(MODULE);
            }
            return true;
        } catch (Throwable error) {
            recoveryPending = true;
            OptimizerBridge.failure(MODULE, error);
            if (entry != null) discardRoot(root, entry, true);
            return renderStarted;
        }
    }

    private static RootEntry captureRoot(ModelRenderer root, long generation) throws IllegalAccessException {
        Counter counter = new Counter();
        IdentityHashMap<ModelRenderer, Boolean> activePath = new IdentityHashMap<ModelRenderer, Boolean>();
        NodeRecord record = captureRecord(root, counter, activePath);
        return record == null ? null : new RootEntry(root, record, counter.value, generation);
    }

    private static NodeRecord captureRecord(ModelRenderer node, Counter counter,
                                            IdentityHashMap<ModelRenderer, Boolean> activePath)
        throws IllegalAccessException {
        if (node == null || counter.value >= MAX_TREE_NODES || activePath.put(node, Boolean.TRUE) != null) return null;
        counter.value++;
        try {
            boolean visible = visible(node);
            int displayList = displayList(node, visible);
            if (visible && displayList <= 0) return null;
            List<ModelRenderer> currentChildren = node.childModels;
            NodeRecord[] children;
            if (currentChildren == null || currentChildren.isEmpty()) {
                children = NodeRecord.EMPTY;
            } else {
                children = new NodeRecord[currentChildren.size()];
                for (int i = 0; i < children.length; i++) {
                    NodeRecord child = captureRecord(currentChildren.get(i), counter, activePath);
                    if (child == null) return null;
                    children[i] = child;
                }
            }
            return new NodeRecord(node, children, visible, displayList);
        } finally {
            activePath.remove(node);
        }
    }

    private static BatchEntry compileBatch(NodeRecord root, float scale, long generation) {
        SnapshotCapture capture;
        try {
            capture = captureSnapshot(root, true);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException(error);
        }
        if (capture == null || capture.visibleNodes < MIN_BATCH_NODES) return null;
        long estimatedBytes = Math.max(4096L, capture.visibleNodes * BYTES_PER_NODE_ESTIMATE);
        CacheBudget.Reservation reservation =
            ClientOptimizerRuntime.INSTANCE.tryReserve(BudgetKind.GPU, estimatedBytes);
        if (reservation == null) return null;
        int displayList = 0;
        boolean compiling = false;
        try {
            displayList = GLAllocation.generateDisplayLists(1);
            GlStateManager.glNewList(displayList, GL11.GL_COMPILE);
            compiling = true;
            emitBody(capture.snapshot, scale);
            GlStateManager.glEndList();
            compiling = false;
            return new BatchEntry(raw(scale), capture.snapshot, displayList, capture.visibleNodes,
                generation, reservation);
        } catch (Throwable error) {
            if (compiling) {
                try { GlStateManager.glEndList(); } catch (Throwable ignored) { }
            }
            if (displayList > 0) {
                try { GLAllocation.deleteDisplayLists(displayList); } catch (Throwable ignored) { }
            }
            reservation.close();
            throw error;
        }
    }

    private static SnapshotCapture captureSnapshot(NodeRecord root, boolean batchRoot)
        throws IllegalAccessException {
        boolean visible = visible(root.node);
        int list = displayList(root.node, visible);
        if (visible && list <= 0) return null;
        NodeSnapshot[] children = NodeSnapshot.EMPTY;
        int visibleNodes = visible ? 1 : 0;
        if (visible && root.children.length > 0) {
            children = new NodeSnapshot[root.children.length];
            for (int i = 0; i < children.length; i++) {
                SnapshotCapture child = captureSnapshot(root.children[i], false);
                if (child == null) return null;
                children[i] = child.snapshot;
                visibleNodes += child.visibleNodes;
            }
        }
        return new SnapshotCapture(new NodeSnapshot(root.node, visible, list, children, batchRoot), visibleNodes);
    }

    private static void emitBody(NodeSnapshot node, float scale) {
        GlStateManager.callList(node.displayList);
        for (NodeSnapshot child : node.children) emitFullRender(child, scale);
    }

    private static void emitFullRender(NodeSnapshot node, float scale) {
        if (!node.visible) return;
        GlStateManager.translate(node.offsetX, node.offsetY, node.offsetZ);
        try {
            if (node.rotateAngleX == 0.0F && node.rotateAngleY == 0.0F && node.rotateAngleZ == 0.0F) {
                if (node.rotationPointX == 0.0F && node.rotationPointY == 0.0F && node.rotationPointZ == 0.0F) {
                    emitBody(node, scale);
                } else {
                    GlStateManager.translate(node.rotationPointX * scale, node.rotationPointY * scale,
                        node.rotationPointZ * scale);
                    try { emitBody(node, scale); }
                    finally {
                        GlStateManager.translate(-node.rotationPointX * scale, -node.rotationPointY * scale,
                            -node.rotationPointZ * scale);
                    }
                }
            } else {
                GlStateManager.pushMatrix();
                try {
                    applyRotation(node.rotationPointX, node.rotationPointY, node.rotationPointZ,
                        node.rotateAngleX, node.rotateAngleY, node.rotateAngleZ, scale);
                    emitBody(node, scale);
                } finally {
                    GlStateManager.popMatrix();
                }
            }
        } finally {
            GlStateManager.translate(-node.offsetX, -node.offsetY, -node.offsetZ);
        }
    }

    private static void renderNode(NodeRecord record, float scale) {
        ModelRenderer node = record.node;
        if (!visible(node)) return;
        GlStateManager.translate(node.offsetX, node.offsetY, node.offsetZ);
        try {
            if (node.rotateAngleX == 0.0F && node.rotateAngleY == 0.0F && node.rotateAngleZ == 0.0F) {
                if (node.rotationPointX == 0.0F && node.rotationPointY == 0.0F && node.rotationPointZ == 0.0F) {
                    renderBody(record, scale);
                } else {
                    GlStateManager.translate(node.rotationPointX * scale, node.rotationPointY * scale,
                        node.rotationPointZ * scale);
                    try { renderBody(record, scale); }
                    finally {
                        GlStateManager.translate(-node.rotationPointX * scale, -node.rotationPointY * scale,
                            -node.rotationPointZ * scale);
                    }
                }
            } else {
                GlStateManager.pushMatrix();
                try {
                    applyRotation(node.rotationPointX, node.rotationPointY, node.rotationPointZ,
                        node.rotateAngleX, node.rotateAngleY, node.rotateAngleZ, scale);
                    renderBody(record, scale);
                } finally {
                    GlStateManager.popMatrix();
                }
            }
        } finally {
            GlStateManager.translate(-node.offsetX, -node.offsetY, -node.offsetZ);
        }
    }

    private static void renderBody(NodeRecord record, float scale) {
        BatchEntry batch = record.batch;
        if (batch != null) {
            GlStateManager.callList(batch.displayList);
            return;
        }
        GlStateManager.callList(record.displayList);
        for (NodeRecord child : record.children) renderNode(child, scale);
    }

    private static void applyRotation(float pointX, float pointY, float pointZ,
                                      float angleX, float angleY, float angleZ, float scale) {
        GlStateManager.translate(pointX * scale, pointY * scale, pointZ * scale);
        if (angleZ != 0.0F) GlStateManager.rotate(angleZ * 57.295776F, 0.0F, 0.0F, 1.0F);
        if (angleY != 0.0F) GlStateManager.rotate(angleY * 57.295776F, 0.0F, 1.0F, 0.0F);
        if (angleX != 0.0F) GlStateManager.rotate(angleX * 57.295776F, 1.0F, 0.0F, 0.0F);
    }

    private static void resetForContext(long generation) {
        for (RootEntry entry : CACHE.values()) entry.release(false);
        CACHE.clear();
        knownContextGeneration = generation;
    }

    private static void discardRoot(ModelRenderer root, RootEntry entry, boolean deleteLists) {
        synchronized (CACHE_LOCK) {
            if (CACHE.get(root) != entry) return;
            CACHE.remove(root);
            entry.release(deleteLists && entry.contextGeneration == knownContextGeneration);
        }
    }

    private static synchronized void resolveFields() throws Exception {
        if (fieldsResolved) return;
        Field compiled = findField(ModelRenderer.class, "compiled", "field_78812_q");
        Field displayList = findField(ModelRenderer.class, "displayList", "field_78811_r");
        compiled.setAccessible(true);
        displayList.setAccessible(true);
        compiledField = compiled;
        displayListField = displayList;
        fieldsResolved = true;
    }

    private static Field findField(Class<?> owner, String... names) throws NoSuchFieldException {
        for (String name : names) {
            try { return owner.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(owner.getName() + " " + java.util.Arrays.toString(names));
    }

    private static boolean visible(ModelRenderer node) {
        return !node.isHidden && node.showModel;
    }

    private static int displayList(ModelRenderer node, boolean visible) throws IllegalAccessException {
        if (!visible) return 0;
        return compiledField.getBoolean(node) ? displayListField.getInt(node) : 0;
    }

    private static int raw(float value) {
        return Float.floatToRawIntBits(value);
    }

    private static boolean same(float left, float right) {
        return raw(left) == raw(right);
    }

    private static final class Counter {
        private int value;
    }

    private static final class RootEntry {
        private final ModelRenderer identity;
        private final NodeRecord root;
        private final int nodeCount;
        private final long contextGeneration;
        private long calls;
        private int batchCount;

        private RootEntry(ModelRenderer identity, NodeRecord root, int nodeCount, long contextGeneration) {
            this.identity = identity;
            this.root = root;
            this.nodeCount = nodeCount;
            this.contextGeneration = contextGeneration;
        }

        private boolean refresh() throws IllegalAccessException {
            if (root.node != identity) return false;
            calls++;
            return root.refresh();
        }

        private void prepareBatches(float scale) throws IllegalAccessException {
            batchCount = prepare(root, scale);
        }

        private int prepare(NodeRecord node, float scale) throws IllegalAccessException {
            if (node.batch != null) {
                if (node.batch.scaleBits == raw(scale) && node.batch.snapshot.matches(true)) return 1;
                node.releaseBatch(true);
                node.retryAfter = calls + RETRY_COOLDOWN_CALLS;
            }
            if (calls >= node.retryAfter && node.eligibleBatchRoot()) {
                BatchEntry compiled = compileBatch(node, scale, contextGeneration);
                if (compiled != null) {
                    for (NodeRecord child : node.children) child.releaseAllBatches(true);
                    node.batch = compiled;
                    return 1;
                }
            }
            int result = 0;
            for (NodeRecord child : node.children) result += prepare(child, scale);
            return result;
        }

        private void release(boolean deleteLists) {
            root.releaseAllBatches(deleteLists);
        }
    }

    private static final class NodeRecord {
        private static final NodeRecord[] EMPTY = new NodeRecord[0];
        private final ModelRenderer node;
        private final NodeRecord[] children;
        private boolean visible;
        private int displayList;
        private float rotationPointX;
        private float rotationPointY;
        private float rotationPointZ;
        private float rotateAngleX;
        private float rotateAngleY;
        private float rotateAngleZ;
        private float offsetX;
        private float offsetY;
        private float offsetZ;
        private int fullStableCalls;
        private int metadataStableCalls;
        private long retryAfter;
        private BatchEntry batch;

        private NodeRecord(ModelRenderer node, NodeRecord[] children, boolean visible, int displayList) {
            this.node = node;
            this.children = children;
            captureLocal(visible, displayList);
        }

        private boolean refresh() throws IllegalAccessException {
            boolean nowVisible = visible(node);
            int nowDisplayList = displayList(node, nowVisible);
            if (nowVisible && nowDisplayList <= 0) return false;
            boolean metadataSame = nowVisible == visible && (!nowVisible || nowDisplayList == displayList);
            boolean fullSame = metadataSame
                && same(node.rotationPointX, rotationPointX) && same(node.rotationPointY, rotationPointY)
                && same(node.rotationPointZ, rotationPointZ) && same(node.rotateAngleX, rotateAngleX)
                && same(node.rotateAngleY, rotateAngleY) && same(node.rotateAngleZ, rotateAngleZ)
                && same(node.offsetX, offsetX) && same(node.offsetY, offsetY) && same(node.offsetZ, offsetZ);
            metadataStableCalls = metadataSame ? increment(metadataStableCalls) : 0;
            fullStableCalls = fullSame ? increment(fullStableCalls) : 0;
            captureLocal(nowVisible, nowDisplayList);

            List<ModelRenderer> currentChildren = node.childModels;
            if (children.length == 0) {
                if (currentChildren != null && !currentChildren.isEmpty()) return false;
            } else {
                if (currentChildren == null || currentChildren.size() != children.length) return false;
                for (int i = 0; i < children.length; i++) {
                    if (currentChildren.get(i) != children[i].node) return false;
                }
            }
            for (NodeRecord child : children) if (!child.refresh()) return false;
            return true;
        }

        private boolean eligibleBatchRoot() {
            if (!visible || displayList <= 0 || metadataStableCalls < WARMUP_CALLS) return false;
            int visibleNodes = 1;
            for (NodeRecord child : children) {
                if (!child.stableAsDescendant()) return false;
                visibleNodes += child.visibleNodeCount();
            }
            return visibleNodes >= MIN_BATCH_NODES;
        }

        private boolean stableAsDescendant() {
            if (metadataStableCalls < WARMUP_CALLS) return false;
            if (!visible) return true;
            if (fullStableCalls < WARMUP_CALLS || displayList <= 0) return false;
            for (NodeRecord child : children) if (!child.stableAsDescendant()) return false;
            return true;
        }

        private int visibleNodeCount() {
            if (!visible) return 0;
            int result = 1;
            for (NodeRecord child : children) result += child.visibleNodeCount();
            return result;
        }

        private void captureLocal(boolean nowVisible, int nowDisplayList) {
            visible = nowVisible;
            displayList = nowDisplayList;
            rotationPointX = node.rotationPointX;
            rotationPointY = node.rotationPointY;
            rotationPointZ = node.rotationPointZ;
            rotateAngleX = node.rotateAngleX;
            rotateAngleY = node.rotateAngleY;
            rotateAngleZ = node.rotateAngleZ;
            offsetX = node.offsetX;
            offsetY = node.offsetY;
            offsetZ = node.offsetZ;
        }

        private void releaseBatch(boolean deleteList) {
            BatchEntry value = batch;
            batch = null;
            if (value != null) value.release(deleteList);
        }

        private void releaseAllBatches(boolean deleteLists) {
            releaseBatch(deleteLists);
            for (NodeRecord child : children) child.releaseAllBatches(deleteLists);
        }

        private static int increment(int value) {
            return value == Integer.MAX_VALUE ? value : value + 1;
        }
    }

    private static final class SnapshotCapture {
        private final NodeSnapshot snapshot;
        private final int visibleNodes;

        private SnapshotCapture(NodeSnapshot snapshot, int visibleNodes) {
            this.snapshot = snapshot;
            this.visibleNodes = visibleNodes;
        }
    }

    private static final class BatchEntry {
        private final int scaleBits;
        private final NodeSnapshot snapshot;
        private final int displayList;
        private final int nodeCount;
        private final long contextGeneration;
        private final CacheBudget.Reservation reservation;

        private BatchEntry(int scaleBits, NodeSnapshot snapshot, int displayList, int nodeCount,
                           long contextGeneration, CacheBudget.Reservation reservation) {
            this.scaleBits = scaleBits;
            this.snapshot = snapshot;
            this.displayList = displayList;
            this.nodeCount = nodeCount;
            this.contextGeneration = contextGeneration;
            this.reservation = reservation;
        }

        private void release(boolean deleteList) {
            if (deleteList && contextGeneration == knownContextGeneration) {
                try { GLAllocation.deleteDisplayLists(displayList); } catch (Throwable ignored) { }
            }
            reservation.close();
        }
    }

    private static final class NodeSnapshot {
        private static final NodeSnapshot[] EMPTY = new NodeSnapshot[0];
        private final ModelRenderer node;
        private final boolean visible;
        private final int displayList;
        private final NodeSnapshot[] children;
        private final boolean batchRoot;
        private final float rotationPointX;
        private final float rotationPointY;
        private final float rotationPointZ;
        private final float rotateAngleX;
        private final float rotateAngleY;
        private final float rotateAngleZ;
        private final float offsetX;
        private final float offsetY;
        private final float offsetZ;

        private NodeSnapshot(ModelRenderer node, boolean visible, int displayList,
                             NodeSnapshot[] children, boolean batchRoot) {
            this.node = node;
            this.visible = visible;
            this.displayList = displayList;
            this.children = children;
            this.batchRoot = batchRoot;
            rotationPointX = node.rotationPointX;
            rotationPointY = node.rotationPointY;
            rotationPointZ = node.rotationPointZ;
            rotateAngleX = node.rotateAngleX;
            rotateAngleY = node.rotateAngleY;
            rotateAngleZ = node.rotateAngleZ;
            offsetX = node.offsetX;
            offsetY = node.offsetY;
            offsetZ = node.offsetZ;
        }

        private boolean matches(boolean root) throws IllegalAccessException {
            boolean currentlyVisible = visible(node);
            if (currentlyVisible != visible) return false;
            if (!visible) return true;
            if (!compiledField.getBoolean(node) || displayListField.getInt(node) != displayList) return false;
            if (!(root || batchRoot) && (!same(node.rotationPointX, rotationPointX)
                || !same(node.rotationPointY, rotationPointY) || !same(node.rotationPointZ, rotationPointZ)
                || !same(node.rotateAngleX, rotateAngleX) || !same(node.rotateAngleY, rotateAngleY)
                || !same(node.rotateAngleZ, rotateAngleZ) || !same(node.offsetX, offsetX)
                || !same(node.offsetY, offsetY) || !same(node.offsetZ, offsetZ))) return false;
            List<ModelRenderer> currentChildren = node.childModels;
            if (children.length == 0) return currentChildren == null || currentChildren.isEmpty();
            if (currentChildren == null || currentChildren.size() != children.length) return false;
            for (int i = 0; i < children.length; i++) {
                if (currentChildren.get(i) != children[i].node || !children[i].matches(false)) return false;
            }
            return true;
        }
    }
}
