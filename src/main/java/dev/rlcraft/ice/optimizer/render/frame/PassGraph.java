package dev.rlcraft.ice.optimizer.render.frame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/** Immutable semantic ordering and dependency graph for one rendered view. */
public final class PassGraph {
    private final List<RenderPass> order;
    private final Map<RenderPass, Integer> indices;
    private final Map<RenderPass, EnumSet<RenderPass>> prerequisites;

    private PassGraph(List<RenderPass> order,
                      Map<RenderPass, EnumSet<RenderPass>> prerequisites) {
        this.order = Collections.unmodifiableList(new ArrayList<RenderPass>(order));
        EnumMap<RenderPass, Integer> builtIndices =
            new EnumMap<RenderPass, Integer>(RenderPass.class);
        for (int i = 0; i < order.size(); i++) {
            RenderPass pass = order.get(i);
            if (builtIndices.put(pass, Integer.valueOf(i)) != null) {
                throw new IllegalArgumentException("duplicate pass " + pass);
            }
        }
        this.indices = Collections.unmodifiableMap(builtIndices);
        EnumMap<RenderPass, EnumSet<RenderPass>> copied =
            new EnumMap<RenderPass, EnumSet<RenderPass>>(RenderPass.class);
        for (Map.Entry<RenderPass, EnumSet<RenderPass>> entry : prerequisites.entrySet()) {
            if (!builtIndices.containsKey(entry.getKey())) {
                throw new IllegalArgumentException("unknown pass " + entry.getKey());
            }
            EnumSet<RenderPass> set = entry.getValue().isEmpty()
                ? EnumSet.noneOf(RenderPass.class) : EnumSet.copyOf(entry.getValue());
            for (RenderPass required : set) {
                Integer requiredIndex = builtIndices.get(required);
                if (requiredIndex == null || requiredIndex.intValue()
                    >= builtIndices.get(entry.getKey()).intValue()) {
                    throw new IllegalArgumentException("invalid dependency " + required
                        + " -> " + entry.getKey());
                }
            }
            copied.put(entry.getKey(), set);
        }
        this.prerequisites = Collections.unmodifiableMap(copied);
    }

    public static PassGraph standard() {
        List<RenderPass> order = new ArrayList<RenderPass>();
        // This is the observable 1.12.2 order, not enum declaration order.
        // In particular lit/ordinary particles and weather precede the
        // translucent terrain layer, while Forge entity/TESR pass 1 follows
        // it.  ShaderPack stages remain explicit optional tail phases.
        Collections.addAll(order,
            RenderPass.ANIMATED_TEXTURE_UPLOAD,
            RenderPass.SHADOW_TERRAIN,
            RenderPass.SHADOW_ENTITY,
            RenderPass.SHADOW_TESR,
            RenderPass.SKY,
            RenderPass.MAIN_SOLID,
            RenderPass.MAIN_CUTOUT_MIPPED,
            RenderPass.MAIN_CUTOUT,
            RenderPass.ENTITY_PASS_0,
            RenderPass.TESR_PASS_0,
            RenderPass.ENTITY_MULTIPASS,
            RenderPass.ENTITY_OUTLINE,
            RenderPass.LIT_PARTICLES,
            RenderPass.PARTICLES,
            RenderPass.WEATHER,
            RenderPass.TRANSLUCENT,
            RenderPass.ENTITY_PASS_1,
            RenderPass.TESR_PASS_1,
            RenderPass.DEFERRED,
            RenderPass.HAND,
            RenderPass.COMPOSITE,
            RenderPass.FINAL,
            RenderPass.HUD_GUI);
        EnumMap<RenderPass, EnumSet<RenderPass>> requirements =
            new EnumMap<RenderPass, EnumSet<RenderPass>>(RenderPass.class);
        require(requirements, RenderPass.MAIN_CUTOUT_MIPPED, RenderPass.MAIN_SOLID);
        require(requirements, RenderPass.MAIN_CUTOUT, RenderPass.MAIN_CUTOUT_MIPPED);
        // Weather can be replaced or omitted by a dimension renderer.  The
        // monotonic index check preserves its relative position when it is
        // observed without making it a mandatory prerequisite.
        require(requirements, RenderPass.TRANSLUCENT, RenderPass.MAIN_CUTOUT);
        // ShaderPack stages are optional.  Their relative order is enforced
        // by the monotonic graph index when they are observed, but a disabled
        // ShaderPack must not make the final HUD scope look malformed merely
        // because deferred/composite/final never ran.
        return new PassGraph(order, requirements);
    }

    private static void require(Map<RenderPass, EnumSet<RenderPass>> map,
                                RenderPass pass, RenderPass required) {
        EnumSet<RenderPass> set = map.get(pass);
        if (set == null) {
            set = EnumSet.noneOf(RenderPass.class);
            map.put(pass, set);
        }
        set.add(required);
    }

    public int indexOf(RenderPass pass) {
        Integer value = indices.get(pass);
        return value == null ? -1 : value.intValue();
    }

    public List<RenderPass> getOrder() {
        return order;
    }

    public boolean canBegin(RenderPass pass, EnumSet<RenderPass> completed,
                            int lastCompletedIndex) {
        int index = indexOf(pass);
        if (index < 0 || index < lastCompletedIndex || completed.contains(pass)) return false;
        EnumSet<RenderPass> required = prerequisites.get(pass);
        return required == null || completed.containsAll(required);
    }

    /** Production emitters may expose consecutive runs of one semantic pass. */
    public boolean canObserve(RenderPass pass, EnumSet<RenderPass> completed,
                              int lastCompletedIndex) {
        int index = indexOf(pass);
        if (index >= 0 && index == lastCompletedIndex && completed.contains(pass)) {
            return true;
        }
        return canBegin(pass, completed, lastCompletedIndex);
    }
}
