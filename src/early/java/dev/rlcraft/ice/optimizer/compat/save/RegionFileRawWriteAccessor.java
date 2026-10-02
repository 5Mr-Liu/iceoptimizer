package dev.rlcraft.ice.optimizer.compat.save;

/** Core-installed ABI for writing an already-zlib-compressed chunk payload. */
public interface RegionFileRawWriteAccessor {
    void ice$writeCompressed(int localChunkX, int localChunkZ, byte[] payload, int length);
}
