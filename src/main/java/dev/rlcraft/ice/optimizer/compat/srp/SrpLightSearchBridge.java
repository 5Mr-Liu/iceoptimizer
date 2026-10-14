package dev.rlcraft.ice.optimizer.compat.srp;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.util.IdentityHashMap;
import java.util.List;
import java.lang.reflect.Method;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEndGateway;
import net.minecraft.block.BlockEndPortalFrame;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.BlockPortal;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.agrona.collections.LongHashSet;

/** Same complete light search, without a position object per cell or a candidate list. */
public final class SrpLightSearchBridge {
    private static final int MODULE = OptimizationModule.SRP_LIGHT_SEARCH.ordinal();
    private static volatile boolean activated;
    private static final ClassValue<Boolean> STANDARD_WORLD_READ = new ClassValue<Boolean>() {
        @Override protected Boolean computeValue(Class<?> type) {
            for (Method method : type.getMethods()) {
                if (("getBlockState".equals(method.getName()) || "func_180495_p".equals(method.getName()))
                    && method.getParameterTypes().length == 1
                    && method.getParameterTypes()[0] == BlockPos.class) {
                    return Boolean.valueOf(method.getDeclaringClass() == World.class);
                }
            }
            return Boolean.FALSE;
        }
    };

    private SrpLightSearchBridge() { }

    public static boolean isEnabled() {
        return OptimizerBridge.isEnabled(MODULE);
    }

    public static BlockPos findSource(World world, Entity parent, int range, int lightTrigger,
                                      List<Long> cant, SrpLightSearchCallbacks callbacks) {
        if (world.getLightFor(EnumSkyBlock.BLOCK, parent.getPosition()) < lightTrigger
            && world.rand.nextInt(3) != 0) return null;
        if (parent.isRiding()) return null;
        BlockPos result = lightTrigger > 0 && STANDARD_WORLD_READ.get(world.getClass()).booleanValue()
            ? scanIndexed(world, parent.posX, parent.posY, parent.posZ, parent.height,
                range, lightTrigger, cant, callbacks)
            : scan(world, parent.posX, parent.posY, parent.posZ, parent.height,
                range, lightTrigger, cant, callbacks);
        if (!activated) {
            activated = true;
            OptimizerBridge.activate(MODULE, "SRP 寻光搜索复用坐标并直接选择最近目标");
        }
        return result;
    }

    static BlockPos scanIndexed(World world, double px, double py, double pz, float height,
                                int range, int lightTrigger, List<Long> cant,
                                SrpLightSearchCallbacks callbacks) {
        Selection selection = new Selection(px, py, pz, lightTrigger, cant, callbacks);
        int minZ = MathHelper.floor(pz - range);
        int maxZ = MathHelper.floor(pz + range);
        for (int dx = -range; dx <= range; dx++) {
            int x = MathHelper.floor(px + dx);
            for (int dy = -4; dy <= height; dy++) {
                int y = MathHelper.floor(py + dy);
                int z = minZ;
                while (z <= maxZ) {
                    int endZ = Math.min(maxZ, (z & ~15) + 15);
                    ExtendedBlockStorage storage = null;
                    if (y >= 0 && y < 256) {
                        Chunk chunk = world.getChunk(x >> 4, z >> 4);
                        storage = chunk.getBlockStorageArray()[y >> 4];
                    }
                    if (storage == null) {
                        IBlockState air = Blocks.AIR.getDefaultState();
                        if (air.getLightValue() >= lightTrigger) {
                            for (int az = z; az <= endZ; az++) selection.accept(air, x, y, az);
                        }
                    } else {
                        SrpLightCandidateIndex index = SrpLightCandidateIndex.forSection(storage.getData());
                        int localX = x & 15, localY = y & 15;
                        int bits = index.row(localX, localY, z & 15, endZ & 15);
                        while (bits != 0) {
                            int localZ = Integer.numberOfTrailingZeros(bits);
                            bits &= bits - 1;
                            int candidateZ = (z & ~15) + localZ;
                            selection.cursor.setPos(x, y, candidateZ);
                            IBlockState state = world.getBlockState(selection.cursor);
                            if (!index.isKnown(localX, localY, localZ)) {
                                index.remember(localX, localY, localZ, state);
                            }
                            selection.accept(state, x, y, candidateZ);
                        }
                    }
                    z = endZ + 1;
                }
            }
        }
        return selection.result();
    }

