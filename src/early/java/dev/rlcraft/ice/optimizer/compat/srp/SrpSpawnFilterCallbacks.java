package dev.rlcraft.ice.optimizer.compat.srp;

/** Core-safe ABI implemented by the transformed SRPMixins handler. */
public interface SrpSpawnFilterCallbacks {
    int ice$spawnParaId(Object wrapper);

    Object ice$spawnEntry(Object wrapper);

    Class<?> ice$spawnEntityClass(Object entry);

    boolean ice$spawnCheckParasiteId(Object saveData, int parasiteId);

    boolean ice$spawnColonyLocked(int parasiteId, Object worldData, boolean parasiteBiome,
                                 Object invocation);

    int ice$spawnColonyPoints(Object worldData, int age);

    boolean ice$spawnSubCapLocked(Class<?> entityClass, int dimensionId);
}
