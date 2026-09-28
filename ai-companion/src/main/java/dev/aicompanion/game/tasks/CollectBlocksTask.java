package dev.aicompanion.game.tasks;

import dev.aicompanion.entity.CompanionEntity;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/** Finds matching blocks that are exposed to air, walks to them and mines them with the right tool. */
public class CollectBlocksTask extends Task {
    private final String name;
    private final Predicate<BlockState> matcher;
    private final int wanted;
    private final int radius;
    private final Set<BlockPos> unreachable = new HashSet<>();
    private BlockPos target;
    private int mined;
    private String lastProblem = "";

    public CollectBlocksTask(String name, Predicate<BlockState> matcher, int wanted, int radius) {
        this.name = name;
        this.matcher = matcher;
        this.wanted = Math.max(1, Math.min(256, wanted));
        this.radius = radius;
    }

    @Override
    public String describe() {
        return "collecting " + name + " (" + mined + "/" + wanted + ")";
    }

    @Override
    protected Result step(CompanionEntity c) {
        if (mined >= wanted) return ok("Mined " + mined + " " + name + ".");
        if (ticks > 20 * 60 * 6) return finish("Ran out of time");
        World world = c.getWorld();
        if (target == null || !matcher.test(world.getBlockState(target))) {
            target = findNearest(c);
            resetMovement();
            if (target == null) return finish("No more reachable " + name + " within " + radius + " blocks" + lastProblem);
        }
        BlockState state = world.getBlockState(target);
        if (!c.canReach(target)) {
            Move move = approach(c, target, 3.0);
            if (move == Move.FAILED) {
                unreachable.add(target);
                target = null;
            }
            return null;
        }
        c.getNavigation().stop();
        if (!CompanionEntity.canHarvestWith(bestTool(c, state), state)) {
            return finish("Needs a better tool to mine " + name + " (e.g. a " + suggestTool(state) + ")");
        }
        if (c.mineStep(target)) {
            mined++;
            target = null;
        }
        return null;
    }

    private Result finish(String reason) {
        return mined > 0 ? ok("Mined " + mined + " of " + wanted + " " + name + ". " + reason + ".") : fail(reason + ".");
    }

    private static net.minecraft.item.ItemStack bestTool(CompanionEntity c, BlockState state) {
        c.equipBestToolFor(state);
        return c.getMainHandStack();
    }

    private static String suggestTool(BlockState state) {
        String id = state.getBlock().getTranslationKey();
        if (id.contains("diamond") || id.contains("emerald") || id.contains("gold") || id.contains("redstone")) return "iron pickaxe";
        if (id.contains("iron") || id.contains("lapis") || id.contains("copper")) return "stone pickaxe";
        if (id.contains("obsidian")) return "diamond pickaxe";
        return "pickaxe";
    }

    private BlockPos findNearest(CompanionEntity c) {
        BlockPos origin = c.getBlockPos();
        World world = c.getWorld();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int vertical = Math.min(radius, 24);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -vertical; dy <= vertical; dy++) {
                    pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    double d = dx * dx + dz * dz + dy * dy * 2.0; // prefer staying on the same level
                    if (d >= bestDistance || !world.isChunkLoaded(pos)) continue;
                    if (!matcher.test(world.getBlockState(pos))) continue;
                    BlockPos found = pos.toImmutable();
                    if (unreachable.contains(found) || !exposed(world, found)) continue;
                    best = found;
                    bestDistance = d;
                }
            }
        }
        if (best == null && !unreachable.isEmpty()) lastProblem = " (" + unreachable.size() + " seen but couldn't get to them)";
        return best;
    }

    private static boolean exposed(World world, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockState n = world.getBlockState(pos.offset(dir));
            if (n.isAir() || !n.isOpaqueFullCube(world, pos.offset(dir))) return true;
        }
        return false;
    }
}
