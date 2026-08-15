package dev.rlcraft.ice.optimizer.lock;

import dev.rlcraft.ice.optimizer.OptimizerRuntimeSide;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import net.minecraftforge.fml.common.ModContainer;

/**
 * Optional target-component inventory retained only for adapter development.
 *
 * <p>No RLCraft, Dregora or individual mod version is accepted or rejected
 * here. Every patch is independently gated by the target class' method
 * descriptors and exact instruction graph inside its bytecode adapter.</p>
 */
public final class RlcPackLock {
    private static final String[] TARGET_HINTS = {
        "srparasite", "scapeandrun", "foamfix", "xaero", "renderlib", "rltweaker", "optifine",
        "orelib", "dsurround", "dshuds", "rlmixins", "betterfoliage", "better foliage",
        "bettercaves", "better caves", "qualitytools", "quality tools", "quark",
        "dynamictrees", "dynamic trees", "lycanites", "mobends", "mo' bends", "iceandfire", "ice and fire",
        "dregora", "fermium", "normalasm", "eaglemixins", "eagle mixins", "srpmixins",
        "entityculling", "entity culling", "phosphor", "openterraingenerator", "open terrain generator"
    };

    public PackLockStatus inspect(Collection<ModContainer> loadedMods, File gameDirectory, boolean legacyStrictFlag) {
        return inspect(loadedMods, gameDirectory, OptimizerRuntimeSide.CLIENT, legacyStrictFlag, false);
    }

    public PackLockStatus inspect(Collection<ModContainer> loadedMods, File gameDirectory,
                                  boolean legacyStrictFlag, boolean developmentDiskOutput) {
        return inspect(loadedMods, gameDirectory, OptimizerRuntimeSide.CLIENT,
            legacyStrictFlag, developmentDiskOutput);
    }

    public PackLockStatus inspect(Collection<ModContainer> loadedMods, File gameDirectory,
                                  OptimizerRuntimeSide side, boolean legacyStrictFlag,
                                  boolean developmentDiskOutput) {
        try {
            List<PackComponent> observed = developmentDiskOutput
                ? observe(loadedMods) : Collections.<PackComponent>emptyList();
            File output = developmentDiskOutput ? writeObservation(gameDirectory, observed, side) : null;
            String detail = "无整合包版本限制；目标类按方法签名和调用图独立验证";
            if (output != null) detail += "；开发组件清单已写入 " + output.getPath();
            return new PackLockStatus(PackLockState.CAPABILITY, detail, observed, output);
        } catch (Throwable error) {
            return new PackLockStatus(PackLockState.CAPABILITY,
                "组件诊断失败但不影响结构适配：" + error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : ": " + error.getMessage()),
                Collections.<PackComponent>emptyList(), null);
        }
    }

    private List<PackComponent> observe(Collection<ModContainer> loadedMods) throws IOException {
        Map<String, PackComponent> components = new LinkedHashMap<String, PackComponent>();
        for (ModContainer mod : loadedMods) {
            File source = mod.getSource();
            String searchable = (mod.getModId() + " " + mod.getName() + " "
                + (source == null ? "" : source.getName())).toLowerCase(Locale.ROOT);
            if (!isTarget(searchable)) continue;
            String sha = source != null && source.isFile()
                ? ClassFingerprint.sha256(source) : "directory-or-unavailable";
            components.put(mod.getModId(), new PackComponent(mod.getModId(), mod.getName(), mod.getVersion(),
                source == null ? "" : source.getName(), sha));
        }
        return new ArrayList<PackComponent>(components.values());
    }

    private File writeObservation(File gameDirectory, List<PackComponent> observed,
                                  OptimizerRuntimeSide side) throws IOException {
        File root = new File(gameDirectory, "ice-optimizer").getCanonicalFile();
        Files.createDirectories(root.toPath());
        String outputName = side == OptimizerRuntimeSide.DEDICATED_SERVER
            ? "components-observed-server.properties" : "components-observed.properties";
        File output = new File(root, outputName).getCanonicalFile();
        if (!output.toPath().startsWith(root.toPath())) {
            throw new IOException("拒绝写出游戏目录外的组件清单");
        }
        Properties properties = new Properties();
        properties.setProperty("format", "2");
        properties.setProperty("policy", "diagnostic-only");
        properties.setProperty("component.count", String.valueOf(observed.size()));
        for (int i = 0; i < observed.size(); i++) {
            PackComponent component = observed.get(i);
            String prefix = "component." + i + ".";
            properties.setProperty(prefix + "modId", component.getModId());
            properties.setProperty(prefix + "name", component.getName());
            properties.setProperty(prefix + "version", component.getVersion());
            properties.setProperty(prefix + "source", component.getSourceName());
            properties.setProperty(prefix + "sha256", component.getSourceSha256());
        }
        File temporary = File.createTempFile("components-observed-", ".tmp", root);
        FileOutputStream stream = new FileOutputStream(temporary);
        try {
            properties.store(stream, "ICE optimizer diagnostic component inventory");
        } finally {
            stream.close();
        }
        try {
            Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return output;
    }

    private static boolean isTarget(String searchable) {
        for (String hint : TARGET_HINTS) if (searchable.contains(hint)) return true;
        return false;
    }
}
