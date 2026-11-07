package dev.rlcraft.ice.optimizer.render.terrain;

/** Exact last-upload contents of the private resident command buffer. */
final class NativeTerrainCommandData {
    private int[] resident = new int[0];
    private int length = -1;

    int changedBytes(int[] words, int bytes) {
        int count = bytes / 4;
        if (count != length) return bytes;
        // Offsets occupy the first four words per draw; indirect commands the
        // following five. Camera motion usually changes only the offsets.
        int offsets = count / 9 * 4;
        for (int i = offsets; i < count; i++)
            if (resident[i] != words[i]) return bytes;
        for (int i = 0; i < offsets; i++)
            if (resident[i] != words[i]) return offsets * 4;
        return 0;
    }

    void uploaded(int[] words, int bytes) {
        int count = bytes / 4;
        if (resident.length < count) resident = new int[count];
        System.arraycopy(words, 0, resident, 0, count);
        length = count;
    }
}
