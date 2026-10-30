package dev.rlcraft.ice.hooks;

/** Core-only policy: no Minecraft, Forge, GL or runtime initialization. */
public final class OptifineItemAnimationPolicy {
    private OptifineItemAnimationPolicy() { }

    public static boolean allowAnimation(boolean terrainEnabled, String spriteName) {
        if (terrainEnabled) return true;
        if (spriteName == null) return false;
        int path = spriteName.indexOf(':') + 1;
        // Match the resource path, never a mod id or a substring inside blocks/.
        // 1.12 uses items/; some mod/resource-pack models use item/ instead.
        return spriteName.startsWith("items/", path) || spriteName.startsWith("item/", path);
    }
}
