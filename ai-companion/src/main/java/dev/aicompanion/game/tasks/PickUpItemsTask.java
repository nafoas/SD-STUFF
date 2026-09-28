package dev.aicompanion.game.tasks;

import dev.aicompanion.entity.CompanionEntity;
import net.minecraft.entity.ItemEntity;

import java.util.Comparator;

/** Walks over every dropped item nearby to pick it up (e.g. its things after dying, or drops from a fight). */
public class PickUpItemsTask extends Task {
    private final int radius;
    private int picked;
    private ItemEntity target;
    private final java.util.Set<java.util.UUID> unreachable = new java.util.HashSet<>();

    public PickUpItemsTask(int radius) {
        this.radius = Math.max(2, Math.min(24, radius));
    }

    @Override
    public boolean isMajor() {
        return false;
    }

    @Override
    public String describe() {
        return "picking up items";
    }

    @Override
    protected Result step(CompanionEntity c) {
        if (ticks > 20 * 90) return picked > 0 ? ok("Picked up " + picked + " dropped stacks (ran out of time).") : fail("Couldn't get to the items.");
        if (target != null && !target.isAlive()) {
            picked++; // walked over it and it went into the bag
            target = null;
        }
        if (target == null) {
            target = c.getWorld().getEntitiesByClass(ItemEntity.class, c.getBoundingBox().expand(radius), e -> e.isAlive() && !unreachable.contains(e.getUuid()))
                    .stream().min(Comparator.comparingDouble(c::squaredDistanceTo)).orElse(null);
            resetMovement();
            if (target == null) return picked > 0 ? ok("Picked up " + picked + " dropped stacks.") : ok("No dropped items within " + radius + " blocks.");
        }
        if (approach(c, target.getPos(), 0.8) == Move.FAILED) {
            unreachable.add(target.getUuid()); // leave it where it is and try the next one
            target = null;
        }
        return null;
    }
}
