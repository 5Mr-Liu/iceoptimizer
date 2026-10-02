package dev.rlcraft.ice.optimizer.compat.otg;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/** Test-only directory aliases; Windows junctions require no symbolic-link privilege. */
final class OtgTestPaths {
    private OtgTestPaths() { }
    static void createDirectoryAlias(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
            return;
        } catch (IOException unavailable) {
            if (!System.getProperty("os.name").startsWith("Windows")) throw unavailable;
            Path fixtureRoot = Paths.get(".refactor-backups/1.0.13-otg-fixtures").toAbsolutePath().normalize();
            if (!link.toAbsolutePath().normalize().startsWith(fixtureRoot)
                || !target.toAbsolutePath().normalize().startsWith(fixtureRoot)) throw unavailable;
            Process process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
                link.toAbsolutePath().toString(), target.toAbsolutePath().toString()).redirectErrorStream(true).start();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("workspace junction creation timed out", unavailable);
                }
                ByteArrayOutputStream result = new ByteArrayOutputStream();
                try (InputStream output = process.getInputStream()) {
                    byte[] buffer = new byte[1024]; int count;
                    while ((count = output.read(buffer)) >= 0) result.write(buffer, 0, count);
                }
                if (process.exitValue() != 0) throw new IOException("workspace junction creation failed: " + result.toString(), unavailable);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IOException(interrupted);
            }
        }
    }
}
