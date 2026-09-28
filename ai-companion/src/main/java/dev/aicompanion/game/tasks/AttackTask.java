package dev.aicompanion.game.tasks;

import dev.aicompanion.entity.CompanionEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Hand;

import java.util.Comparator;
import java.util.function.Predicate;

/** Hunts down and fights a number of matching creatures nearby. */
public class AttackTask extends Task {
    private final String label;
    private final Predicate<LivingEntity> matcher;
    private final int wanted;
    private LivingEntity target;
    private int kills;
    private int cooldown;

    public AttackTask(String label, Predicate<LivingEntity> matcher, int wanted) {
        this.label = label;
        this.matcher = matcher;
        this.wanted = Math.max(1, Math.min(20, wanted));
    }

    @Override
    public String describe() {
        return "fighting " + label + " (" + kills + "/" + wanted + ")";
    }

    @Override
    protected Result step(CompanionEntity c) {
        if (kills >= wanted) return ok("Defeated " + kills + " " + label + ".");
        if (ticks > 20 * 120) return kills > 0 ? ok("Defeated " + kills + " " + label + " before giving up.") : fail("Couldn't catch any " + label + ".");
        if (target != null && !target.isAlive()) {
            kills++;
            target = null;
            return null;
        }
        if (target == null) {
            target = c.getWorld().getEntitiesByClass(LivingEntity.class, c.getBoundingBox().expand(24), e -> e != c && e.isAlive() && matcher.test(e))
                    .stream().min(Comparator.comparingDouble(c::squaredDistanceTo)).orElse(null);
            if (target == null) return kills > 0 ? ok("Defeated " + kills + " " + label + "; no more around.") : fail("No " + label + " within 24 blocks.");
            c.equipBestWeapon();
        }
        c.getLookControl().lookAt(target, 30, 30);
        if (c.squaredDistanceTo(target) > 2.4 * 2.4) {
            if (ticks % 10 == 0 || c.getNavigation().isIdle()) c.getNavigation().startMovingTo(target, 1.2);
        } else if (--cooldown <= 0) {
            c.swingHand(Hand.MAIN_HAND);
            c.tryAttack(target);
            cooldown = 12;
        }
        return null;
    }
}
