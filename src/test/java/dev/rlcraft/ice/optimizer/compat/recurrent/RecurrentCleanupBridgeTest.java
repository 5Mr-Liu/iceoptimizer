package dev.rlcraft.ice.optimizer.compat.recurrent;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.storage.WorldSavedData;
import org.junit.Test;
import static org.junit.Assert.*;

public class RecurrentCleanupBridgeTest {
    @Test public void unknownDataUsesItsVirtualSerializerAndPreservesAllFields() {
        OtherData data = new OtherData();
        assertSame(data.tag, RecurrentCleanupBridge.serializeForCleanup(data));
        assertEquals(1, data.calls);
        assertEquals("preserve", data.tag.getString("entries"));
        assertFalse(data.isDirty());
    }
    public static final class OtherData extends WorldSavedData {
        int calls; final NBTTagCompound tag = new NBTTagCompound();
        OtherData() { super("other"); tag.setString("entries", "preserve"); }
        @Override public NBTTagCompound serializeNBT() { calls++; return tag; }
        @Override public void readFromNBT(NBTTagCompound tag) { throw new AssertionError(); }
        @Override public NBTTagCompound writeToNBT(NBTTagCompound tag) { throw new AssertionError(); }
    }
}
