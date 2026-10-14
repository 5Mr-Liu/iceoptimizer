import java.io.*;
import net.minecraft.nbt.*;

/** Reads only scene metadata, without exposing player identifiers or inventory. */
public final class WorldBenchmarkMetadata {
    public static void main(String[] args) throws Exception {
        for (String path : args) try (InputStream stream = new FileInputStream(path)) {
            NBTTagCompound data = CompressedStreamTools.readCompressed(stream).getCompoundTag("Data");
            NBTTagCompound player = data.getCompoundTag("Player");
            NBTTagList position = player.getTagList("Pos", 6);
            System.out.println("world=" + new File(path).getParentFile().getName()
                + " hasPlayer=" + data.hasKey("Player") + " position=" + position.getDoubleAt(0)
                + "," + position.getDoubleAt(1) + "," + position.getDoubleAt(2)
                + " dimension=" + player.getInteger("Dimension") + " mode=" + player.getInteger("playerGameType")
                + " time=" + data.getLong("Time") + " day=" + data.getLong("DayTime"));
        }
    }
}