    private static final class Selection {
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        final LongHashSet blockedPositions = new LongHashSet();
        final IdentityHashMap<Block, Boolean> blockedBlocks = new IdentityHashMap<Block, Boolean>();
        final double px, py, pz;
        final int trigger;
        final SrpLightSearchCallbacks callbacks;
        double nearest = 40000.0D;
        boolean found;
        int bestX, bestY, bestZ;

        Selection(double px, double py, double pz, int trigger, List<Long> cant,
                  SrpLightSearchCallbacks callbacks) {
            this.px = px; this.py = py; this.pz = pz;
            this.trigger = trigger; this.callbacks = callbacks;
            for (Long value : cant) blockedPositions.add(value.longValue());
        }

        void accept(IBlockState state, int x, int y, int z) {
            Block block = state.getBlock();
            if (block instanceof BlockLiquid || block instanceof BlockPortal
                || block instanceof BlockEndGateway || block instanceof BlockEndPortalFrame
                || state.getMaterial() == Material.PORTAL) return;
            Boolean blocked = blockedBlocks.get(block);
            if (blocked == null) {
                blocked = callbacks.ice$lightBlocked(block);
                blockedBlocks.put(block, blocked);
            }
            if (blocked.booleanValue() || state.getLightValue() < trigger
                && state.getMaterial() != Material.CIRCUITS) return;
            cursor.setPos(x, y, z);
            if (blockedPositions.contains(cursor.toLong())) return;
            double distance = cursor.distanceSq(px, py, pz);
            if (distance < nearest) {
                nearest = distance;
                bestX = x; bestY = y; bestZ = z;
                found = true;
            }
        }

        BlockPos result() { return found ? new BlockPos(bestX, bestY, bestZ) : null; }
    }

    static BlockPos scan(IBlockAccess world, double px, double py, double pz, float height,
                         int range, int lightTrigger, List<Long> cant,
                         SrpLightSearchCallbacks callbacks) {
        LongHashSet blockedPositions = new LongHashSet();
        for (Long position : cant) blockedPositions.add(position.longValue());
        IdentityHashMap<Block, Boolean> blockedBlocks = new IdentityHashMap<Block, Boolean>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        double nearest = 40000.0D;
        boolean found = false;
        int bestX = 0, bestY = 0, bestZ = 0;
        for (int x = -range; x <= range; x++) {
            for (int y = -4; y <= height; y++) {
                for (int z = -range; z <= range; z++) {
                    cursor.setPos(px + x, py + y, pz + z);
                    IBlockState state = world.getBlockState(cursor);
                    Block block = state.getBlock();
                    if (block instanceof BlockLiquid || block instanceof BlockPortal
                        || block instanceof BlockEndGateway || block instanceof BlockEndPortalFrame
                        || state.getMaterial() == Material.PORTAL) continue;
                    Boolean blocked = blockedBlocks.get(block);
                    if (blocked == null) {
                        blocked = callbacks.ice$lightBlocked(block);
                        blockedBlocks.put(block, blocked);
                    }
                    if (blocked.booleanValue()) continue;
                    if (state.getLightValue() < lightTrigger
                        && state.getMaterial() != Material.CIRCUITS) continue;
                    if (blockedPositions.contains(cursor.toLong())) continue;
                    double distance = cursor.distanceSq(px, py, pz);
                    if (distance < nearest) {
                        nearest = distance;
                        bestX = cursor.getX();
                        bestY = cursor.getY();
                        bestZ = cursor.getZ();
                        found = true;
                    }
                }
            }
        }
        return found ? new BlockPos(bestX, bestY, bestZ) : null;
    }
}
