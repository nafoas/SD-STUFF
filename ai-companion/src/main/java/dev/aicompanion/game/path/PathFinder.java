package dev.aicompanion.game.path;

import dev.aicompanion.entity.CompanionEntity;
import dev.aicompanion.world.BreakPolicy;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* over the block grid for a 1x2 body, with the moves a player uses: walk, step up, drop down, swim, climb
 * ladders, open wooden doors, break what's in the way (only what the break rules allow), pillar up and bridge
 * gaps with throwaway blocks from the bag. Costs are roughly in ticks, so a slow block to break is a long detour.
 */
public final class PathFinder {
    private PathFinder() {}

    public enum Kind { WALK, ASCEND, DESCEND, SWIM, CLIMB, PILLAR, BRIDGE, DIG_DOWN }

    /** One move: end at pos, after breaking `breaks` and placing a filler block at `place` (if any). */
    public record Step(BlockPos pos, Kind kind, List<BlockPos> breaks, @Nullable BlockPos place) {}

    public record Plan(List<Step> steps, boolean reachesGoal, int blocksPlaced, int blocksBroken) {
        public boolean isEmpty() {
            return steps.isEmpty();
        }
    }

    private static final int MAX_NODES = 5000;
    private static final int MAX_FALL = 3;
    private static final double WALK = 5, WATER = 10, UP = 8, FALL_PER_BLOCK = 2, CLIMB = 6, PILLAR = 14, BRIDGE = 12, MAX_BREAK_TICKS = 160;

    private static final class Node implements Comparable<Node> {
        final BlockPos pos;
        final double g, f;
        final Node parent;
        final Step step;
        final int placed;
        final int broken;

        Node(BlockPos pos, double g, double h, Node parent, Step step, int placed, int broken) {
            this.pos = pos;
            this.g = g;
            this.f = g + h;
            this.parent = parent;
            this.step = step;
            this.placed = placed;
            this.broken = broken;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(f, o.f);
        }
    }

    /** Finds a way from the companion's feet to within `within` blocks of goal. Returns the best partial plan if it can't get all the way. */
    public static Plan find(CompanionEntity c, Vec3d goal, double within, BreakPolicy.Purpose purpose, int fillerBudget) {
        Search s = new Search(c, purpose, fillerBudget);
        BlockPos start = c.getBlockPos();
        if (!s.passable(start) && s.passable(start.up())) start = start.up();
        return s.run(start, goal, within);
    }

    private static final class Search {
        final CompanionEntity c;
        final ServerWorld world;
        final BreakPolicy.Purpose purpose;
        final int fillerBudget;
        final Map<BlockPos, Double> breakCost = new HashMap<>();
        final Map<BlockPos, Boolean> passableCache = new HashMap<>();

        Search(CompanionEntity c, BreakPolicy.Purpose purpose, int fillerBudget) {
            this.c = c;
            this.world = c.serverWorld();
            this.purpose = purpose;
            this.fillerBudget = fillerBudget;
        }

        Plan run(BlockPos start, Vec3d goal, double within) {
            PriorityQueue<Node> open = new PriorityQueue<>();
            Map<BlockPos, Double> best = new HashMap<>();
            Node first = new Node(start, 0, h(start, goal), null, null, 0, 0);
            open.add(first);
            best.put(start, 0.0);
            Node closest = first;
            double closestDist = dist(start, goal);
            int expanded = 0;
            while (!open.isEmpty() && expanded++ < MAX_NODES) {
                Node n = open.poll();
                if (n.g > best.getOrDefault(n.pos, Double.MAX_VALUE)) continue;
                double d = dist(n.pos, goal);
                if (d <= within) return build(n, true);
                if (d < closestDist) {
                    closest = n;
                    closestDist = d;
                }
                for (Node next : neighbors(n, goal)) {
                    if (next.g < best.getOrDefault(next.pos, Double.MAX_VALUE)) {
                        best.put(next.pos, next.g);
                        open.add(next);
                    }
                }
            }
            return build(closest, false);
        }

        private static double dist(BlockPos p, Vec3d goal) {
            return Math.sqrt(goal.squaredDistanceTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5));
        }

        private static double h(BlockPos p, Vec3d goal) {
            return dist(p, goal) * WALK;
        }

        private Plan build(Node end, boolean reached) {
            List<Step> steps = new ArrayList<>();
            for (Node n = end; n.parent != null; n = n.parent) steps.add(0, n.step);
            return new Plan(steps, reached, end.placed, end.broken);
        }

        // -------------------------------------------------------------- moves

