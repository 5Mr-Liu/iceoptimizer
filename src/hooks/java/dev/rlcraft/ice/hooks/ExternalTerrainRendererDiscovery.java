package dev.rlcraft.ice.hooks;

import java.io.File;
import java.io.IOException;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/** Does not initialize foreign classes while LaunchWrapper is still discovering coremods. */
final class ExternalTerrainRendererDiscovery {
    private static final String PLUGIN = "meldexun.nothirium.mc.asm.NothiriumPlugin";
    private static final String RENDERER = "meldexun/nothirium/mc/renderer/ChunkRenderManager.class";
    private ExternalTerrainRendererDiscovery() { }

    static boolean present(File game) {
        File mods = new File(game, "mods");
        return inDirectory(mods) || inDirectory(new File(mods, "1.12.2"));
    }

    private static boolean inDirectory(File directory) {
        File[] files = directory.listFiles();
        if (files == null) return false;
        for (File file : files) {
            if (!file.isFile() || !file.getName().endsWith(".jar")) continue;
            try (JarFile jar = new JarFile(file, false)) {
                Manifest manifest = jar.getManifest();
                if (manifest != null && PLUGIN.equals(manifest.getMainAttributes().getValue("FMLCorePlugin"))
                    && jar.getJarEntry(PLUGIN.replace('.', '/') + ".class") != null
                    && jar.getJarEntry(RENDERER) != null) return true;
            } catch (IOException ignored) {
                // Forge owns diagnostics for an unreadable mod. A filename is not proof of ownership.
            }
        }
        return false;
    }
}
