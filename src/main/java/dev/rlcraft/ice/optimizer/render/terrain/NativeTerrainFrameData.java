package dev.rlcraft.ice.optimizer.render.terrain;

import java.util.Arrays;

/** Four layer queues packed into one offset/indirect-command upload. */
final class NativeTerrainFrameData {
    private final int[][] records = { new int[1792], new int[1792], new int[1792], new int[1792] };
    private int[][] materialRecords;
    private int[] materialWords;
    private boolean hasMaterials;
    final int[] counts = new int[4];
    final int[] starts = new int[4];

    void clear() { Arrays.fill(counts, 0); hasMaterials = false; }

    int[] materials() { return hasMaterials ? materialWords : null; }

    void add(int layer, int count, int first, float x, float y, float z, float scale) {
        add(layer, count, first, 0, x, y, z, scale);
    }

    void add(int layer, int count, int first, int baseVertex, float x, float y, float z, float scale) {
        add(layer, count, first, baseVertex, x, y, z, scale, -1);
    }

    void add(int layer, int count, int first, int baseVertex, float x, float y, float z, float scale, int texture) {
        if (texture >= 0 && materialRecords == null) {
            materialRecords = new int[4][];
            for (int l=0;l<4;l++) {materialRecords[l]=new int[Math.max(256,counts[l]+1)];Arrays.fill(materialRecords[l],-1);}
        }
        int record = counts[layer];
        if (materialRecords != null) {
            if (record == materialRecords[layer].length)materialRecords[layer]=Arrays.copyOf(materialRecords[layer],record*2);
            materialRecords[layer][record]=texture;
        }
        if (texture>=0)hasMaterials=true;
        int slot = counts[layer]++ * 7;
        if (slot + 7 > records[layer].length) records[layer] = Arrays.copyOf(records[layer], records[layer].length * 2);
        int[] words = records[layer];
        words[slot] = Float.floatToRawIntBits(x); words[slot + 1] = Float.floatToRawIntBits(y);
        words[slot + 2] = Float.floatToRawIntBits(z); words[slot + 3] = Float.floatToRawIntBits(scale);
        words[slot + 4] = count; words[slot + 5] = first;
        words[slot + 6] = baseVertex;
    }

    int size() { return counts[0] + counts[1] + counts[2] + counts[3]; }

    void pack(int[] target) {
        if (hasMaterials && (materialWords==null || materialWords.length<size()))materialWords=new int[Math.max(256,size())];
        int commandBase = size() * 4;
        int destination = 0;
        for (int layer = 0; layer < 4; layer++) {
            starts[layer] = destination;
            int[] source = records[layer];
            for (int i = 0; i < counts[layer]; i++, destination++) {
                int from = (layer == 3 ? counts[layer] - 1 - i : i) * 7;
                if(hasMaterials)materialWords[destination]=materialRecords[layer][from/7];
                int offset = destination * 4;
                System.arraycopy(source, from, target, offset, 4);
                offset = commandBase + destination * 5;
                target[offset] = source[from + 4]; target[offset + 1] = 1;
                target[offset + 2] = source[from + 5]; target[offset + 3] = source[from + 6];
                target[offset + 4] = destination;
            }
        }
    }
}
