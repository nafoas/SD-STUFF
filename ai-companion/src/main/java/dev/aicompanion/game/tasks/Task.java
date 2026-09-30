package dev.aicompanion.game.tasks;

import dev.aicompanion.entity.CompanionEntity;
import dev.aicompanion.game.path.PathFollower;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Something the companion's body is doing, run on the server thread one tick at a time.
 * Tasks are started by the action layer and report a short result back to it when they end.
 */
public abstract class Task {
    public record Result(boolean success, String message) {}

    protected enum Move { ARRIVED, MOVING, FAILED }

    protected int ticks;
    private int repathCooldown;
    private double bestDistance = Double.MAX_VALUE;
    private int ticksSinceProgress;
    private Vec3d lastTarget;
    private PathFollower follower;

    /** Short present-tense description, e.g. "collecting oak_log (3/10)". */
    public abstract String describe();

    /** Runs one tick. Returns null while still running. */
    protected abstract Result step(CompanionEntity c);

    public final Result tick(CompanionEntity c) {
        ticks++;
        return step(c);
    }

    /** Called when the task ends for any reason, including cancellation. */
    public void stop(CompanionEntity c) {
        if (follower != null) follower.finish(c);
        c.getNavigation().stop();
        c.stopBreaking();
    }

    /** Whether getting about may pillar up and bridge gaps with throwaway blocks (not for a stroll). */
    protected boolean placesBlocksToGetAbout() {
        return true;
    }

    /** Major tasks (gathering, building, digging...) prompt a character check-in when they end. */
    public boolean isMajor() {
        return !isContinuous();
    }

    /** Continuous tasks (follow, guard) report success as soon as they start and then keep running. */
    public boolean isContinuous() {
        return false;
    }

    protected static Result ok(String message) {
        return new Result(true, message);
    }

    protected static Result fail(String message) {
        return new Result(false, message);
    }

    /**
     * Walks toward a target until within the given distance. Uses the game's own smooth pathing for ordinary walks,
     * and switches to the companion's pathfinder (breaking through, pillaring, bridging, climbing) when that can't
     * get there or gets stuck.
     */
    protected Move approach(CompanionEntity c, Vec3d target, double within) {
        if (lastTarget == null || lastTarget.squaredDistanceTo(target) > 1) {
            lastTarget = target;
            bestDistance = Double.MAX_VALUE;
            ticksSinceProgress = 0;
            repathCooldown = 0;
            follower = null;
        }
        double distance = c.getPos().distanceTo(target);
        if (distance <= within) {
            c.getNavigation().stop();
            if (follower != null) follower.finish(c);
            follower = null;
            return Move.ARRIVED;
        }
        if (follower != null) {
            PathFollower.Status status = follower.tick(c);
            if (status == PathFollower.Status.ARRIVED) {
                follower = null;
                return c.getPos().distanceTo(target) <= within + 1 ? Move.ARRIVED : Move.MOVING;
            }
            if (status == PathFollower.Status.FAILED) {
                follower.finish(c);
                lastFailure = follower.failure();
                follower = null;
                lastTarget = null;
                return Move.FAILED;
            }
            return Move.MOVING;
        }
        if (distance < bestDistance - 0.5) {
            bestDistance = distance;
            ticksSinceProgress = 0;
        } else if (++ticksSinceProgress > 60) {
            // The easy way isn't working: take the hard way.
            c.getNavigation().stop();
            follower = new PathFollower(target, within, placesBlocksToGetAbout());
            return Move.MOVING;
        }
        if (--repathCooldown <= 0 || c.getNavigation().isIdle()) {
            repathCooldown = 20;
            Path path = c.getNavigation().findPathTo(BlockPos.ofFloored(target), Math.max(0, (int) within - 1));
            if ((path == null || path.getLength() <= 1) && distance < 2.5) {
                // Right next to it (a step down into a tunnel, one block over): just step there.
                c.getMoveControl().moveTo(target.x, target.y, target.z, c.workSpeed());
                return Move.MOVING;
            }
            if (path == null || !path.reachesTarget()) {
                c.getNavigation().stop();
                follower = new PathFollower(target, within, placesBlocksToGetAbout());
            } else {
                c.getNavigation().startMovingAlong(path, c.workSpeed());
            }
        }
        return Move.MOVING;
    }

    /** Why the last approach failed, if the pathfinder said. */
    protected String lastFailure = "";

    protected Move approach(CompanionEntity c, BlockPos target, double within) {
        return approach(c, Vec3d.ofBottomCenter(target), within);
    }

    /** Resets movement tracking (call when switching to a new target of the same kind). */
    protected void resetMovement() {
        lastTarget = null;
        follower = null;
    }
}
