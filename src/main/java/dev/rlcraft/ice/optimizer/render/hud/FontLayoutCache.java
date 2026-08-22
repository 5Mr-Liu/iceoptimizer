package dev.rlcraft.ice.optimizer.render.hud;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded exact-key layout cache; rendering still emits shadow before main. */
public final class FontLayoutCache {
    private static final Object LEGACY_FONT_KEY = new Object();

    interface PublicationHook {
        void afterEntryPut();
    }

    private static final PublicationHook NO_PUBLICATION_HOOK =
        new PublicationHook() {
            @Override public void afterEntryPut() { }
        };

    public interface LayoutFactory {
        GlyphLayout create();
    }

    public static final class GlyphLayout {
        private final float[] glyphGeometry;
        private final int[] texturePages;
        private final float advance;

        public GlyphLayout(float[] glyphGeometry, int[] texturePages) {
            this(glyphGeometry, texturePages, 0.0F);
        }

        public GlyphLayout(float[] glyphGeometry, int[] texturePages,
                           float advance) {
            if (glyphGeometry == null || texturePages == null
                || glyphGeometry.length % 20 != 0
                || texturePages.length != glyphGeometry.length / 20
                || !finite(advance) || advance < 0.0F) {
                throw new IllegalArgumentException("glyph layout");
            }
            for (float value : glyphGeometry) {
                if (!finite(value)) throw new IllegalArgumentException(
                    "non-finite glyph layout");
            }
            for (int page : texturePages) {
                if (page <= 0) throw new IllegalArgumentException(
                    "invalid font texture page");
            }
            this.glyphGeometry = glyphGeometry.clone();
            this.texturePages = texturePages.clone();
            this.advance = advance;
        }

        public float[] getGlyphGeometry() { return glyphGeometry.clone(); }
        public int[] getTexturePages() { return texturePages.clone(); }
        public int glyphCount() { return texturePages.length; }
        public float getAdvance() { return advance; }
        long retainedBytes() {
            return (long) glyphGeometry.length * 4L
                + (long) texturePages.length * 4L;
        }

        public int texturePage(int glyph) {
            if (glyph < 0 || glyph >= texturePages.length) {
                throw new IndexOutOfBoundsException("glyph");
            }
            return texturePages[glyph];
        }

        /** Writes the complete cached string atomically into the native stream. */
        public boolean record(LwjglHudRenderer renderer, float x, float y) {
            return renderer != null && renderer.recordGlyphRun(
                glyphGeometry, texturePages.length, x, y);
        }

        /** Exact compatibility replay used only if the bounded stream is unavailable. */
        public void replayLegacy(float x, float y) {
            for (int glyph = 0; glyph < texturePages.length; glyph++) {
                int base = glyph * 20;
                boolean begun = false;
                Throwable failure = null;
                try {
                    org.lwjgl.opengl.GL11.glBegin(
                        org.lwjgl.opengl.GL11.GL_TRIANGLE_STRIP);
                    begun = true;
                    for (int vertex = 0; vertex < 4; vertex++) {
                        int offset = base + vertex * 5;
                        org.lwjgl.opengl.GL11.glTexCoord2f(
                            glyphGeometry[offset + 3], glyphGeometry[offset + 4]);
                        org.lwjgl.opengl.GL11.glVertex3f(
                            glyphGeometry[offset] + x,
                            glyphGeometry[offset + 1] + y,
                            glyphGeometry[offset + 2]);
                    }
                } catch (Throwable error) {
                    failure = error;
                } finally {
                    if (begun) try { org.lwjgl.opengl.GL11.glEnd(); }
                    catch (Throwable error) { failure = append(failure, error); }
                }
                if (failure != null) rethrow(failure);
            }
        }

        private static Throwable append(Throwable first, Throwable next) {
            if (first == null) return next;
            Throwable nextFatal = FatalErrors.findFatal(next);
            if (nextFatal != null && FatalErrors.findFatal(first) == null) {
                if (nextFatal != first) nextFatal.addSuppressed(first);
                return nextFatal;
            }
            if (next != null && first != next) first.addSuppressed(next);
            return first;
        }

        private static void rethrow(Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            if (failure instanceof Error) throw (Error) failure;
            throw new IllegalStateException("font compatibility replay failed", failure);
        }
    }

    private final int maximumEntries;
    private final int maximumGlyphs;
    private final CacheBudget budget;
    private final PublicationHook publicationHook;
    private final LinkedHashMap<Key, Entry> cache =
        new LinkedHashMap<Key, Entry>(16, 0.75F, true);
    private int glyphs;
    private long hits;
    private long misses;
    private long evictions;
    private boolean trusted = true;
    private Throwable lastFailure;

