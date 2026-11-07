package dev.rlcraft.ice.optimizer.compat.recurrent;

import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import net.minecraft.world.storage.WorldSavedData;

/** Omits only tags that RCCleanup immediately removes; the original reader still runs. */
public final class RecurrentCleanupBridge {
    static final String DATA = "ivorius.reccomplex.world.gen.feature.WorldStructureGenerationData";
    private static final ClassValue<EntryTypes> TYPES = new ClassValue<EntryTypes>() {
        @Override protected EntryTypes computeValue(Class<?> type) { return new EntryTypes(type); }
    };

    private RecurrentCleanupBridge() { }

    public static NBTTagCompound serializeForCleanup(WorldSavedData data) {
        if (OptimizerBridge.isEnabled("recurrent-cleanup-nbt") && data instanceof RecurrentCleanupAccess
                && DATA.equals(data.getClass().getName())) {
            RecurrentCleanupAccess access = (RecurrentCleanupAccess) data;
            if (canSkipDiscardedTags(data.getClass(), access))
                return access.ice$writeCleanupNbt(new NBTTagCompound());
        }
        return data.serializeNBT();
    }

    static boolean canSkipDiscardedTags(Class<?> type, RecurrentCleanupAccess access) {
        Map<?, ?> entries = access.ice$cleanupEntries();
        if (entries == null || entries.getClass() != HashMap.class
                || !plainChunks(access.ice$cleanupCheckedChunks())
                || !plainChunks(access.ice$cleanupCheckedChunksFinal())) return false;
        EntryTypes types = TYPES.get(type);
        if (types.structure == null) return false;
        try {
            for (Object entry : entries.values()) {
                if (entry == null) return false;
                if (entry.getClass() == types.custom) continue; // Its full writer still runs.
                if (entry.getClass() != types.structure) return false;
                // These original serializers call virtual methods; do not skip custom implementations
                // or hide invalid records that the original serialization would reject.
                Object box = types.box.get(entry), transform = types.transform.get(entry);
                if (types.uuid.get(entry) == null || types.structureId.get(entry) == null
                        || box == null || box.getClass() != StructureBoundingBox.class
                        || transform == null || transform.getClass() != types.transformType) return false;
            }
            return true;
        } catch (IllegalAccessException unavailable) {
            return false;
        }
    }

    private static boolean plainChunks(Set<?> chunks) {
        if (chunks == null || chunks.getClass() != HashSet.class) return false;
        for (Object chunk : chunks) if (chunk == null || chunk.getClass() != ChunkPos.class) return false;
        return true;
    }

    private static final class EntryTypes {
        Class<?> structure, custom, transformType;
        Field uuid, box, transform, structureId;
        EntryTypes(Class<?> data) {
            if (!DATA.equals(data.getName())) return;
            try {
                ClassLoader loader = data.getClassLoader();
                Class<?> entry = Class.forName(DATA + "$Entry", false, loader);
                custom = Class.forName(DATA + "$CustomEntry", false, loader);
                Class<?> candidate = Class.forName(DATA + "$StructureEntry", false, loader);
                transformType = Class.forName("ivorius.ivtoolkit.math.AxisAlignedTransform2D", false, loader);
                uuid = field(entry, "uuid"); box = field(entry, "boundingBox");
                transform = field(candidate, "transform"); structureId = field(candidate, "structureID");
                structure = candidate;
            } catch (ReflectiveOperationException | SecurityException | LinkageError unavailable) {
                structure = null;
            }
        }
        private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
            Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
        }
    }
}
