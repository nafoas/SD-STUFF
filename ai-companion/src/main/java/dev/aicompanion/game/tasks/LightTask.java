package dev.aicompanion.game.tasks;

import dev.aicompanion.entity.CompanionEntity;
import dev.aicompanion.world.BreakPolicy;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;

import java.util.ArrayList;
import java.util.List;

/** Places torches on dark floor spots around it (so monsters don't spawn there), spaced out like a player would. */
public class LightTask extends Task {
    private final int radius;
    private List<BlockPos> spots;
    private final List<BlockPos> placed = new ArrayList<>();
    private int index;

    public LightTask(int radius) {
        this.radius = Math.max(4, Math.min(16, radius));
    }

    @Override
    public boolean isMajor() {
        return false;
    }

    @Override
    public String describe() {
        return "lighting up the area";
    }

    @Override
    protected Result step(CompanionEntity c) {
        if (spots == null) {
            spots = new ArrayList<>();
            BlockPos o = c.getBlockPos();
            for (BlockPos p : BlockPos.iterate(o.add(-radius, -3, -radius), o.add(radius, 3, radius))) {
                if (!c.getWorld().getBlockState(p).isAir()) continue;
                BlockPos below = p.down();
                if (!c.getWorld().getBlockState(below).isSideSolidFullSquare(c.getWorld(), below, net.minecraft.util.math.Direction.UP)) continue;
                if (c.getWorld().getLightLevel(LightType.BLOCK, p) >= 8) continue;
                // Its own builds and natural ground only: it doesn't decorate other people's homes.
                if (!BreakPolicy.allowed(c, below, BreakPolicy.Purpose.GATHER)) continue;
                spots.add(p.toImmutable());
            }
            spots.sort((a, b) -> Double.compare(a.getSquaredDistance(o), b.getSquaredDistance(o)));
        }
        if (ticks > 20 * 90) return finish("ran out of time");
        while (index < spots.size()) {
            if (c.count(Items.TORCH) == 0) return finish("out of torches");
            BlockPos p = spots.get(index);
            if (placed.stream().anyMatch(t -> t.getSquaredDistance(p) < 6 * 6) || !c.getWorld().getBlockState(p).isAir()) {
                index++;
                continue;
            }
            if (!c.canReach(p)) {
                if (approach(c, p, 3) == Move.FAILED) index++;
                return null;
            }
            c.placeBlock(p, Blocks.TORCH.getDefaultState());
            placed.add(p);
            index++;
            return null;
        }
        return finish("done");
    }

    private Result finish(String why) {
        return placed.isEmpty() ? (why.equals("done") ? ok("Everything around is already lit.") : fail("Placed no torches (" + why + ")."))
                : ok("Placed " + placed.size() + " torches (" + why + ").");
    }
}
