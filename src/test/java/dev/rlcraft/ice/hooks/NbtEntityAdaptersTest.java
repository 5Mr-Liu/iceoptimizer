package dev.rlcraft.ice.hooks;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** Actual game/mod bytecode, including stack analysis of every changed method. */
public class NbtEntityAdaptersTest {
    @Test public void actualSrgEquipmentKeepsCapsChecksAndReplacesOnlyTwoNbtCalls() throws Exception {
        ClassNode node = transform("ice.minecraft.srg.jar", EquipmentNbtAdapter.TARGET, new EquipmentNbtAdapter());
        assertEquals(2, calls(node, null, EquipmentNbtAdapter.BRIDGE, "equal"));
        assertTrue(calls(node, null, EquipmentNbtAdapter.TARGET, "areCapsCompatible") >= 2);
    }

    @Test public void actualSrgNbtRetainsEncoderAndAddsMapAccessWithoutChangingEquals() throws Exception {
        ClassNode node = transform("ice.minecraft.srg.jar", NbtCompoundAdapter.TARGET, new NbtCompoundAdapter());
        assertTrue(node.interfaces.contains(NbtCompoundAdapter.ACCESS));
        assertEquals(1, calls(node, "func_74734_a", NbtCompoundAdapter.BRIDGE, "write"));
        assertEquals(1, calls(node, "equals", "java/util/Objects", "equals"));
        assertNotNull(method(node, "ice$originalNbtWrite"));
    }

    @Test public void actualHorseAccessorReadsTheArmorSlotDirectly() throws Exception {
        ClassNode node = transform("ice.minecraft.srg.jar", HorseArmorAccessAdapter.TARGET, new HorseArmorAccessAdapter());
        assertTrue(node.interfaces.contains(HorseArmorAccessAdapter.ACCESS));
        assertEquals(1, calls(node, "ice$horseArmor", "net/minecraft/inventory/ContainerHorseChest", "func_70301_a"));
    }

    @Test public void actualQualityToolsContainsNoHorseSerializationOrItemReconstruction() throws Exception {
        ClassNode node = transform("ice.qualitytools.jar", "com/tmtravlr/qualitytools/CommonEventHandler", new QualityToolsAttributeAdapter());
        assertEquals(1, calls(node, "onLivingUpdate", QualityToolsAttributeAdapter.BRIDGE, "horseArmor"));
        assertEquals(0, calls(node, "onLivingUpdate", "net/minecraft/entity/passive/EntityHorse", "func_189511_e"));
        assertEquals(0, calls(node, "onLivingUpdate", "net/minecraft/item/ItemStack", "<init>"));
        assertEquals(1, calls(node, "onLivingUpdate", QualityToolsAttributeAdapter.BRIDGE, "shouldRefresh"));
    }

    @Test public void actualPotionCoreChecksSwitchOnceAndPreservesOriginalEvent() throws Exception {
        ClassNode node = transform("ice.potioncore.jar", PotionCoreNbtAdapter.TARGET, new PotionCoreNbtAdapter());
        assertEquals(1, calls(node, "onLivingUpdate", PotionCoreNbtAdapter.BRIDGE, "isEnabled"));
        int replacements = calls(node, "onLivingUpdate", PotionCoreNbtAdapter.BRIDGE, "getInteger")
            + calls(node, "onLivingUpdate", PotionCoreNbtAdapter.BRIDGE, "getBoolean")
            + calls(node, "onLivingUpdate", PotionCoreNbtAdapter.BRIDGE, "getDouble");
        assertTrue(replacements >= 10);
        assertEquals(0, calls(node, "onLivingUpdate", "net/minecraft/nbt/NBTTagCompound", "func_74762_e"));
        assertNotNull(method(node, PotionCoreNbtAdapter.ORIGINAL));
        assertNull(method(node, PotionCoreNbtAdapter.ORIGINAL).visibleAnnotations);
        assertNotNull(method(node, "onLivingUpdate").visibleAnnotations);
        assertTrue(calls(node, PotionCoreNbtAdapter.ORIGINAL, "net/minecraft/nbt/NBTTagCompound", "func_74762_e") > 0);
    }

    private static ClassNode transform(String property, String owner, OptimizerBytecodeAdapter adapter) throws Exception {
        String path = System.getProperty(property, "");
        Assume.assumeFalse("configure " + property, path.isEmpty());
        byte[] original;
        try (JarFile jar = new JarFile(path); InputStream in = jar.getInputStream(jar.getJarEntry(owner + ".class"))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int count; (count = in.read(buffer)) != -1;) out.write(buffer, 0, count);
            original = out.toByteArray();
        }
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(adapter.transform(owner.replace('/', '.'), original, null)).accept(node, ClassReader.EXPAND_FRAMES);
        for (MethodNode method : node.methods) if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
            new Analyzer(new BasicVerifier()).analyze(node.name, method);
        }
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        for (MethodNode method : node.methods) if (name.equals(method.name)) return method;
        return null;
    }
    private static int calls(ClassNode node, String methodName, String owner, String name) {
        int count = 0;
        for (MethodNode method : node.methods) {
            if (methodName != null && !methodName.equals(method.name)) continue;
            for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (owner.equals(call.owner) && name.equals(call.name)) count++;
            }
        }
        return count;
    }
}
