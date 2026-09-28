package dev.aicompanion.game.tasks;

import dev.aicompanion.entity.CompanionEntity;
import net.minecraft.util.math.Vec3d;

public class GoToTask extends Task {
    private final Vec3d target;
    private final String label;
    private final double within;

    public GoToTask(Vec3d target, String label, double within) {
        this.target = target;
        this.label = label;
        this.within = within;
    }

    @Override
    public String describe() {
        return "walking to " + label;
    }

    @Override
    protected Result step(CompanionEntity c) {
        return switch (approach(c, target, within)) {
            case ARRIVED -> ok("Arrived at " + label + ".");
            case FAILED -> fail("Couldn't find a way to " + label + "; got as close as " + (int) c.getPos().distanceTo(target) + " blocks.");
            case MOVING -> ticks > 20 * 180 ? fail("Took too long walking to " + label + ".") : null;
        };
    }
}
