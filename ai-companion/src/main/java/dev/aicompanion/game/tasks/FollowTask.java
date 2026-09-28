package dev.aicompanion.game.tasks;

import dev.aicompanion.entity.CompanionEntity;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.UUID;

/** Keeps close to a player until replaced by another action. */
public class FollowTask extends Task {
    private final UUID player;
    private final String name;
    private final double distance;

    public FollowTask(UUID player, String name, double distance) {
        this.player = player;
        this.name = name;
        this.distance = distance;
    }

    @Override
    public String describe() {
        return "following " + name;
    }

    @Override
    public boolean isContinuous() {
        return true;
    }

    @Override
    protected Result step(CompanionEntity c) {
        ServerPlayerEntity target = c.getServer().getPlayerManager().getPlayer(player);
        if (target == null) return fail(name + " left the game");
        if (target.getWorld() != c.getWorld()) return fail(name + " went to another dimension");
        double d = c.squaredDistanceTo(target);
        if (d > distance * distance) {
            if (ticks % 10 == 0 || c.getNavigation().isIdle()) c.getNavigation().startMovingTo(target, Math.max(1.0, c.workSpeed()));
        } else if (d < (distance - 1.5) * (distance - 1.5)) {
            c.getNavigation().stop();
        }
        c.getLookControl().lookAt(target, 20, 20);
        return null;
    }
}
