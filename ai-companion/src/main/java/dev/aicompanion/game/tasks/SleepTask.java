package dev.aicompanion.game.tasks;

import dev.aicompanion.entity.CompanionEntity;
import dev.aicompanion.world.BlockOwnership;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.BedPart;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/** Goes to its own bed and sleeps through the night (wakes if hurt or at sunrise). */
public class SleepTask extends Task {
    private BlockPos bed;

    @Override
    public boolean isMajor() {
        return false;
    }

    @Override
    public String describe() {
        return "going to bed";
    }

    @Nullable
    public static BlockPos findOwnBed(CompanionEntity c) {
        String self = BlockOwnership.companionOwner(c.getCharacterId());
        BlockOwnership owners = BlockOwnership.get(c.serverWorld());
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.iterate(c.getBlockPos().add(-48, -12, -48), c.getBlockPos().add(48, 12, 48))) {
            BlockState s = c.getWorld().getBlockState(p);
            if (s.getBlock() instanceof BedBlock && s.get(Properties.BED_PART) == BedPart.HEAD && self.equals(owners.owner(p))
                    && !s.get(Properties.OCCUPIED)) {
                double d = p.getSquaredDistance(c.getBlockPos());
                if (d < bestD) {
                    bestD = d;
                    best = p.toImmutable();
                }
            }
        }
        return best;
    }

    @Override
    protected Result step(CompanionEntity c) {
        if (c.isSleeping()) {
            if (c.getWorld().isDay() || c.hurtTime > 0 || ticks > 20 * 60 * 12) {
                c.wakeUp();
                return ok("Slept in its bed and woke up" + (c.getWorld().isDay() ? " at sunrise." : "."));
            }
            return null;
        }
        if (c.getWorld().isDay()) return ok("It's daytime; no need to sleep.");
        if (bed == null) {
            bed = findOwnBed(c);
            if (bed == null) return fail("Doesn't have a bed of its own nearby (3 wool + 3 planks make one; place it at home).");
        }
        if (c.getBlockPos().getSquaredDistance(bed) > 2.5 * 2.5) {
            if (approach(c, bed, 2.0) == Move.FAILED) return fail("Couldn't get to its bed.");
            return null;
        }
        c.getNavigation().stop();
        c.sleep(bed);
        c.log("note", "Went to sleep in its bed", false);
        return null;
    }

    @Override
    public void stop(CompanionEntity c) {
        super.stop(c);
        if (c.isSleeping()) c.wakeUp();
    }
}
