package dev.rlcraft.ice.optimizer.compat.nbt;

import static org.junit.Assert.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.nbt.*;
import org.junit.Test;

public class NbtEncodingBridgeTest {
    @Test public void entryEncodingIsByteIdenticalForNestedAndModifiedUtfValues() throws Exception {
        NBTTagCompound compound = new NBTTagCompound();
        compound.setString("unicode\u0000", "NBT \u0000 \ud83d\ude00");
        compound.setIntArray("ints", new int[] { 0, -1, Integer.MIN_VALUE });
        compound.setByteArray("bytes", new byte[] { 1, 2, -1 });
        compound.setDouble("nan", Double.NaN);
        NBTTagList list = new NBTTagList();
        for (int i = 0; i < 12; i++) {
            NBTTagCompound child = new NBTTagCompound();
            child.setLong("value", Long.MIN_VALUE + i);
            list.appendTag(child);
        }
        compound.setTag("list", list);
        ByteArrayOutputStream original = new ByteArrayOutputStream();
        CompressedStreamTools.write(compound, new DataOutputStream(original));
        ByteArrayOutputStream optimized = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(optimized);
        output.writeByte(10); output.writeUTF("");
        NbtEncodingBridge.writeEntries(access(compound), output);
        assertArrayEquals(original.toByteArray(), optimized.toByteArray());
        NBTTagCompound loaded = CompressedStreamTools.read(new DataInputStream(new ByteArrayInputStream(optimized.toByteArray())));
        assertTrue(Double.isNaN(loaded.getDouble("nan")));
        compound.removeTag("nan"); loaded.removeTag("nan");
        assertEquals(compound, loaded);
    }

    @Test public void outputFailurePropagatesAtTheOriginalWrite() throws Exception {
        NBTTagCompound compound = new NBTTagCompound(); compound.setString("key", "value");
        final IOException expected = new IOException("test output failure");
        try {
            NbtEncodingBridge.writeEntries(access(compound), new DataOutputStream(new OutputStream() {
                @Override public void write(int value) throws IOException { throw expected; }
            }));
            fail("expected output failure");
        } catch (IOException error) { assertSame(expected, error); }
    }

    @SuppressWarnings("unchecked") private static NbtCompoundAccess access(NBTTagCompound compound) throws Exception {
        Field mapField = null;
        Method entryMethod = null;
        for (Field field : NBTTagCompound.class.getDeclaredFields()) if (field.getType() == Map.class) mapField = field;
        for (Method method : NBTTagCompound.class.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && Arrays.equals(method.getParameterTypes(),
                new Class<?>[] { String.class, NBTBase.class, DataOutput.class })) entryMethod = method;
        }
        assertNotNull(mapField); assertNotNull(entryMethod);
        mapField.setAccessible(true); entryMethod.setAccessible(true);
        final Map<String, NBTBase> map = (Map<String, NBTBase>) mapField.get(compound);
        final Method writeEntry = entryMethod;
        return new NbtCompoundAccess() {
            @Override public Map<String, NBTBase> ice$tagMap() { return map; }
            @Override public void ice$originalNbtWrite(DataOutput output) { throw new AssertionError(); }
            @Override public void ice$writeNbtEntry(String key, NBTBase value, DataOutput output) throws IOException {
                try { writeEntry.invoke(null, key, value, output); }
                catch (InvocationTargetException error) {
                    Throwable cause = error.getCause();
                    if (cause instanceof IOException) throw (IOException) cause;
                    if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                    if (cause instanceof Error) throw (Error) cause;
                    throw new AssertionError(cause);
                } catch (IllegalAccessException error) { throw new AssertionError(error); }
            }
        };
    }
}
