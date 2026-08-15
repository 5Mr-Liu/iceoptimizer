package dev.rlcraft.ice.hooks;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.Properties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

final class OptimizerDiscovery {
    private static final Logger LOGGER = LogManager.getLogger("ICE Optimizer Discovery");
    private static final String OUTPUT_PROPERTY = "ice.optimizer.developmentDiskOutput";
    private static final String LEGACY_OUTPUT_PROPERTY = "ice.optimizer.discoveryDump";
    private static final String CONFIG_KEY = "B:developmentDiskOutput=";
    private static volatile File gameDirectory;
    private static volatile boolean enabled;

    private OptimizerDiscovery() {
    }

    static void initialize(Map<String, Object> data) {
        Object location = data == null ? null : data.get("mcLocation");
        if (location instanceof File) gameDirectory = (File) location;
        enabled = developmentOutputEnabled(gameDirectory);
    }

    static boolean isEnabled() {
        return enabled;
    }

    static boolean dump(TargetSpec target, String fingerprint, byte[] bytes) {
        if (!enabled || gameDirectory == null || target == null || bytes == null) return false;
        try {
            File root = new File(gameDirectory, "ice-optimizer/discovery").getCanonicalFile();
            Files.createDirectories(root.toPath());
            String safeName = target.className.replace('.', '_').replace('$', '_');
            File classFile = new File(root, safeName + "-" + fingerprint.substring(0, 16) + ".class").getCanonicalFile();
            File infoFile = new File(root, safeName + "-" + fingerprint.substring(0, 16) + ".properties").getCanonicalFile();
            if (!classFile.toPath().startsWith(root.toPath()) || !infoFile.toPath().startsWith(root.toPath())) {
                throw new IOException("discovery path escaped root");
            }
            if (!classFile.isFile()) writeBytes(classFile, bytes);
            if (!infoFile.isFile()) {
                Properties properties = new Properties();
                properties.setProperty("class", target.className);
                properties.setProperty("module", target.moduleId);
                properties.setProperty("adapter", target.adapterId);
                properties.setProperty("sha256", fingerprint);
                FileOutputStream stream = new FileOutputStream(infoFile);
                try {
                    properties.store(stream, "ICE optimizer exact target discovery");
                } finally {
                    stream.close();
                }
            }
            return true;
        } catch (Throwable error) {
            LOGGER.warn("无法写出优化目标发现样本 {}", target.className, error);
            return false;
        }
    }

    private static boolean developmentOutputEnabled(File directory) {
        String explicit = System.getProperty(OUTPUT_PROPERTY);
        if (explicit == null) explicit = System.getProperty(LEGACY_OUTPUT_PROPERTY);
        if (explicit != null) return Boolean.parseBoolean(explicit);
        if (directory == null) return false;
        File config = new File(new File(directory, "config"), "ice-optimizer.cfg");
        if (!config.isFile()) return false;
        try {
            BufferedReader reader = Files.newBufferedReader(config.toPath(), StandardCharsets.UTF_8);
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    String value = line.trim();
                    if (value.startsWith(CONFIG_KEY)) {
                        return Boolean.parseBoolean(value.substring(CONFIG_KEY.length()).trim());
                    }
                }
            } finally {
                reader.close();
            }
        } catch (IOException error) {
            LOGGER.warn("无法读取优化器开发输出配置；保持零写盘模式", error);
        }
        return false;
    }

    private static void writeBytes(File target, byte[] bytes) throws IOException {
        FileOutputStream output = new FileOutputStream(target);
        try {
            output.write(bytes);
        } finally {
            output.close();
        }
    }
}
