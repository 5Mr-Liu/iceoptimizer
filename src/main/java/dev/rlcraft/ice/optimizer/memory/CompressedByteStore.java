package dev.rlcraft.ice.optimizer.memory;

import java.util.Arrays;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;

/** LZ4 storage for cold optimizer payloads; decompression is byte-for-byte lossless. */
public final class CompressedByteStore implements WeightedValue {
    private static final LZ4Factory FACTORY = LZ4Factory.fastestInstance();
    private final byte[] compressed;
    private final int originalLength;

    private CompressedByteStore(byte[] compressed, int originalLength) {
        this.compressed = compressed;
        this.originalLength = originalLength;
    }

    public static CompressedByteStore compress(byte[] source) {
        if (source == null) throw new IllegalArgumentException("source");
        LZ4Compressor compressor = FACTORY.fastCompressor();
        byte[] target = new byte[compressor.maxCompressedLength(source.length)];
        int length = compressor.compress(source, 0, source.length, target, 0, target.length);
        return new CompressedByteStore(Arrays.copyOf(target, length), source.length);
    }

    public byte[] restore() {
        byte[] result = new byte[originalLength];
        LZ4FastDecompressor decompressor = FACTORY.fastDecompressor();
        decompressor.decompress(compressed, 0, result, 0, originalLength);
        return result;
    }

    public int getOriginalLength() { return originalLength; }
    public int getCompressedLength() { return compressed.length; }

    @Override
    public int weightBytes() {
        return Math.max(1, compressed.length + 16);
    }
}
