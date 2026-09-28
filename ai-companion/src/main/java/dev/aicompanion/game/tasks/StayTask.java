package dev.aicompanion.game.tasks;

import dev.aicompanion.entity.CompanionEntity;
import net.minecraft.util.math.BlockPos;

/** Stays in one spot (guarding it: reflexes still fight anything that comes close). */
public class StayTask extends Task {
    private final BlockPos spot;

    public StayTask(BlockPos spot) {
        this.spot = spot;
    }

    @Override
    public String describe() {
        return "staying at " + spot.toShortString();
    }

    @Override
    public boolean isContinuous() {
        return true;
    }

    @Override
    protected Result step(CompanionEntity c) {
        if (c.getBlockPos().getSquaredDistance(spot) > 4 && (ticks % 20 == 0 || c.getNavigation().isIdle())) {
            c.getNavigation().startMovingTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 1.0);
        }
        return null;
    }
}
