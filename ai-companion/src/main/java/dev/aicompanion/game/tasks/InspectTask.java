package dev.aicompanion.game.tasks;

import dev.aicompanion.entity.CompanionEntity;
import dev.aicompanion.world.BuildAwareness;
import net.minecraft.util.math.BlockPos;

/** Walks over to a build, looks around it for a moment, and describes it. */
public class InspectTask extends Task {
    private final BlockPos target;
    private int looking;

    public InspectTask(BlockPos target) {
        this.target = target;
    }

    @Override
    public boolean isMajor() {
        return false;
    }

    @Override
    public String describe() {
        return "looking at a build";
    }

    @Override
    protected Result step(CompanionEntity c) {
        if (c.getBlockPos().getSquaredDistance(target) > 10 * 10) {
            Move move = approach(c, target, 8);
            if (move == Move.FAILED && c.getBlockPos().getSquaredDistance(target) > 24 * 24) return fail("Couldn't get close enough to see it.");
            if (move != Move.FAILED) return null;
        }
        // Take a moment to look it over, glancing around.
        c.getLookControl().lookAt(target.getX() + 0.5 + Math.sin(looking / 10.0) * 4, target.getY() + 2, target.getZ() + 0.5);
        if (++looking < 60) return null;
        return ok(BuildAwareness.describe(c.serverWorld(), target));
    }
}
