package dev.rlcraft.ice.optimizer.compat.entity;

import static org.junit.Assert.*;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import dev.rlcraft.ice.optimizer.ClientOptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.util.UUID;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class QualityToolsModifierBridgeTest {
    private boolean enabled;
    private boolean module;
    private static final String ATTRIBUTE = "generic.maxHealth";
    private static final UUID ID = new UUID(12L, 34L);

    @Before public void enable() {
        enabled = OptimizerConfig.settings.enabled;
        module = OptimizerConfig.settings.qualityToolsEntityAttributes;
        OptimizerConfig.settings.enabled = true;
        OptimizerConfig.settings.qualityToolsEntityAttributes = true;
        OptimizerRegistry.configure(ClientOptimizerConfig.capture());
        OptimizerRegistry.targetObserved("qualitytools-attributes", "test.Quality", fingerprint(), true);
        OptimizerRegistry.patchInstalled("qualitytools-attributes", "test.Quality", fingerprint());
    }

    @After public void restore() {
        OptimizerConfig.settings.enabled = enabled;
        OptimizerConfig.settings.qualityToolsEntityAttributes = module;
        OptimizerRegistry.configure(ClientOptimizerConfig.capture());
    }

    @Test public void retainsOnlyTheMatchingModifierAndDoesNotMutateIt() {
        AttributeModifier existing = new AttributeModifier(ID, "qualitytools", 2D, 0).setSaved(false);
        AttributeModifier other = new AttributeModifier(new UUID(56L, 78L), "qualitytools", 3D, 0);
        Multimap<String, AttributeModifier> unused = HashMultimap.create();
        unused.put(ATTRIBUTE, existing);
        unused.put(ATTRIBUTE, other);
        assertTrue(QualityToolsModifierBridge.retainExistingModifier(tag(ID), unused));
        assertFalse(unused.containsEntry(ATTRIBUTE, existing));
        assertTrue(unused.containsEntry(ATTRIBUTE, other));
        assertFalse(existing.isSaved());
        assertEquals(2D, existing.getAmount(), 0D);
        assertFalse("a repeated UUID goes through the original already-applied branch",
            QualityToolsModifierBridge.retainExistingModifier(tag(ID), unused));
    }

    @Test public void readsMutationsImmediatelyAndIgnoresUnrelatedLargeData() {
        NBTTagCompound tag = tag(ID);
        tag.setTag("Unrelated", new NBTTagCompound() {
            @Override public NBTTagCompound copy() { throw new AssertionError("Must not copy unrelated NBT"); }
        });
        Multimap<String, AttributeModifier> unused = HashMultimap.create();
        AttributeModifier existing = new AttributeModifier(ID, "qualitytools", 2D, 0);
        unused.put(ATTRIBUTE, existing);
        tag.setLong("UUIDLeast", 99L);
        assertFalse(QualityToolsModifierBridge.retainExistingModifier(tag, unused));
        tag.setLong("UUIDLeast", ID.getLeastSignificantBits());
        assertTrue(QualityToolsModifierBridge.retainExistingModifier(tag, unused));
        unused.put(ATTRIBUTE, existing);
        tag.setString("AttributeName", "generic.attackDamage");
        assertFalse(QualityToolsModifierBridge.retainExistingModifier(tag, unused));
        assertTrue(unused.containsEntry(ATTRIBUTE, existing));
    }

    @Test public void agreesWithOriginalUuidEqualityForChangedAmountsAndOperations() {
        for (double amount : new double[] { -0D, Double.NaN, Double.POSITIVE_INFINITY, -12D }) {
            for (int operation = 0; operation <= 2; operation++) {
                NBTTagCompound tag = tag(ID);
                tag.setDouble("Amount", amount);
                tag.setInteger("Operation", operation);
                tag.setString("Name", "changed name");
                Multimap<String, AttributeModifier> expected = HashMultimap.create();
                AttributeModifier old = new AttributeModifier(ID, "qualitytools", 2D, 0);
                expected.put(ATTRIBUTE, old);
                Multimap<String, AttributeModifier> actual = HashMultimap.create(expected);
                AttributeModifier parsed = SharedMonsterAttributes.readAttributeModifierFromNBT(tag);
                assertTrue(expected.get(ATTRIBUTE).contains(parsed));
                expected.get(ATTRIBUTE).remove(parsed);
                assertTrue(QualityToolsModifierBridge.retainExistingModifier(tag, actual));
                assertEquals(expected, actual);
            }
        }
    }

    @Test public void invalidOrCustomNbtLeavesOriginalFailurePathAndMapUntouched() {
        Multimap<String, AttributeModifier> unused = HashMultimap.create();
        unused.put(ATTRIBUTE, new AttributeModifier(ID, "qualitytools", 2D, 0));
        NBTTagCompound emptyName = tag(ID); emptyName.setString("Name", "");
        NBTTagCompound invalidOperation = tag(ID); invalidOperation.setInteger("Operation", 3);
        NBTTagCompound missingUuid = tag(ID); missingUuid.removeTag("UUIDMost");
        NBTTagCompound customAmount = tag(ID); customAmount.setTag("Amount", new NBTTagDouble(2D) { });
        NBTTagCompound customRoot = new NBTTagCompound() { };
        customRoot.merge(tag(ID));
        for (NBTTagCompound invalid : new NBTTagCompound[] { emptyName, invalidOperation,
                missingUuid, customAmount, customRoot }) {
            assertFalse(QualityToolsModifierBridge.retainExistingModifier(invalid, unused));
            assertEquals(1, unused.size());
        }
    }

    @Test public void customModifiersCollectionsAndDisabledModuleFallBack() {
        NBTTagCompound tag = tag(ID);
        AttributeModifier custom = new AttributeModifier(ID, "qualitytools", 2D, 0) { };
        Multimap<String, AttributeModifier> unused = HashMultimap.create();
        unused.put(ATTRIBUTE, custom);
        assertFalse(QualityToolsModifierBridge.retainExistingModifier(tag, unused));
        assertEquals(1, unused.size());
        Multimap<String, AttributeModifier> list = ArrayListMultimap.create();
        list.put(ATTRIBUTE, new AttributeModifier(ID, "qualitytools", 2D, 0));
        assertFalse(QualityToolsModifierBridge.retainExistingModifier(tag, list));
        assertEquals(1, list.size());
        unused.clear(); unused.putAll(list);
        OptimizerConfig.settings.qualityToolsEntityAttributes = false;
        OptimizerRegistry.configure(ClientOptimizerConfig.capture());
        assertFalse(QualityToolsModifierBridge.retainExistingModifier(tag, unused));
        assertEquals(1, unused.size());
    }

    @Test public void largeModifierSetsKeepOriginalHashLookup() {
        Multimap<String, AttributeModifier> unused = HashMultimap.create();
        unused.put(ATTRIBUTE, new AttributeModifier(ID, "qualitytools", 2D, 0));
        for (int i = 0; i < 128; i++) {
            unused.put(ATTRIBUTE, new AttributeModifier(new UUID(i + 100L, i), "qualitytools", 2D, 0));
        }
        assertFalse(QualityToolsModifierBridge.retainExistingModifier(tag(ID), unused));
        assertEquals(129, unused.size());
    }

    private static NBTTagCompound tag(UUID id) {
        NBTTagCompound tag = SharedMonsterAttributes.writeAttributeModifierToNBT(
            new AttributeModifier(id, "qualitytools", 2D, 0));
        tag.setString("AttributeName", ATTRIBUTE);
        return tag;
    }

    private static String fingerprint() {
        return "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
    }
}
