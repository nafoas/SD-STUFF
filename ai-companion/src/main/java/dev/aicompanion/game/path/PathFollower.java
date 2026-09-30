package dev.aicompanion.game.path;

import dev.aicompanion.entity.CompanionEntity;
import dev.aicompanion.world.BreakPolicy;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks a companion along a {@link PathFinder} plan one step at a time: mines what's in the way, jumps and places
 * a block to pillar up, places a block to bridge, climbs, swims. Replans when it gets stuck. If it's truly trapped it
 * may break one ordinary block to get out, and puts it back once it's through.
 */
public class PathFollower {
    public enum Status { RUNNING, ARRIVED, FAILED }

    private final Vec3d goal;
    private final double within;
    private PathFinder.Plan plan;
    private int index;
    private int stepTicks;
    private int replans;
    private boolean escaping;
    /** Blocks broken to escape, to put back once through. */
    private final List<BlockPos> toRestore = new ArrayList<>();
    private final List<BlockState> restoreStates = new ArrayList<>();
    /** Which step each escape block was broken for; it goes back only once the companion is past that step. */
    private final List<Integer> restoreAfterStep = new ArrayList<>();
    /** Who the block belonged to, so putting it back doesn't make it the companion's. */
    private final List<String> restoreOwners = new ArrayList<>();
    @Nullable private String failure;

    public PathFollower(Vec3d goal, double within) {
        this(goal, within, true);
    }

    /** placeBlocks=false: walk, climb, swim and open doors only (no pillaring or bridging), e.g. for a stroll. */
    public PathFollower(Vec3d goal, double within, boolean placeBlocks) {
        this.goal = goal;
        this.within = within;
        this.placeBlocks = placeBlocks;
    }

    private final boolean placeBlocks;

    public Vec3d goal() {
        return goal;
    }

    @Nullable
    public String failure() {
        return failure;
    }

    public Status tick(CompanionEntity c) {
        if (c.getPos().distanceTo(goal) <= within) {
            restoreEscapeBlocks(c, true);
            return Status.ARRIVED;
        }
        if (plan == null || index >= plan.steps().size()) {
            if (plan != null && plan.reachesGoal()) {
                restoreEscapeBlocks(c, true);
                return c.getPos().distanceTo(goal) <= within + 1.5 ? Status.ARRIVED : replan(c);
            }
            return replan(c);
        }
        restoreEscapeBlocks(c, false);
        PathFinder.Step step = plan.steps().get(index);
        handleDoors(c, step.pos());
        if (++stepTicks > 100) {
            if (dev.aicompanion.ModConfig.get().debugPaths) dev.aicompanion.AiCompanionMod.LOGGER.info("[path] {} stuck on {} {} at {}", c.getCharacterName(),
                    step.kind(), step.pos().toShortString(), c.getPos());
            return replan(c);
        }

        // Clear the way first.
        for (BlockPos b : step.breaks()) {
            BlockState s = c.getWorld().getBlockState(b);
            if (s.isAir() || s.getCollisionShape(c.getWorld(), b).isEmpty()) continue;
            if (!escaping && !BreakPolicy.allowed(c, b, BreakPolicy.Purpose.MOVE)) return replan(c);
            if (escaping && dev.aicompanion.ModConfig.get().debugPaths) dev.aicompanion.AiCompanionMod.LOGGER.info("[path] escape-breaking {} (recorded {})", b.toShortString(), toRestore.size());
            if (escaping && toRestore.size() < 2 && !toRestore.contains(b)) {
                toRestore.add(b);
                restoreStates.add(s);
                restoreAfterStep.add(index);
                restoreOwners.add(dev.aicompanion.world.BlockOwnership.get(c.serverWorld()).owner(b));
            }
            c.getNavigation().stop();
            c.mineStep(b);
            return Status.RUNNING;
        }

        BlockPos target = step.pos();
        switch (step.kind()) {
            case BRIDGE -> {
                if (step.place() != null && c.getWorld().getBlockState(step.place()).isReplaceable()) {
                    if (c.getEyePos().distanceTo(Vec3d.ofCenter(step.place())) > 4.5) {
                        moveToward(c, Vec3d.ofBottomCenter(target.offset(c.getHorizontalFacing().getOpposite())));
                        return Status.RUNNING;
                    }
                    if (!c.placeFiller(step.place())) return fail("ran out of blocks to bridge with");
                    return Status.RUNNING;
                }
                moveToward(c, Vec3d.ofBottomCenter(target));
            }
            case PILLAR -> {
                BlockPos under = step.place();
                if (under != null && c.getWorld().getBlockState(under).isReplaceable()) {
                    c.getMoveControl().moveTo(under.getX() + 0.5, c.getY(), under.getZ() + 0.5, 0.6);
                    if (c.isOnGround()) c.getJumpControl().setActive();
                    // At the top of the jump, once its feet are above the spot, drop a block into it.
                    if (c.getY() >= under.getY() + 1.0 && !c.getBoundingBox().intersects(new net.minecraft.util.math.Box(under))) {
                        if (!c.placeFiller(under)) return fail("ran out of blocks to pillar with");
                    }
                    return Status.RUNNING;
                }
            }
            case CLIMB, SWIM -> {
                Vec3d aim = ladderAim(c, target);
                double dx = aim.x - c.getX(), dz = aim.z - c.getZ();
                boolean up = target.getY() > c.getY() - 0.2;
                if (dx * dx + dz * dz > 0.3 * 0.3) {
                    c.getMoveControl().moveTo(aim.x, c.getY(), aim.z, 0.6);
                    if (up && c.isTouchingWater()) c.getJumpControl().setActive();
                } else {
                    // Lined up: stop steering (a mob spins when its target is straight overhead) and go straight up,
                    // at the same speed the game gives a player climbing a ladder.
                    c.getNavigation().stop();
                    c.setForwardSpeed(0);
                    Vec3d v = c.getVelocity();
                    if (up && (c.isClimbing() || c.isTouchingWater())) c.setVelocity(v.x * 0.3, 0.2, v.z * 0.3);
                    else if (up) c.getJumpControl().setActive();
                    else c.setVelocity(v.x * 0.3, v.y, v.z * 0.3);
                }
            }
            default -> {
                moveToward(c, Vec3d.ofBottomCenter(target));
                // Stepping off the top of a ladder: keep climbing while it steps across, or it drops off.
                if (c.isClimbing() && target.getY() > c.getY() - 0.1) {
                    Vec3d v = c.getVelocity();
                    c.setVelocity(v.x, 0.2, v.z);
                }
            }
        }
        boolean climbingUp = (step.kind() == PathFinder.Kind.CLIMB || step.kind() == PathFinder.Kind.SWIM) && target.getY() >= c.getBlockY();
        if (climbingUp ? c.getY() >= target.getY() - 0.05 && arrived(c, target, 0.45) : arrived(c, target, 0.35)) {
            index++;
            stepTicks = 0;
        }
        return Status.RUNNING;
    }

