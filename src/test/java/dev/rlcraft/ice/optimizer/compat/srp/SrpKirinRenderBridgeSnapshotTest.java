package dev.rlcraft.ice.optimizer.compat.srp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelRenderer;
import org.junit.Test;

public class SrpKirinRenderBridgeSnapshotTest {
    @Test
    public void acceptsLiveBatchRootAnimationButRejectsAnyDescendantTransformChange() throws Exception {
        ModelRenderer root = renderer(101);
        ModelRenderer child = renderer(102);
        ModelRenderer leaf = renderer(103);
        root.addChild(child);
        child.addChild(leaf);
        Object snapshot = captureSnapshot(root);

        root.rotationPointX = 3.5F;
        root.rotationPointY = -2.0F;
        root.rotateAngleZ = 0.75F;
        root.offsetX = -0.125F;
        assertTrue(matches(snapshot));

        child.rotateAngleY = Float.intBitsToFloat(Float.floatToRawIntBits(child.rotateAngleY) + 1);
        assertFalse(matches(snapshot));
    }

    @Test
    public void rejectsVisibilityDisplayListAndChildOrderChanges() throws Exception {
        ModelRenderer root = renderer(201);
        ModelRenderer first = renderer(202);
        ModelRenderer second = renderer(203);
        root.addChild(first);
        root.addChild(second);
        Object snapshot = captureSnapshot(root);

        first.showModel = false;
        assertFalse(matches(snapshot));
        first.showModel = true;
        assertTrue(matches(snapshot));

        setPrivate(first, 999, "displayList", "field_78811_r");
        assertFalse(matches(snapshot));
        setPrivate(first, 202, "displayList", "field_78811_r");
        Collections.swap(root.childModels, 0, 1);
        assertFalse(matches(snapshot));
    }

    @Test
    public void dynamicJointCanBatchThreeStableDescendantsWithoutFreezingItsOwnPose() throws Exception {
        ModelRenderer root = renderer(301);
        ModelRenderer joint = renderer(302);
        ModelRenderer first = renderer(303);
        ModelRenderer second = renderer(304);
        root.addChild(joint);
        joint.addChild(first);
        joint.addChild(second);
        Object rootEntry = captureRoot(root);
        Object rootRecord = field(rootEntry, "root");
        Object jointRecord = ((Object[]) field(rootRecord, "children"))[0];

        for (int i = 0; i < 4; i++) {
            root.rotateAngleY += 0.1F;
            joint.rotateAngleX += 0.2F;
            assertTrue(refresh(rootEntry));
        }
        assertFalse(eligible(rootRecord));
        assertTrue("the joint transform remains live while its own list and descendants batch", eligible(jointRecord));

        first.offsetZ = 0.25F;
        assertTrue(refresh(rootEntry));
        assertFalse(eligible(jointRecord));
    }

    private static ModelRenderer renderer(int displayList) throws Exception {
        ModelRenderer renderer = new ModelRenderer(new ModelBase() { });
        setPrivate(renderer, true, "compiled", "field_78812_q");
        setPrivate(renderer, displayList, "displayList", "field_78811_r");
        return renderer;
    }

    private static Object captureRoot(ModelRenderer root) throws Exception {
        resolveFields();
        Method capture = SrpKirinRenderBridge.class.getDeclaredMethod("captureRoot", ModelRenderer.class, long.class);
        capture.setAccessible(true);
        Object result = capture.invoke(null, root, 1L);
        assertNotNull(result);
        return result;
    }

    private static Object captureSnapshot(ModelRenderer root) throws Exception {
        Object entry = captureRoot(root);
        Object rootRecord = field(entry, "root");
        Method capture = SrpKirinRenderBridge.class.getDeclaredMethod(
            "captureSnapshot", rootRecord.getClass(), boolean.class);
        capture.setAccessible(true);
        Object result = capture.invoke(null, rootRecord, true);
        assertNotNull(result);
        return field(result, "snapshot");
    }

    private static boolean matches(Object snapshot) throws Exception {
        Method matches = snapshot.getClass().getDeclaredMethod("matches", boolean.class);
        matches.setAccessible(true);
        return (Boolean) matches.invoke(snapshot, true);
    }

    private static boolean refresh(Object entry) throws Exception {
        Method refresh = entry.getClass().getDeclaredMethod("refresh");
        refresh.setAccessible(true);
        return (Boolean) refresh.invoke(entry);
    }

    private static boolean eligible(Object record) throws Exception {
        Method eligible = record.getClass().getDeclaredMethod("eligibleBatchRoot");
        eligible.setAccessible(true);
        return (Boolean) eligible.invoke(record);
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void resolveFields() throws Exception {
        Method resolve = SrpKirinRenderBridge.class.getDeclaredMethod("resolveFields");
        resolve.setAccessible(true);
        resolve.invoke(null);
    }

    private static void setPrivate(ModelRenderer renderer, Object value, String... names) throws Exception {
        Field field = null;
        for (String name : names) {
            try {
                field = ModelRenderer.class.getDeclaredField(name);
                break;
            } catch (NoSuchFieldException ignored) { }
        }
        if (field == null) throw new NoSuchFieldException(java.util.Arrays.toString(names));
        field.setAccessible(true);
        field.set(renderer, value);
    }
}