    public FontLayoutCache(int maximumEntries, int maximumGlyphs) {
        this(maximumEntries, maximumGlyphs, null, NO_PUBLICATION_HOOK);
    }

    public FontLayoutCache(int maximumEntries, int maximumGlyphs,
                           CacheBudget budget) {
        this(maximumEntries, maximumGlyphs, budget, NO_PUBLICATION_HOOK);
    }

    FontLayoutCache(int maximumEntries, int maximumGlyphs,
                    CacheBudget budget, PublicationHook publicationHook) {
        if (publicationHook == null) {
            throw new IllegalArgumentException("font publication hook");
        }
        this.maximumEntries = Math.max(16, maximumEntries);
        this.maximumGlyphs = Math.max(256, maximumGlyphs);
        this.budget = budget;
        this.publicationHook = publicationHook;
    }

    public synchronized GlyphLayout getOrCreate(String text, int styleFlags,
                                                boolean bidi, long fontGeneration,
                                                LayoutFactory factory) {
        return getOrCreate(LEGACY_FONT_KEY, text, styleFlags, bidi,
            fontGeneration, factory);
    }

    public synchronized GlyphLayout getOrCreate(Object font, String text,
                                                int styleFlags, boolean bidi,
                                                long fontGeneration,
                                                LayoutFactory factory) {
        if (text == null || text.length() > 8192 || fontGeneration <= 0L
            || font == null || factory == null || !trusted) return null;
        Key key = new Key(font, text, styleFlags, bidi, fontGeneration);
        Entry existing = cache.get(key);
        if (existing != null) {
            hits++;
            return existing.layout;
        }
        misses++;
        GlyphLayout created = factory.create();
        if (created == null || created.glyphCount() > maximumGlyphs) return null;
        return putInternal(key, created) ? created : null;
    }

    public synchronized GlyphLayout get(Object font, String text,
                                        int styleFlags, boolean bidi,
                                        long fontGeneration) {
        if (font == null || text == null || text.length() > 8192
            || fontGeneration <= 0L || !trusted) return null;
        Entry existing = cache.get(new Key(font, text, styleFlags, bidi,
            fontGeneration));
        if (existing == null) misses++;
        else hits++;
        return existing == null ? null : existing.layout;
    }

    public synchronized boolean put(Object font, String text, int styleFlags,
                                    boolean bidi, long fontGeneration,
                                    GlyphLayout layout) {
        if (font == null || text == null || text.length() > 8192
            || fontGeneration <= 0L || layout == null
            || layout.glyphCount() > maximumGlyphs || !trusted) return false;
        Key key = new Key(font, text, styleFlags, bidi, fontGeneration);
        return putInternal(key, layout);
    }

    private boolean putInternal(Key key, GlyphLayout layout) {
        Entry previous = cache.get(key);
        while (needsEviction(previous, layout)
            && evictEldestExcept(previous)) { }
        int previousGlyphs = previous == null
            ? 0 : previous.layout.glyphCount();
        if (cache.size() + (previous == null ? 1 : 0) > maximumEntries
            || layout.glyphCount() > maximumGlyphs - (glyphs - previousGlyphs)) {
            return false;
        }
        CacheBudget.Reservation reservation = reserve(layout.retainedBytes());
        while (reservation == null && !cache.isEmpty()) {
            if (!evictEldestExcept(previous)) break;
            reservation = reserve(layout.retainedBytes());
        }
        if (reservation == null) return false;
        Entry replacement = new Entry(layout, reservation);
        try {
            Entry displaced = cache.put(key, replacement);
            if (displaced != previous) {
                throw new IllegalStateException(
                    "font cache publication displaced an unexpected entry");
            }
            publicationHook.afterEntryPut();
        } catch (Throwable publicationFailure) {
            Throwable failure = publicationFailure;
            boolean rolledBack = false;
            try {
                Entry mapped = cache.get(key);
                if (mapped == replacement) {
                    if (previous == null) {
                        Entry removed = cache.remove(key);
                        if (removed != replacement || cache.get(key) != null) {
                            throw new IllegalStateException(
                                "font cache publication rollback failed");
                        }
                    } else {
                        Entry displaced = cache.put(key, previous);
                        if (displaced != replacement
                            || cache.get(key) != previous) {
                            throw new IllegalStateException(
                                "font cache replacement rollback failed");
                        }
                    }
                } else if (mapped != previous) {
                    throw new IllegalStateException(
                        "font cache rollback found a foreign entry");
                }
                rolledBack = true;
            } catch (Throwable rollbackFailure) {
                failure = appendFailure(failure, rollbackFailure);
            }
            if (rolledBack) {
                try { replacement.close(); }
                catch (Throwable releaseFailure) {
                    failure = appendFailure(failure, releaseFailure);
                }
            } else {
                trusted = false;
                lastFailure = failure;
            }
            rethrowFailure(failure);
            return false;
        }
        glyphs = glyphs - previousGlyphs + layout.glyphCount();
        if (previous != null) try {
            previous.close();
        } catch (Throwable releaseFailure) {
            trusted = false;
            lastFailure = releaseFailure;
            rethrowFailure(releaseFailure);
        }
        return true;
    }

