package dev.aicompanion.game.tasks;

import dev.aicompanion.entity.CompanionEntity;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Digs a 1x2 tunnel forward, or a staircase down/up, collecting everything it breaks.
 * This is how the companion gets underground to ores. Stops before breaking into lava or water.
 */
public class DigTask extends Task {
    public enum Mode { FORWARD, DOWN, UP }

    private final Direction direction;
    private final int length;
    private final Mode mode;
    private BlockPos standing;
    private int progress;
    private List<BlockPos> toBreak = List.of();
    private BlockPos nextStand;

    public DigTask(Direction direction, int length, Mode mode) {
        this.direction = direction;
        this.length = Math.max(1, Math.min(64, length));
        this.mode = mode;
    }

    @Override
    public String describe() {
        return (mode == Mode.FORWARD ? "digging a tunnel " : mode == Mode.DOWN ? "digging stairs down " : "digging stairs up ")
                + direction.asString() + " (" + progress + "/" + length + ")";
    }

    @Override
    protected Result step(CompanionEntity c) {
        if (standing == null) standing = c.getBlockPos();
        if (progress >= length) return ok("Dug " + progress + " blocks " + direction.asString() + ", now at y=" + c.getBlockY() + ".");
        if (ticks > 20 * 60 * 8) return ok("Dug " + progress + " blocks before running out of time.");

        if (toBreak.isEmpty() && nextStand == null) planNextStep();
        for (BlockPos pos : toBreak) {
            if (dangerousNeighbor(c, pos)) {
                return progress > 0 ? ok("Stopped after " + progress + " blocks: found lava or water ahead at y=" + pos.getY() + ".")
                        : fail("There's lava or water right ahead.");
            }
        }
        for (BlockPos pos : toBreak) {
            BlockState state = c.getWorld().getBlockState(pos);
            if (state.isAir() || state.getCollisionShape(c.getWorld(), pos).isEmpty() && state.getFluidState().isEmpty()) continue;
            if (state.getHardness(c.getWorld(), pos) < 0) return fail("Hit an unbreakable block.");
            c.equipBestToolFor(state);
            if (!CompanionEntity.canHarvestWith(c.getMainHandStack(), state) && state.getHardness(c.getWorld(), pos) > 2.5f) {
                return fail("Hit " + state.getBlock().getName().getString() + " and needs a better pickaxe.");
            }
            c.mineStep(pos);
            return null;
        }
        // Path is clear: step into it.
        Move move = approach(c, nextStand, 0.6);
        if (move == Move.ARRIVED || c.getBlockPos().equals(nextStand)) {
            standing = nextStand;
            progress++;
            toBreak = List.of();
            nextStand = null;
            resetMovement();
        } else if (move == Move.FAILED) {
            if (mode == Mode.UP && c.getBlockPos().equals(standing)) {
                return fail("Couldn't climb the stairs (need a block to step on).");
            }
            return progress > 0 ? ok("Dug " + progress + " blocks, then got stuck.") : fail("Couldn't move into the tunnel.");
        }
        return null;
    }

    private void planNextStep() {
        List<BlockPos> list = new ArrayList<>();
        BlockPos ahead = standing.offset(direction);
        switch (mode) {
            case FORWARD -> {
                list.add(ahead.up());
                list.add(ahead);
                nextStand = ahead;
            }
            case DOWN -> {
                list.add(ahead.up());
                list.add(ahead);
                list.add(ahead.down());
                nextStand = ahead.down();
            }
            case UP -> {
                list.add(standing.up(2));
                list.add(ahead.up(2));
                list.add(ahead.up());
                nextStand = ahead.up();
            }
        }
        toBreak = list;
    }

    private static boolean dangerousNeighbor(CompanionEntity c, BlockPos pos) {
        for (Direction d : Direction.values()) {
            FluidState fluid = c.getWorld().getFluidState(pos.offset(d));
            if (!fluid.isEmpty()) return true;
        }
        return !c.getWorld().getFluidState(pos).isEmpty();
    }
}
