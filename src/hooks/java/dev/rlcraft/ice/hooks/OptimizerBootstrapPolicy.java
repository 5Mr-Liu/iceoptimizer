package dev.rlcraft.ice.hooks;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import dev.rlcraft.ice.early.EarlyRendererOwnership;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;

/** Cold-start decisions only; never loads Forge configuration or the client runtime. */
final class OptimizerBootstrapPolicy {
    private static volatile boolean rendererEnabled = true;

    private OptimizerBootstrapPolicy() { }

    static void initialize(Map<String, Object> data) {
        rendererEnabled = true;
        EarlyRendererOwnership.initialize(false);
        Object location = data == null ? null : data.get("mcLocation");
        if (!(location instanceof File)) return;
        if (ExternalTerrainRendererDiscovery.present((File) location)) {
            EarlyRendererOwnership.initialize(true);
            rendererEnabled = false;
            LogManager.getLogger("ICE Optimizer").info(
                "检测到 Nothirium 完整地形渲染器；ICE 渲染层专用钩子和运行时停用，独立优化保留");
            return;
        }
        File config = new File(new File((File) location, "config"), "ice-optimizer.cfg");
        if (!config.exists()) {
            selectNativeRenderer();
            return;
        }
        if (!config.isFile() || config.length() > 1024 * 1024) return;
        try (BufferedReader reader = Files.newBufferedReader(config.toPath(), StandardCharsets.UTF_8)) {
            rendererEnabled = readRendererEnabled(reader);
            if (!rendererEnabled) {
                LogManager.getLogger("ICE Optimizer").info(
                    "ICE 渲染层已在启动配置中关闭；不安装渲染层专用字节码钩子（修改此设置需要重启）");
            }
        } catch (IOException error) {
            LogManager.getLogger("ICE Optimizer").warn("无法读取启动配置；保留默认优化适配器", error);
        }
        boolean optimizerEnabled = true;
        try (BufferedReader reader = Files.newBufferedReader(config.toPath(), StandardCharsets.UTF_8)) {
            optimizerEnabled = readBoolean(reader, "enabled", true);
        } catch (IOException error) {
            LogManager.getLogger("ICE Optimizer").warn("无法读取优化器启用设置", error);
        }
        try (BufferedReader reader = Files.newBufferedReader(config.toPath(), StandardCharsets.UTF_8)) {
            if (optimizerEnabled && readBoolean(reader, "nativeTerrainRenderer", true))
                selectNativeRenderer();
        } catch (IOException error) {
            LogManager.getLogger("ICE Optimizer").warn("无法读取自有地形后端设置；保留既有路径", error);
        }
    }

    private static void selectNativeRenderer() {
        EarlyRendererOwnership.initialize(false, true);
        rendererEnabled = false;
        LogManager.getLogger("ICE Optimizer").info(
            "ICE 自有地形渲染器已选择；旧混合渲染层停用，区块构建和独立优化保留");
    }

    static boolean shouldInstall(TargetSpec target) {
        if (EarlyRendererOwnership.hasNativeTerrainRenderer()
            && (target.adapterId.equals("modern-terrain-upload-context")
                || target.adapterId.equals("modern-terrain-container-access")
                || target.adapterId.equals("modern-terrain-vbo-emitter")
                || target.adapterId.startsWith("modern-visibility-"))) return true;
        return shouldInstall(target, rendererEnabled);
    }

    static boolean shouldInstall(TargetSpec target, boolean enabled) {
        if (enabled || target.moduleIds.isEmpty()) return true;
        // Shared or unknown capabilities must retain their original installation path.
        for (String module : target.moduleIds) {
            if (!EarlyRendererOwnership.isRendererModule(module)) return true;
        }
        return false;
    }

    /** Accept only an unambiguous boolean at general.settings.modernRenderer. */
    static boolean readRendererEnabled(Reader input) throws IOException {
        return readBoolean(input, "modernRenderer", true);
    }

    static boolean readBoolean(Reader input, String name, boolean fallback) throws IOException {
        BufferedReader reader = input instanceof BufferedReader ? (BufferedReader) input : new BufferedReader(input);
        List<String> categories = new ArrayList<String>();
        Boolean setting = null;
        boolean inList = false;
        String line;
        while ((line = reader.readLine()) != null) {
            String value = line.replace("\uFEFF", "").trim();
            if (value.isEmpty() || value.startsWith("#")) continue;
            if (inList) {
                if (value.equals(">")) inList = false;
                continue;
            }
            if (value.endsWith("<")) {
                inList = true;
            } else if (value.endsWith("{")) {
                String category = value.substring(0, value.length() - 1).trim();
                if (category.startsWith("\"") && category.endsWith("\"") && category.length() >= 2) {
                    category = category.substring(1, category.length() - 1);
                }
                if (category.isEmpty() || category.indexOf('{') >= 0 || category.indexOf('}') >= 0) return fallback;
                categories.add(category);
            } else if (value.equals("}")) {
                if (categories.isEmpty()) return fallback;
                categories.remove(categories.size() - 1);
            } else if (categories.size() == 2 && categories.get(0).equals("general")
                    && categories.get(1).equals("settings")) {
                int equals = value.indexOf('=');
                if (equals < 0 || !value.substring(0, equals).trim().equals("B:" + name)) continue;
                String flag = value.substring(equals + 1).trim();
                if (setting != null || !(flag.equals("true") || flag.equals("false"))) return fallback;
                setting = Boolean.valueOf(flag);
            }
        }
        return inList || !categories.isEmpty() || setting == null ? fallback : setting.booleanValue();
    }
}
