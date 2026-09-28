package dev.aicompanion.game.tasks;

import dev.aicompanion.entity.CompanionEntity;
import dev.aicompanion.game.Ids;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;

import net.minecraft.util.math.Vec3d;

import java.util.UUID;

/** Walks to a player and hands over items. */
public class GiveTask extends Task {
    private final UUID player;
    private final String name;
    private final Item item;
    private final int count;

    public GiveTask(UUID player, String name, Item item, int count) {
        this.player = player;
        this.name = name;
        this.item = item;
        this.count = Math.max(1, count);
    }

    @Override
    public boolean isMajor() {
        return false;
    }

    @Override
    public String describe() {
        return "bringing " + count + " " + Ids.name(item) + " to " + name;
    }

    @Override
    protected Result step(CompanionEntity c) {
        ServerPlayerEntity target = c.getServer().getPlayerManager().getPlayer(player);
        if (target == null || target.getWorld() != c.getWorld()) return fail(name + " isn't around.");
        if (c.count(item) < 1) return fail("Doesn't have any " + Ids.name(item) + ".");
        if (c.squaredDistanceTo(target) > 2.5 * 2.5) {
            if (approach(c, target.getPos(), 2.5) == Move.FAILED) {
                // Can't walk up to them (e.g. they're on a roof): throw the items if they're close enough.
                if (c.squaredDistanceTo(target) < 12 * 12 && c.canSee(target)) return toss(c, target);
                return fail("Couldn't reach " + name + ".");
            }
            return null;
        }
        int amount = c.remove(item, Math.min(count, c.count(item)));
        ItemStack stack = new ItemStack(item, amount);
        c.swingHand(net.minecraft.util.Hand.MAIN_HAND);
        if (!target.getInventory().insertStack(stack)) target.dropItem(stack, false);
        else if (!stack.isEmpty()) target.dropItem(stack, false);
        return ok("Gave " + amount + " " + Ids.name(item) + " to " + name + (amount < count ? " (all it had)" : "") + ".");
    }

    private Result toss(CompanionEntity c, ServerPlayerEntity target) {
        int amount = c.remove(item, Math.min(count, c.count(item)));
        c.getLookControl().lookAt(target, 30, 30);
        c.swingHand(net.minecraft.util.Hand.MAIN_HAND);
        ItemEntity thrown = new ItemEntity(c.getWorld(), c.getX(), c.getEyeY() - 0.3, c.getZ(), new ItemStack(item, amount));
        Vec3d delta = target.getEyePos().subtract(thrown.getPos());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        // Rough ballistic throw: enough speed to cover the distance, aimed a bit high.
        thrown.setVelocity(delta.x * 0.1, delta.y * 0.1 + horizontal * 0.02 + 0.25, delta.z * 0.1);
        thrown.setPickupDelay(10);
        c.getWorld().spawnEntity(thrown);
        return ok("Couldn't walk up to " + name + ", so threw them " + amount + " " + Ids.name(item) + ".");
    }
}
