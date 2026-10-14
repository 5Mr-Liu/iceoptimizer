package dev.rlcraft.ice.optimizer.compat.nbt;

import static org.junit.Assert.*;
import net.minecraft.nbt.*;
import org.junit.Test;

public class PotionCoreNbtBridgeTest {
    @Test public void everyNumericTypeWrongTypesAndMissingValuesRetainVanillaConversion() {
        NBTBase[] values = { new NBTTagByte((byte) -3), new NBTTagShort((short) 513),
            new NBTTagInt(257), new NBTTagLong(Long.MAX_VALUE), new NBTTagFloat(-2.75F),
            new NBTTagDouble(Double.NaN), new NBTTagDouble(Double.POSITIVE_INFINITY),
            new NBTTagString("7"), new NBTTagCompound(), new NBTTagList() };
        NBTTagCompound compound = new NBTTagCompound();
        check(compound);
        for (NBTBase value : values) { compound.setTag("value", value); check(compound); }
        compound.setInteger("value", 10); check(compound);
        compound.setInteger("value", 20); check(compound);
    }

    @Test public void preservesModCompoundGetterOverrides() {
        NBTTagCompound custom = new NBTTagCompound() {
            @Override public int getInteger(String key) { return 71; }
            @Override public boolean getBoolean(String key) { return true; }
            @Override public double getDouble(String key) { return 1.5D; }
        };
        check(custom);
    }

    private static void check(NBTTagCompound value) {
        assertEquals(value.getInteger("value"), PotionCoreNbtBridge.getInteger(value, "value"));
        assertEquals(value.getBoolean("value"), PotionCoreNbtBridge.getBoolean(value, "value"));
        assertEquals(Double.doubleToLongBits(value.getDouble("value")),
            Double.doubleToLongBits(PotionCoreNbtBridge.getDouble(value, "value")));
    }
}