        private List<Node> neighbors(Node n, Vec3d goal) {
            List<Node> out = new ArrayList<>(12);
            BlockPos p = n.pos;
            boolean inWater = isWater(p);
            boolean climbing = climbable(p);

            for (Direction d : Direction.Type.HORIZONTAL) {
                BlockPos t = p.offset(d);
                // Level move (walk, swim, or break through), possibly bridging a gap.
                List<BlockPos> breaks = new ArrayList<>();
                double cost = clear(t, breaks) + clear(t.up(), breaks);
                if (cost >= 0) {
                    // (t and t.up() are clear once `breaks` are mined, so only the floor matters here.)
                    if (hasFloor(t) || isWater(t)) {
                        add(out, n, t, (isWater(t) ? WATER : WALK) + cost, Kind.WALK, breaks, null, goal);
                    } else {
                        // Drop down to the first floor below (up to MAX_FALL), unless it's into danger.
                        BlockPos land = t;
                        int fall = 0;
                        while (fall <= MAX_FALL && passable(land.down()) && !isWater(land)) {
                            land = land.down();
                            fall++;
                        }
                        if (fall <= MAX_FALL && (standable(land) || isWater(land)) && fall > 0) {
                            add(out, n, land, WALK + cost + fall * FALL_PER_BLOCK, Kind.DESCEND, breaks, null, goal);
                        }
                        // Or bridge across.
                        if (n.placed < fillerBudget && replaceable(t.down()) && !inWater) {
                            add(out, n, t, BRIDGE + cost, Kind.BRIDGE, breaks, t.down(), goal);
                        }
                    }
                }
                // Step up one block (not straight off a ladder: climb to its top first, then step off).
                if (climbing) continue;
                List<BlockPos> upBreaks = new ArrayList<>();
                double upCost = clear(p.up(2), upBreaks);
                if (upCost >= 0) {
                    BlockPos u = t.up();
                    double c2 = clear(u, upBreaks);
                    double c3 = c2 < 0 ? -1 : clear(u.up(), upBreaks);
                    if (c2 >= 0 && c3 >= 0 && hasFloor(u)) {
                        add(out, n, u, UP + upCost + c2 + c3, Kind.ASCEND, upBreaks, null, goal);
                    }
                }
            }

            // Vertical moves.
            BlockPos above = p.up();
            if (climbing || inWater) {
                if (passable(above.up()) && (climbable(above) || passable(above) || isWater(above))) {
                    add(out, n, above, inWater ? WATER : CLIMB, inWater ? Kind.SWIM : Kind.CLIMB, List.of(), null, goal);
                }
                if (climbable(p.down()) || isWater(p.down())) add(out, n, p.down(), CLIMB, Kind.CLIMB, List.of(), null, goal);
            } else if (n.placed < fillerBudget && (standable(p) || n.step != null && (n.step.kind() == Kind.PILLAR || n.step.kind() == Kind.BRIDGE))) {
                // Pillar: jump and put a block under your feet.
                List<BlockPos> breaks = new ArrayList<>();
                double cost = clear(p.up(2), breaks);
                if (cost >= 0) add(out, n, above, PILLAR + cost, Kind.PILLAR, breaks, p, goal);
            }
            // Dig straight down (only toward something below).
            if (goal.y < p.getY() - 1 && !inWater) {
                BlockPos below = p.down();
                double cost = breakCost(below);
                if (cost >= 0 && !passable(below)) {
                    BlockPos land = below;
                    int fall = 0;
                    while (fall < MAX_FALL && passable(land.down())) {
                        land = land.down();
                        fall++;
                    }
                    if (standable(land) && !nearFluid(below)) {
                        add(out, n, land, cost + WALK + fall * FALL_PER_BLOCK, Kind.DIG_DOWN, List.of(below), null, goal);
                    }
                }
            }
            return out;
        }

        private void add(List<Node> out, Node from, BlockPos to, double cost, Kind kind, List<BlockPos> breaks, @Nullable BlockPos place, Vec3d goal) {
            if (dangerous(to)) return;
            Step step = new Step(to, kind, List.copyOf(breaks), place);
            out.add(new Node(to, from.g + cost, h(to, goal), from, step, from.placed + (place != null ? 1 : 0), from.broken + breaks.size()));
        }

        // -------------------------------------------------------------- terrain

        /** 0 if pos is open, the time to break it (adding it to breaks) if it can be cleared, or -1 if it's in the way for good. */
        private double clear(BlockPos pos, List<BlockPos> breaks) {
            if (passable(pos)) return 0;
            double cost = breakCost(pos);
            if (cost < 0) return -1;
            breaks.add(pos);
            return cost;
        }