    /** Players hug a ladder: aim at the side of the block the ladder hangs on, so at the top there's something to stand on. */
    private static Vec3d ladderAim(CompanionEntity c, BlockPos target) {
        for (BlockPos p : new BlockPos[]{target, target.down()}) {
            net.minecraft.block.BlockState s = c.getWorld().getBlockState(p);
            // Only once it's in the ladder's column: hugging the wall from the side would catch on the ladder itself.
            boolean inColumn = c.getBlockX() == target.getX() && c.getBlockZ() == target.getZ();
            if (inColumn && s.getBlock() instanceof net.minecraft.block.LadderBlock) {
                net.minecraft.util.math.Direction wall = s.get(net.minecraft.block.LadderBlock.FACING).getOpposite();
                return new Vec3d(target.getX() + 0.5 + wall.getOffsetX() * 0.22, target.getY(), target.getZ() + 0.5 + wall.getOffsetZ() * 0.22);
            }
        }
        return Vec3d.ofBottomCenter(target);
    }

    /** Call when the trip ends for any reason: puts back anything broken to escape, closes doors behind it. */
    public void finish(CompanionEntity c) {
        restoreEscapeBlocks(c, true);
        for (BlockPos d : openedDoors) setDoor(c, d, false);
        openedDoors.clear();
    }

    /** Wooden doors it opened on the way, to close behind it. */
    private final List<BlockPos> openedDoors = new ArrayList<>();

    /** Opens a wooden door in the way (where it stands or where it's stepping), like a player; closes ones left behind. */
    private void handleDoors(CompanionEntity c, BlockPos next) {
        for (BlockPos p : new BlockPos[]{c.getBlockPos(), next, next.up()}) {
            BlockState s = c.getWorld().getBlockState(p);
            if (s.getBlock() instanceof net.minecraft.block.DoorBlock && s.isIn(net.minecraft.registry.tag.BlockTags.WOODEN_DOORS) && !s.get(net.minecraft.block.DoorBlock.OPEN)) {
                BlockPos lower = s.get(net.minecraft.block.DoorBlock.HALF) == net.minecraft.block.enums.DoubleBlockHalf.UPPER ? p.down() : p;
                setDoor(c, lower, true);
                if (!openedDoors.contains(lower)) openedDoors.add(lower);
            }
        }
        for (int i = openedDoors.size() - 1; i >= 0; i--) {
            BlockPos d = openedDoors.get(i);
            if (c.getBlockPos().getSquaredDistance(d) > 2.5 * 2.5 && !c.getBoundingBox().intersects(new net.minecraft.util.math.Box(d).expand(0, 1, 0))) {
                setDoor(c, d, false);
                openedDoors.remove(i);
            }
        }
    }

    private static void setDoor(CompanionEntity c, BlockPos lower, boolean open) {
        BlockState s = c.getWorld().getBlockState(lower);
        if (s.getBlock() instanceof net.minecraft.block.DoorBlock door && s.get(net.minecraft.block.DoorBlock.OPEN) != open) {
            door.setOpen(c, c.getWorld(), s, lower, open);
        }
    }