    private boolean needsEviction(Entry protectedEntry, GlyphLayout layout) {
        int protectedGlyphs = protectedEntry == null
            ? 0 : protectedEntry.layout.glyphCount();
        return cache.size() + (protectedEntry == null ? 1 : 0) > maximumEntries
            || layout.glyphCount()
                > maximumGlyphs - (glyphs - protectedGlyphs);
    }

    public synchronized void invalidate() {
        trusted = false;
        clearEntries();
        trusted = true;
        lastFailure = null;
    }

    /** Independent same-generation fuse; the HUD stream remains available. */
    public synchronized void disable(Throwable failure) {
        trusted = false;
        lastFailure = failure == null
            ? new IllegalStateException("font cache failure") : failure;
        clearEntries();
    }

    public synchronized boolean isTrusted() { return trusted; }
    public synchronized Throwable consumeLastFailure() {
        Throwable result = lastFailure;
        lastFailure = null;
        return result;
    }

    private void clearEntries() {
        Entry[] removed = cache.values().toArray(
            new Entry[cache.size()]);
        cache.clear();
        glyphs = 0;
        Throwable failure = null;
        for (Entry entry : removed) try { entry.close(); }
        catch (Throwable releaseFailure) {
            failure = appendFailure(failure, releaseFailure);
        }
        if (failure != null) {
            trusted = false;
            lastFailure = failure;
            rethrowFailure(failure);
        }
    }

    public synchronized long getHits() { return hits; }
    public synchronized long getMisses() { return misses; }
    public synchronized long getEvictions() { return evictions; }

    private boolean evictEldestExcept(Entry protectedEntry) {
        Map.Entry<Key, Entry> selected = null;
        for (Map.Entry<Key, Entry> candidate : cache.entrySet()) {
            if (candidate.getValue() != protectedEntry) {
                selected = candidate;
                break;
            }
        }
        if (selected == null) return false;
        Key key = selected.getKey();
        Entry entry = selected.getValue();
        Entry removed = cache.remove(key);
        if (removed != entry) {
            trusted = false;
            IllegalStateException failure = new IllegalStateException(
                "font cache eviction removed an unexpected entry");
            lastFailure = failure;
            throw failure;
        }
        glyphs -= entry.layout.glyphCount();
        evictions++;
        try { entry.close(); }
        catch (Throwable releaseFailure) {
            trusted = false;
            lastFailure = releaseFailure;
            rethrowFailure(releaseFailure);
        }
        return true;
    }

    private CacheBudget.Reservation reserve(long bytes) {
        return budget == null || bytes == 0L
            ? CacheBudget.Reservation.empty()
            : budget.tryReserve(BudgetKind.HEAP, bytes);
    }

    private static final class Entry {
        private final GlyphLayout layout;
        private final CacheBudget.Reservation reservation;
        private Entry(GlyphLayout layout,
                      CacheBudget.Reservation reservation) {
            this.layout = layout;
            this.reservation = reservation;
        }
        private void close() { reservation.close(); }
    }

    private static final class Key {
        private final Object font;
        private final String text;
        private final int flags;
        private final boolean bidi;
        private final long generation;

        private Key(Object font, String text, int flags, boolean bidi,
                    long generation) {
            this.font = font;
            this.text = text;
            this.flags = flags;
            this.bidi = bidi;
            this.generation = generation;
        }

        @Override public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Key)) return false;
            Key other = (Key) value;
            return font == other.font && flags == other.flags && bidi == other.bidi
                && generation == other.generation && text.equals(other.text);
        }

        @Override public int hashCode() {
            int result = System.identityHashCode(font);
            result = 31 * result + text.hashCode();
            result = 31 * result + flags;
            result = 31 * result + (bidi ? 1 : 0);
            return 31 * result + (int) (generation ^ (generation >>> 32));
        }
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static Throwable appendFailure(Throwable first, Throwable next) {
        if (first == null) return next;
        Throwable nextFatal = FatalErrors.findFatal(next);
        if (nextFatal != null && FatalErrors.findFatal(first) == null) {
            if (nextFatal != first) nextFatal.addSuppressed(first);
            return nextFatal;
        }
        if (next != null && first != next) first.addSuppressed(next);
        return first;
    }

    private static void rethrowFailure(Throwable failure) {
        FatalErrors.rethrowIfFatal(failure);
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) throw (Error) failure;
        throw new IllegalStateException("font cache operation failed", failure);
    }
}
