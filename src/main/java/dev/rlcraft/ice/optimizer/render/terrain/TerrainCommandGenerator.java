package dev.rlcraft.ice.optimizer.render.terrain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Emits commands in the exact caller-provided visible order, including ties. */
public final class TerrainCommandGenerator {
    /** Allocation-free production sink for fallback and indirect encoders. */
    public interface CommandSink {
        void accept(int count, int first, int baseInstance,
                    int originX, int originY, int originZ,
                    long sequence, long checksum);
    }

    public TerrainCommandBatch generate(List<TerrainMesh> visible, TerrainLayer layer) {
        if (visible == null || layer == null) throw new IllegalArgumentException("visible terrain");
        if (visible.isEmpty()) return new TerrainCommandBatch(layer,
            Collections.<TerrainDrawCommand>emptyList());
        final List<TerrainDrawCommand> commands =
            new ArrayList<TerrainDrawCommand>(visible.size());
        CommandSink collector = new CommandSink() {
            @Override public void accept(int count, int first, int baseInstance,
                                         int originX, int originY, int originZ,
                                         long sequence, long checksum) {
                commands.add(new TerrainDrawCommand(count, first, baseInstance,
                    originX, originY, originZ, sequence, checksum));
            }
        };
        for (int index = 0; index < visible.size(); index++) {
            emit(visible.get(index), layer, index, collector);
        }
        return new TerrainCommandBatch(layer, commands);
    }

    /** Emits the exact logical command without allocating a command object. */
    public void emit(TerrainMesh mesh, TerrainLayer layer, int baseInstance,
                     CommandSink sink) {
        if (mesh == null || layer == null || baseInstance < 0 || sink == null
            || mesh.getLayer() != layer) {
            throw new IllegalArgumentException("mixed terrain layer");
        }
        long offset = mesh.getRange().getOffset();
        int stride = mesh.getStrideBytes();
        if (offset < 0L || offset % stride != 0L
            || offset / stride > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "indirect first vertex overflow/alignment");
        }
        sink.accept(mesh.getVertexCount(), (int) (offset / stride),
            baseInstance, mesh.getChunkX(), mesh.getChunkY(),
            mesh.getChunkZ(), mesh.getSequence(), mesh.getChecksum());
    }
}
