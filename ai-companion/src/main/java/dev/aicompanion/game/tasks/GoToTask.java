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

    private java.util.List<net.minecraft.util.math.BlockPos> trailFor(CompanionEntity c) {
        java.util.List<net.minecraft.util.math.BlockPos> out = new java.util.ArrayList<>();
        var brain = c.brain();
        if (brain == null) return out;
        String dim = c.getWorld().getRegistryKey().getValue().toString();
        for (var t : brain.memory().trails) {
            if (!t.dimension.equals(dim) || t.points.size() < 2) continue;
            int[] a = t.points.get(0), b = t.points.get(t.points.size() - 1);
            boolean forward = near(c.getPos(), a, 16) && near(target, b, 16);
            boolean backward = near(c.getPos(), b, 16) && near(target, a, 16);
            if (!forward && !backward) continue;
            for (int[] p : t.points) out.add(new net.minecraft.util.math.BlockPos(p[0], p[1], p[2]));
            if (backward) java.util.Collections.reverse(out);
            return out;
        }
        return out;
    }

    private static boolean near(Vec3d v, int[] p, double r) {
        return v.squaredDistanceTo(p[0] + 0.5, p[1], p[2] + 0.5) < r * r;
    }

    @Override
    public boolean isMajor() {
        return false;
    }

    @Override
    public String describe() {
        return "walking to " + label;
    }

    /** Waypoints of one of its own paths, when the trip runs between the two places the path connects. */
    private java.util.List<net.minecraft.util.math.BlockPos> trail;
    private int trailIndex;

    @Override
    protected Result step(CompanionEntity c) {
        if (trail == null) {
            trail = trailFor(c);
            if (!trail.isEmpty()) c.log("note", "Following its path to " + label, false);
        }
        while (trailIndex < trail.size()) {
            net.minecraft.util.math.BlockPos wp = trail.get(trailIndex);
            Move move = approach(c, wp, 1.5);
            if (move == Move.MOVING) return null;
            trailIndex++;
            resetMovement();
        }
        return switch (approach(c, target, within)) {
            case ARRIVED -> ok("Arrived at " + label + ".");
            case FAILED -> fail("Couldn't find a way to " + label + "; got as close as " + (int) c.getPos().distanceTo(target) + " blocks.");
            case MOVING -> ticks > 20 * 180 ? fail("Took too long walking to " + label + ".") : null;
        };
    }
}
