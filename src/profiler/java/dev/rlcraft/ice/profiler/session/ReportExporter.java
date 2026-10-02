package dev.rlcraft.ice.profiler.session;

import java.io.File;
import java.io.IOException;

public interface ReportExporter {
    File export(RecordingSession session) throws IOException;
}
