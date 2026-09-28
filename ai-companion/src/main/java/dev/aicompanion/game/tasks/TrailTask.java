package dev.aicompanion.game.tasks;

import dev.aicompanion.ai.CompanionBrain;
import dev.aicompanion.ai.CompanionMemory;
import dev.aicompanion.entity.CompanionEntity;
import dev.aicompanion.game.path.PathFinder;
import dev.aicompanion.world.BreakPolicy;
import net.minecraft.block.Blocks;
import net.minecraft.item.ShovelItem;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Lays a path between two of its places (grass turned into a dirt path with a shovel) and remembers it, so future
 * trips between those places follow the path.
 */
public class TrailTask extends Task {
    private final String fromName, toName;
    private CompanionMemory.Location from, to;
    private List<BlockPos> route;
    private int index;
    private boolean atStart;
    private int paved;
    private final List<int[]> points = new ArrayList<>();

    public TrailTask(String from, String to) {
        this.fromName = from.toLowerCase();
        this.toName = to.toLowerCase();
    }

    @Override
    public String describe() {
        return "laying a path from " + fromName + " to " + toName;
    }

    @Override
    protected Result step(CompanionEntity c) {
        CompanionBrain brain = c.brain();
        if (brain == null) return fail("No memory.");
        if (from == null) {
            from = brain.memory().places.get(fromName);
            to = brain.memory().places.get(toName);
            if (from == null || to == null) return fail("Doesn't know both places (" + fromName + ", " + toName + ").");
            if (!from.dimension.equals(to.dimension)) return fail("Those places are in different dimensions.");
        }
        if (ticks > 20 * 60 * 5) return finish(c, "ran out of time");
        if (!atStart) {
            Move move = approach(c, new BlockPos(from.x, from.y, from.z), 2.0);
            if (move == Move.FAILED) return fail("Couldn't get to " + fromName + ".");
            if (move == Move.MOVING) return null;
            atStart = true;
            PathFinder.Plan plan = PathFinder.find(c, Vec3d.ofBottomCenter(new BlockPos(to.x, to.y, to.z)), 2.0, BreakPolicy.Purpose.MOVE, 0);
            if (!plan.reachesGoal()) return fail("There's no walkable way from " + fromName + " to " + toName + " to lay a path along.");
            route = new ArrayList<>();
            for (PathFinder.Step st : plan.steps()) route.add(st.pos());
            if (!holdShovel(c)) return fail("Needs a shovel to make a path.");
        }
        if (index >= route.size()) return finish(c, "done");
        BlockPos feet = route.get(index);
        Move move = approach(c, feet, 1.2);
        if (move == Move.FAILED) {
            index++;
            return null;
        }
        if (move == Move.MOVING && c.getBlockPos().getSquaredDistance(feet) > 3 * 3) return null;
        BlockPos ground = feet.down();
        if (c.getWorld().getBlockState(ground).isOf(Blocks.GRASS_BLOCK) && c.getWorld().getBlockState(feet).isAir()
                && BreakPolicy.allowed(c, ground, BreakPolicy.Purpose.GATHER)) {
            c.getLookControl().lookAt(ground.toCenterPos());
            c.swingHand(net.minecraft.util.Hand.MAIN_HAND);
            c.getWorld().setBlockState(ground, Blocks.DIRT_PATH.getDefaultState());
            c.getMainHandStack().damage(1, c, e -> e.sendEquipmentBreakStatus(net.minecraft.entity.EquipmentSlot.MAINHAND));
            paved++;
        }
        if (index % 3 == 0 || index == route.size() - 1) points.add(new int[]{feet.getX(), feet.getY(), feet.getZ()});
        index++;
        return null;
    }

    private Result finish(CompanionEntity c, String reason) {
        CompanionBrain brain = c.brain();
        if (brain != null && points.size() >= 2) {
            CompanionMemory.Trail t = new CompanionMemory.Trail();
            t.from = fromName;
            t.to = toName;
            t.dimension = from.dimension;
            t.points = new ArrayList<>(points);
            brain.memory().trails.removeIf(x -> x.from.equals(fromName) && x.to.equals(toName) || x.from.equals(toName) && x.to.equals(fromName));
            brain.memory().trails.add(t);
            brain.saveLater();
        }
        return paved > 0 || points.size() >= 2 ? ok("Laid a path from " + fromName + " to " + toName + " (" + paved + " blocks of path, " + reason + ").")
                : fail("Couldn't lay the path (" + reason + ").");
    }

    private static boolean holdShovel(CompanionEntity c) {
        if (c.getMainHandStack().getItem() instanceof ShovelItem) return true;
        for (int i = 0; i < c.getInventory().size(); i++) {
            if (c.getInventory().getStack(i).getItem() instanceof ShovelItem) {
                c.hold(c.getInventory().getStack(i));
                return true;
            }
        }
        return false;
    }
}
