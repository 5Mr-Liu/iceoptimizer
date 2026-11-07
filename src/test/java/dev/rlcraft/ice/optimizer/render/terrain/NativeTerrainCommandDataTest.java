package dev.rlcraft.ice.optimizer.render.terrain;

import java.util.Arrays;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativeTerrainCommandDataTest {
    @Test public void cameraOnlyUploadsPrefixAndUnchangedFramesUploadNothing() {
        NativeTerrainCommandData data = new NativeTerrainCommandData();
        int[] words = new int[18];
        assertEquals(72, data.changedBytes(words, 72));
        // Planning an upload must not publish it before the driver succeeds.
        assertEquals(72, data.changedBytes(words, 72));
        data.uploaded(words, 72);
        assertEquals(0, data.changedBytes(words, 72));
        words[7] = Float.floatToRawIntBits(-0.0F);
        assertEquals(32, data.changedBytes(words, 72));
        data.uploaded(words, 72);
        words[17]++;
        assertEquals(72, data.changedBytes(words, 72));
        assertEquals(36, data.changedBytes(words, 36));
    }

    @Test public void partialUploadsReconstructExactPacketsAcrossMotionReorderShrinkAndGrowth() {
        NativeTerrainCommandData data = new NativeTerrainCommandData();
        Random random = new Random(51390);
        int[] words = new int[900], gpu = new int[900];
        int count = 40;
        for (int frame = 0; frame < 2000; frame++) {
            if (frame % 17 == 0) count = 1 + random.nextInt(100);
            int size = count * 9, offsets = count * 4;
            if (frame % 5 == 0) words[random.nextInt(size)] = random.nextInt();
            else if (frame % 3 != 0) words[random.nextInt(offsets)] = random.nextInt();
            int bytes = size * 4;
            int changed = data.changedBytes(words, bytes);
            if (changed != 0) {
                System.arraycopy(words, 0, gpu, 0, changed / 4);
                data.uploaded(words, bytes);
            }
            assertArrayEquals("frame " + frame, Arrays.copyOf(words, size), Arrays.copyOf(gpu, size));
            assertEquals(0, data.changedBytes(words, bytes));
        }
    }
}
