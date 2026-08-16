package dev.rlcraft.ice.profiler.analysis;

import java.io.File;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

public final class ModResolver {
    private static final int CACHE_LIMIT = 8192;
    private final Map<String, ModIdentity> sources = new HashMap<String, ModIdentity>();
    private final LinkedHashMap<String, ModIdentity> cache = new LinkedHashMap<String, ModIdentity>(256, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ModIdentity> eldest) {
            return size() > CACHE_LIMIT;
        }
    };

    public ModResolver() {
        refresh();
    }

    public synchronized void refresh() {
        sources.clear();
        cache.clear();
        try {
            List<ModContainer> mods = Loader.instance().getModList();
            for (ModContainer mod : mods) {
                File source = mod.getSource();
                if (source == null) continue;
                ModIdentity identity = new ModIdentity(mod.getModId(), mod.getName(), mod.getVersion());
                sources.put(normalize(source), identity);
                sources.put(source.getName().toLowerCase(Locale.ROOT), identity);
            }
        } catch (Throwable ignored) {
            // Unit tests and very early bootstrap can run before Forge's Loader is ready.
        }
    }

    public synchronized ModIdentity resolve(String className) {
        if (className == null || className.isEmpty()) return ModIdentity.UNKNOWN;
        ModIdentity cached = cache.get(className);
        if (cached != null) return cached;
        ModIdentity result = resolveUncached(className);
        cache.put(className, result);
        return result;
    }

    private ModIdentity resolveUncached(String className) {
        if (className.startsWith("net.minecraft.")) return ModIdentity.MINECRAFT;
        if (className.startsWith("net.minecraftforge.") || className.startsWith("cpw.mods.")) return ModIdentity.FORGE;
        if (className.startsWith("java.") || className.startsWith("javax.") || className.startsWith("sun.") || className.startsWith("com.sun.")) return ModIdentity.JVM;
        if (className.startsWith("org.lwjgl.")) return ModIdentity.LWJGL;
        if (className.startsWith("dev.rlcraft.ice.")) return ModIdentity.ICE;

        String resource = className.replace('.', '/') + ".class";
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            URL url = loader == null ? null : loader.getResource(resource);
            if (url == null) url = ModResolver.class.getClassLoader().getResource(resource);
            if (url != null) {
                URLConnection connection = url.openConnection();
                if (connection instanceof JarURLConnection) {
                    File jar = new File(((JarURLConnection) connection).getJarFileURL().toURI());
                    ModIdentity identity = sources.get(normalize(jar));
                    if (identity == null) identity = sources.get(jar.getName().toLowerCase(Locale.ROOT));
                    if (identity != null) return identity;
                } else if ("file".equalsIgnoreCase(url.getProtocol())) {
                    String full = url.toString().toLowerCase(Locale.ROOT);
                    for (Map.Entry<String, ModIdentity> entry : sources.entrySet()) {
                        if (full.contains(entry.getKey().replace('\\', '/'))) return entry.getValue();
                    }
                }
            }
        } catch (Throwable ignored) {
            // Attribution is best-effort and must never affect game loading.
        }
        return ModIdentity.UNKNOWN;
    }

    private static String normalize(File file) {
        try {
            return file.getCanonicalPath().replace('\\', '/').toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return file.getAbsolutePath().replace('\\', '/').toLowerCase(Locale.ROOT);
        }
    }
}