        private double breakCost(BlockPos pos) {
            Double cached = breakCost.get(pos);
            if (cached != null) return cached;
            double cost = computeBreakCost(pos);
            breakCost.put(pos, cost);
            return cost;
        }

        private double computeBreakCost(BlockPos pos) {
            BlockState s = world.getBlockState(pos);
            if (s.isAir() || !s.getFluidState().isEmpty()) return -1;
            float hardness = s.getHardness(world, pos);
            if (hardness < 0) return -1;
            if (nearFluid(pos)) return -1; // don't open up lava or a flood
            if (world.getBlockState(pos.up()).getBlock() instanceof FallingBlock) return -1; // sand/gravel would bury it
            if (!BreakPolicy.allowed(c, pos, purpose)) return -1;
            ItemStack tool = c.bestToolFor(s);
            float speed = Math.max(1.0f, tool.getMiningSpeedMultiplier(s));
            double ticks = hardness == 0 ? 1 : Math.ceil(hardness * (CompanionEntity.canHarvestWith(tool, s) ? 30 : 100) / speed);
            if (ticks > MAX_BREAK_TICKS && purpose != BreakPolicy.Purpose.ESCAPE) return -1;
            // Glass drops nothing, so it could never be put back: go through anything else first.
            if (s.isIn(BlockTags.IMPERMEABLE) || s.getBlock() instanceof net.minecraft.block.PaneBlock && !(s.isOf(Blocks.IRON_BARS))) ticks += 60;
            return ticks + 2;
        }

        boolean passable(BlockPos pos) {
            return passableCache.computeIfAbsent(pos, p -> {
                BlockState s = world.getBlockState(p);
                if (s.isOf(Blocks.LAVA) || s.isIn(BlockTags.FIRE) || s.isOf(Blocks.COBWEB) || s.isOf(Blocks.SWEET_BERRY_BUSH) || s.isOf(Blocks.POWDER_SNOW)) return false;
                if (s.getBlock() instanceof DoorBlock && s.isIn(BlockTags.WOODEN_DOORS)) return true; // it opens doors
                if (s.getBlock() instanceof FenceGateBlock) return true;
                if (s.isIn(BlockTags.CLIMBABLE)) return true; // ladders, vines, scaffolding
                return s.getCollisionShape(world, p).isEmpty();
            });
        }

        private boolean standable(BlockPos feet) {
            return passable(feet) && hasFloor(feet);
        }

        /** Something safe to stand on under feet (whether or not feet is clear yet). */
        private boolean hasFloor(BlockPos feet) {
            BlockPos below = feet.down();
            BlockState s = world.getBlockState(below);
            if (climbable(feet)) return true;
            if (s.isOf(Blocks.MAGMA_BLOCK) || s.isOf(Blocks.CACTUS) || s.isIn(BlockTags.CAMPFIRES)) return false;
            if (s.isIn(BlockTags.FENCES) || s.isIn(BlockTags.WALLS) || s.getBlock() instanceof FenceGateBlock) return false; // too tall to stand on
            return !s.getCollisionShape(world, below).isEmpty();
        }

        private boolean replaceable(BlockPos pos) {
            BlockState s = world.getBlockState(pos);
            return s.isAir() || s.isReplaceable() && !s.isOf(Blocks.LAVA);
        }

        private boolean isWater(BlockPos pos) {
            FluidState f = world.getFluidState(pos);
            return f.isIn(FluidTags.WATER);
        }

        private boolean climbable(BlockPos pos) {
            return world.getBlockState(pos).isIn(BlockTags.CLIMBABLE);
        }

        private boolean nearFluid(BlockPos pos) {
            for (Direction d : Direction.values()) {
                if (d == Direction.DOWN) continue;
                if (!world.getFluidState(pos.offset(d)).isEmpty()) return true;
            }
            return false;
        }

        private boolean dangerous(BlockPos feet) {
            for (BlockPos p : new BlockPos[]{feet, feet.up(), feet.down()}) {
                BlockState s = world.getBlockState(p);
                if (s.isOf(Blocks.LAVA) || s.isIn(BlockTags.FIRE) || s.isOf(Blocks.MAGMA_BLOCK)) return true;
            }
            for (Direction d : Direction.Type.HORIZONTAL) {
                if (world.getFluidState(feet.offset(d)).isIn(FluidTags.LAVA)) return true;
            }
            return false;
        }
    }
}
