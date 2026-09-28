package dev.aicompanion.game.tasks;

import dev.aicompanion.entity.CompanionEntity;
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
        c.getNavigation().stop();
        c.stopBreaking();
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

    /** Walks toward a target until within the given distance. Detects being stuck. */
    protected Move approach(CompanionEntity c, Vec3d target, double within) {
        if (lastTarget == null || lastTarget.squaredDistanceTo(target) > 1) {
            lastTarget = target;
            bestDistance = Double.MAX_VALUE;
            ticksSinceProgress = 0;
            repathCooldown = 0;
        }
        double distance = c.getPos().distanceTo(target);
        if (distance <= within) {
            c.getNavigation().stop();
            return Move.ARRIVED;
        }
        if (distance < bestDistance - 0.5) {
            bestDistance = distance;
            ticksSinceProgress = 0;
        } else if (++ticksSinceProgress > 100) {
            c.getNavigation().stop();
            lastTarget = null;
            return Move.FAILED;
        }
        if (--repathCooldown <= 0 || c.getNavigation().isIdle()) {
            repathCooldown = 20;
            Path path = c.getNavigation().findPathTo(BlockPos.ofFloored(target), Math.max(0, (int) within - 1));
            if ((path == null || path.getLength() <= 1) && distance < 2.5) {
                // Right next to it (a step down into a tunnel, one block over): just step there.
                c.getMoveControl().moveTo(target.x, target.y, target.z, c.workSpeed());
                return Move.MOVING;
            }
            if (path == null) {
                if (ticksSinceProgress > 40) {
                    lastTarget = null;
                    return Move.FAILED;
                }
            } else {
                c.getNavigation().startMovingAlong(path, c.workSpeed());
            }
        }
        return Move.MOVING;
    }

    protected Move approach(CompanionEntity c, BlockPos target, double within) {
        return approach(c, Vec3d.ofBottomCenter(target), within);
    }

    /** Resets movement tracking (call when switching to a new target of the same kind). */
    protected void resetMovement() {
        lastTarget = null;
    }
}