    private static void moveToward(CompanionEntity c, Vec3d pos) {
        c.getNavigation().stop();
        c.getMoveControl().moveTo(pos.x, pos.y, pos.z, c.workSpeed());
    }

    private static boolean arrived(CompanionEntity c, BlockPos target, double radius) {
        double dx = c.getX() - (target.getX() + 0.5);
        double dz = c.getZ() - (target.getZ() + 0.5);
        return dx * dx + dz * dz < radius * radius && Math.abs(c.getY() - target.getY()) < 0.6;
    }

    private Status replan(CompanionEntity c) {
        if (replans++ >= 4) return fail("couldn't find a way there");
        for (int i = 0; i < restoreAfterStep.size(); i++) restoreAfterStep.set(i, Integer.MAX_VALUE - 2); // put back at the end
        index = 0;
        stepTicks = 0;
        int filler = placeBlocks ? c.fillerCount() : 0;
        plan = PathFinder.find(c, goal, within, BreakPolicy.Purpose.MOVE, filler);
        boolean trapped = (plan.isEmpty() || !plan.reachesGoal()) && isTrapped(c);
        if (dev.aicompanion.ModConfig.get().debugPaths) dev.aicompanion.AiCompanionMod.LOGGER.info("[path] normal plan reaches={} steps={} trapped={}", plan.reachesGoal(), plan.steps().size(), trapped);
        if (trapped) {
            // Boxed in with no way out: allowed to break one ordinary block (and put it back afterwards).
            PathFinder.Plan out = PathFinder.find(c, goal, within, BreakPolicy.Purpose.ESCAPE, filler);
            if (dev.aicompanion.ModConfig.get().debugPaths) dev.aicompanion.AiCompanionMod.LOGGER.info("[path] escape plan reaches={} steps={}", out.reachesGoal(), out.steps().size());
            if (!out.isEmpty()) {
                plan = out;
                escaping = true;
                c.log("need", "Was trapped, so broke out (will put the block back)", false);
            }
        }
        if (dev.aicompanion.ModConfig.get().debugPaths) {
            StringBuilder sb = new StringBuilder();
            for (PathFinder.Step st : plan.steps()) sb.append(st.kind()).append('@').append(st.pos().toShortString()).append(st.breaks().isEmpty() ? "" : "!" + st.breaks().size()).append(' ');
            dev.aicompanion.AiCompanionMod.LOGGER.info("[path] {} from {} to {}: reaches={} escaping={} steps: {}", c.getCharacterName(), c.getBlockPos().toShortString(),
                    goal, plan.reachesGoal(), escaping, sb);
        }
        if (plan.isEmpty()) return fail("couldn't find a way there");
        return Status.RUNNING;
    }

    /** Can't reach any spot a few blocks away without breaking protected blocks. */
    private static boolean isTrapped(CompanionEntity c) {
        int[][] dirs = {{8, 0}, {-8, 0}, {0, 8}, {0, -8}};
        for (int[] d : dirs) {
            PathFinder.Plan probe = PathFinder.find(c, c.getPos().add(d[0], 0, d[1]), 2.5, BreakPolicy.Purpose.MOVE, 0);
            if (probe.reachesGoal()) return false;
        }
        return true;
    }

    /** Once it's through (two blocks past), puts escape blocks back as they were. */
    private void restoreEscapeBlocks(CompanionEntity c, boolean force) {
        for (int i = toRestore.size() - 1; i >= 0; i--) {
            BlockPos p = toRestore.get(i);
            if (!force && (index <= restoreAfterStep.get(i) + 1 || c.getBlockPos().getSquaredDistance(p) < 2 * 2)) continue;
            if (c.getBoundingBox().intersects(new net.minecraft.util.math.Box(p))) continue;
            BlockState state = restoreStates.get(i);
            if (dev.aicompanion.ModConfig.get().debugPaths) dev.aicompanion.AiCompanionMod.LOGGER.info("[path] restoring {} at {} force={} replaceable={} have={}",
                    state.getBlock(), p.toShortString(), force, c.getWorld().getBlockState(p).isReplaceable(), c.count(state.getBlock().asItem()));
            if (c.getWorld().getBlockState(p).isReplaceable() && c.count(state.getBlock().asItem()) > 0) {
                c.placeBlock(p, state);
                var owners = dev.aicompanion.world.BlockOwnership.get(c.serverWorld());
                String original = restoreOwners.get(i);
                if (original == null) owners.clear(p);
                else owners.set(p, original);
                dev.aicompanion.world.BuildAwareness.invalidate(p);
                c.log("note", "Put back the " + state.getBlock().getName().getString() + " it broke to get out", false);
            }
            toRestore.remove(i);
            restoreStates.remove(i);
            restoreAfterStep.remove(i);
            restoreOwners.remove(i);
        }
    }

    private Status fail(String why) {
        if (dev.aicompanion.ModConfig.get().debugPaths) dev.aicompanion.AiCompanionMod.LOGGER.info("[path] failed: {}", why);
        failure = why;
        return Status.FAILED;
    }
}
