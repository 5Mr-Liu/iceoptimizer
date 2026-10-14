package dev.rlcraft.ice.benchmark;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Submission-order packing upper bound, not a runtime allocator or FPS test. */
public final class TerrainSpatialLayoutProbe {
    private static final long PAGE = 8L * 1024 * 1024;
    private static final class Page { int id; long used; }
    public static void main(String[] args) throws Exception {
        TerrainSceneData scene = TerrainSceneData.read(args[0]);
        Map<String, Page> pages = new HashMap<String, Page>();
        String previousRegion = null;
        int previousBuffer = -1, previousIdeal = -1;
        long previousEnd = -1;
        int owned = 0, originalRuns = 0, regionRuns = 0, idealRuns = 0, idealDraws = 0, pageCount = 0;
        long bytes = 0;
        for (TerrainSceneData.Section section : scene.sections) {
            if (!section.owned || section.data.length > PAGE) {
                previousRegion = null; previousBuffer = previousIdeal = -1; previousEnd = -1;
                continue;
            }
            owned++; bytes += section.data.length;
            String region = Arrays.toString(section.region);
            boolean sameRegion = region.equals(previousRegion);
            if (!sameRegion) regionRuns++;
            if (!sameRegion || previousBuffer != section.buffer) originalRuns++;
            Page page = pages.get(region);
            if (page == null || page.used + section.data.length > PAGE) {
                page = new Page(); page.id = pageCount++;
                pages.put(region, page);
            }
            if (!sameRegion || previousIdeal != page.id) idealRuns++;
            if (!sameRegion || previousIdeal != page.id || previousEnd != page.used) idealDraws++;
            page.used += section.data.length;
            previousEnd = page.used;
            previousRegion = region; previousBuffer = section.buffer; previousIdeal = page.id;
        }
        System.out.println("layer=" + scene.layer + " owned=" + owned + " visible=" + scene.sections.length);
        System.out.println("captured_region_page_runs=" + originalRuns + " same_region_lower_bound=" + regionRuns);
        System.out.println("ideal_visible_only_page_runs=" + idealRuns + " ideal_unsplit_draws=" + idealDraws);
        System.out.println("ideal_visible_bytes=" + bytes + " pages=" + pageCount + " capacity=" + pageCount * PAGE);
        System.out.println("scope=optimistic frozen-view packing; excludes unseen meshes, updates, copy cost and directional splits; no FPS claim");
    }
}
