package dev.rlcraft.ice.optimizer.compat.lycanites;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.RandomAccess;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import net.minecraft.util.ResourceLocation;
import net.minecraft.block.Block;

/** Mutation-aware membership index for Lycanites' normal ArrayList configuration path. */
public final class LycanitesBlockMembershipBridge {
    private static final int MODULE_ORDINAL =
        OptimizationModule.LYCANITES_BLOCK_MEMBERSHIP.ordinal();
    private static final int INDEX_THRESHOLD = 8;
    private static volatile boolean activated;

    private LycanitesBlockMembershipBridge() {
    }

    @SuppressWarnings("unchecked")
    public static List<ResourceLocation> track(List<ResourceLocation> source) {
        if (!OptimizerBridge.isEnabled(MODULE_ORDINAL) || source == null
            || source instanceof IndexedResourceList) return source;
        try {
            // Preserve custom List.contains semantics from other mods.
            if (source.getClass() != ArrayList.class) return source;
            IndexedResourceList indexed = new IndexedResourceList(source);
            if (!activated) {
                activated = true;
                OptimizerBridge.activate(MODULE_ORDINAL,
                    "Lycanites 标准 blockIds 列表已安装可变更追踪的成员索引");
            }
            OptimizerBridge.success(MODULE_ORDINAL);
            return indexed;
        } catch (Throwable error) {
            OptimizerBridge.failure(MODULE_ORDINAL, error);
            return source;
        }
    }

    static boolean isIndexedForTest(List<?> value) {
        return value instanceof IndexedResourceList;
    }

    /** Both lists preserve the side-effect-free membership semantics used by the scan. */
    static boolean hasStandardMembership(List<?> value) {
        return value != null && (value.getClass() == ArrayList.class
            || value instanceof IndexedResourceList);
    }

    /** Block registry names are assigned once; list mutations invalidate this fixed-key table. */
    public static boolean containsBlock(List<ResourceLocation> values, Block block) {
        if (values instanceof IndexedResourceList) return ((IndexedResourceList) values).containsBlock(block);
        return values.contains(block.getRegistryName());
    }

    private static final class IndexedResourceList extends ArrayList<ResourceLocation>
            implements RandomAccess {
        private static final long serialVersionUID = 1L;
        private transient Set<ResourceLocation> membership;
        private transient int indexedModCount = Integer.MIN_VALUE;
        private transient boolean valueChanged = true;
        private transient boolean subListExposed;
        private transient IdentityHashMap<Block, Boolean> blocks;
        private transient int blockModCount = Integer.MIN_VALUE;

        private IndexedResourceList(Collection<? extends ResourceLocation> source) {
            super(source);
        }

        @Override public boolean contains(Object value) {
            if (size() < INDEX_THRESHOLD || subListExposed) return super.contains(value);
            if (membership == null || indexedModCount != modCount || valueChanged) {
                blocks = null;
                membership = new HashSet<ResourceLocation>(this);
                indexedModCount = modCount;
                valueChanged = false;
            }
            return membership.contains(value);
        }

        private boolean containsBlock(Block block) {
            if (subListExposed) return super.contains(block.getRegistryName());
            if (blocks == null || blockModCount != modCount || valueChanged) {
                blocks = new IdentityHashMap<Block, Boolean>();
                blockModCount = modCount;
                // Both indexes own the same mutation state; reset the other one together.
                membership = null;
                indexedModCount = Integer.MIN_VALUE;
                valueChanged = false;
            }
            Boolean found = blocks.get(block);
            if (found == null) {
                ResourceLocation name = block.getRegistryName();
                if (name == null) return super.contains(null);
                found = Boolean.valueOf(super.contains(name));
                blocks.put(block, found);
            }
            return found.booleanValue();
        }

        @Override public ResourceLocation set(int index, ResourceLocation element) {
            ResourceLocation previous = super.set(index, element);
            valueChanged = true;
            return previous;
        }

        @Override public boolean add(ResourceLocation value) {
            boolean changed = super.add(value);
            if (changed) valueChanged = true;
            return changed;
        }

        @Override public void add(int index, ResourceLocation element) {
            super.add(index, element);
            valueChanged = true;
        }

        @Override public boolean addAll(Collection<? extends ResourceLocation> values) {
            boolean changed = super.addAll(values);
            if (changed) valueChanged = true;
            return changed;
        }

        @Override public boolean addAll(int index, Collection<? extends ResourceLocation> values) {
            boolean changed = super.addAll(index, values);
            if (changed) valueChanged = true;
            return changed;
        }

        @Override public ResourceLocation remove(int index) {
            ResourceLocation removed = super.remove(index);
            valueChanged = true;
            return removed;
        }

        @Override public boolean remove(Object value) {
            boolean changed = super.remove(value);
            if (changed) valueChanged = true;
            return changed;
        }

        @Override public boolean removeAll(Collection<?> values) {
            boolean changed = super.removeAll(values);
            if (changed) valueChanged = true;
            return changed;
        }

        @Override public boolean retainAll(Collection<?> values) {
            boolean changed = super.retainAll(values);
            if (changed) valueChanged = true;
            return changed;
        }

        @Override public boolean removeIf(Predicate<? super ResourceLocation> filter) {
            boolean changed = super.removeIf(filter);
            if (changed) valueChanged = true;
            return changed;
        }

        @Override public void replaceAll(UnaryOperator<ResourceLocation> operator) {
            super.replaceAll(operator);
            valueChanged = true;
        }

        @Override public void clear() {
            if (!isEmpty()) valueChanged = true;
            super.clear();
        }

        @Override protected void removeRange(int fromIndex, int toIndex) {
            if (fromIndex != toIndex) valueChanged = true;
            super.removeRange(fromIndex, toIndex);
        }

        @Override public List<ResourceLocation> subList(int fromIndex, int toIndex) {
            subListExposed = true;
            return super.subList(fromIndex, toIndex);
        }

        @Override public Object clone() {
            return new IndexedResourceList(this);
        }
    }
}
