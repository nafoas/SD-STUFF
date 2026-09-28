package dev.aicompanion.game.tasks;

import dev.aicompanion.ai.CompanionBrain;
import dev.aicompanion.ai.CompanionMemory;
import dev.aicompanion.entity.CompanionEntity;
import dev.aicompanion.game.Ids;
import dev.aicompanion.world.BreakPolicy;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.WallTorchBlock;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Its mine, the way a player runs one: a 1-wide staircase down from an entrance near home (torches every few
 * steps), then at the right depth for what it's after, a main tunnel with side branches every 3 blocks. Ore showing
 * in the walls gets dug out. Progress is remembered, so each trip continues where the last one stopped.
 */
public class MineTask extends Task {
    private final String target;
    private final int wantedY;
    private final int maxBlocks;
    private CompanionMemory.Mine mine;
    private int dug;
    private int oresFound;
    private final Deque<BlockPos> toDig = new ArrayDeque<>();
    @Nullable private BlockPos standAt;
    private boolean walkedIn;
    private String stopReason = "";

    public MineTask(String target, int minutes) {
        this.target = target.toLowerCase(Locale.ROOT);
        this.wantedY = depthFor(this.target);
        this.maxBlocks = Math.max(10, Math.min(200, minutes * 25));
    }

    /** Good depths in 1.20.1 for each ore. */
    public static int depthFor(String target) {
        if (target.contains("diamond") || target.contains("redstone")) return -54;
        if (target.contains("gold")) return -16;
        if (target.contains("lapis")) return 0;
        if (target.contains("iron")) return 16;
        if (target.contains("coal") || target.contains("copper")) return 48;
        return Integer.MIN_VALUE; // stone: just below the surface
    }

    @Override
    public String describe() {
        return "mining" + (target.isBlank() || target.equals("stone") ? "" : " for " + target) + " (" + dug + " blocks dug)";
    }

    @Override
    protected Result step(CompanionEntity c) {
        CompanionBrain brain = c.brain();
        if (brain == null) return fail("No memory.");
        if (mine == null) {
            mine = findOrStartMine(c, brain.memory());
            if (mine == null) return fail("Couldn't find a good spot for a mine entrance near home.");
            brain.saveLater();
        }
        if (ticks > 20 * 60 * 6) return finish("ran out of time");
        if (dug >= maxBlocks) return finish("dug enough for now");
        if (freeSlots(c) <= 1) return finish("bag is full");
        if (c.bestToolFor(Blocks.STONE.getDefaultState()).getMiningSpeedMultiplier(Blocks.STONE.getDefaultState()) <= 1.0f) {
            return finish("needs a pickaxe");
        }

        // First walk to where the mine currently ends (down its own staircase).
        if (!walkedIn) {
            BlockPos resume = mine.tunnelStarted ? new BlockPos(mine.headX, mine.headY, mine.headZ) : new BlockPos(mine.stairX, mine.stairY, mine.stairZ);
            Move move = approach(c, resume, 1.5);
            if (move == Move.FAILED) return finish("couldn't get back down into the mine (" + lastFailure + ")");
            if (move == Move.MOVING) return null;
            walkedIn = true;
        }

        // Dig out whatever the current step needs, one block at a time.
        while (!toDig.isEmpty()) {
            BlockPos p = toDig.peek();
            BlockState s = c.getWorld().getBlockState(p);
            if (s.isAir() || !s.getFluidState().isEmpty() && s.getCollisionShape(c.getWorld(), p).isEmpty()) {
                toDig.poll();
                continue;
            }
            if (lavaOrWaterNear(c, p)) return finish("hit lava or water; sealed that way off");
            if (!BreakPolicy.allowed(c, p, BreakPolicy.Purpose.GATHER)) {
                toDig.clear();
                return finish("ran into something it shouldn't dig through");
            }
            if (!c.canReach(p)) {
                if (approach(c, p, 3.5) == Move.FAILED) toDig.poll();
                return null;
            }
            c.getNavigation().stop();
            boolean ore = Ids.name(s.getBlock()).endsWith("_ore");
            if (c.mineStep(p)) {
                toDig.poll();
                dug++;
                if (ore) oresFound++;
            }
            return null;
        }
        if (standAt != null) {
            Move move = approach(c, standAt, 0.8);
            if (move == Move.MOVING) return null;
            standAt = null;
            digVisibleOre(c);
            if (!toDig.isEmpty()) return null;
        }
        planNextStep(c);
        brain.saveLater();
        return null;
    }

    private void planNextStep(CompanionEntity c) {
        Direction dir = Direction.byName(mine.direction);
        if (dir == null) dir = Direction.NORTH;
        int goalY = wantedY == Integer.MIN_VALUE ? mine.entranceY - 6 : wantedY;
        if (!mine.tunnelStarted && mine.stairY > goalY && mine.stairY > c.getWorld().getBottomY() + 6) {
            // Next stair step: one forward, one down, with headroom.
            BlockPos from = new BlockPos(mine.stairX, mine.stairY, mine.stairZ);
            BlockPos next = from.offset(dir).down();
            toDig.add(next.up(2));
            toDig.add(next.up());
            toDig.add(next);
            standAt = next;
            mine.stairX = next.getX();
            mine.stairY = next.getY();
            mine.stairZ = next.getZ();
            if (++mine.stepsSinceTorch >= 6 && placeTorch(c, from, dir)) mine.stepsSinceTorch = 0;
            return;
        }
        if (!mine.tunnelStarted) {
            mine.tunnelStarted = true;
            mine.headX = mine.stairX;
            mine.headY = mine.stairY;
            mine.headZ = mine.stairZ;
        }
        // Main tunnel forward; every 3 blocks a pair of side branches, 6 long.
        BlockPos head = new BlockPos(mine.headX, mine.headY, mine.headZ);
        BlockPos next = head.offset(dir);
        toDig.add(next);
        toDig.add(next.up());
        mine.tunnelLength++;
        if (mine.tunnelLength % 3 == 0) {
            for (Direction side : new Direction[]{dir.rotateYClockwise(), dir.rotateYCounterclockwise()}) {
                BlockPos b = next;
                for (int i = 0; i < 6; i++) {
                    b = b.offset(side);
                    toDig.add(b);
                    toDig.add(b.up());
                }
            }
            mine.branches += 2;
        }
        standAt = next;
        mine.headX = next.getX();
        mine.headY = next.getY();
        mine.headZ = next.getZ();
        if (++mine.stepsSinceTorch >= 8 && placeTorch(c, head, dir)) mine.stepsSinceTorch = 0;
    }

    /** Ore showing in the walls, floor or ceiling around it gets dug too. */
    private void digVisibleOre(CompanionEntity c) {
        BlockPos feet = c.getBlockPos();
        for (BlockPos p : BlockPos.iterate(feet.add(-2, -1, -2), feet.add(2, 2, 2))) {
            BlockState s = c.getWorld().getBlockState(p);
            String name = Ids.name(s.getBlock());
            if ((name.endsWith("_ore") || name.equals("ancient_debris")) && c.canReach(p) && exposed(c, p)
                    && CompanionEntity.canHarvestWith(c.bestToolFor(s), s)) {
                toDig.add(p.toImmutable());
            }
        }
    }

    private static boolean exposed(CompanionEntity c, BlockPos p) {
        for (Direction d : Direction.values()) if (c.getWorld().getBlockState(p.offset(d)).isAir()) return true;
        return false;
    }

    private boolean placeTorch(CompanionEntity c, BlockPos at, Direction facing) {
        if (c.count(Items.TORCH) == 0) return false;
        Direction wallSide = facing.rotateYClockwise();
        BlockPos spot = at.up();
        BlockPos wall = spot.offset(wallSide);
        if (!c.getWorld().getBlockState(spot).isAir() || !c.getWorld().getBlockState(wall).isSolidBlock(c.getWorld(), wall)) return false;
        return c.placeBlock(spot, Blocks.WALL_TORCH.getDefaultState().with(WallTorchBlock.FACING, wallSide.getOpposite()));
    }

    private static boolean lavaOrWaterNear(CompanionEntity c, BlockPos p) {
        for (Direction d : Direction.values()) if (!c.getWorld().getFluidState(p.offset(d)).isEmpty()) return true;
        return false;
    }

    private static int freeSlots(CompanionEntity c) {
        int n = 0;
        for (int i = 0; i < c.getInventory().size(); i++) if (c.getInventory().getStack(i).isEmpty()) n++;
        return n;
    }

    @Nullable
    private CompanionMemory.Mine findOrStartMine(CompanionEntity c, CompanionMemory m) {
        String dim = c.getWorld().getRegistryKey().getValue().toString();
        for (CompanionMemory.Mine existing : m.mines) {
            if (!existing.dimension.equals(dim)) continue;
            // An existing mine that's already below the working depth gets a new level: continue the stairs from the tunnel start.
            int goalY = wantedY == Integer.MIN_VALUE ? existing.entranceY - 6 : wantedY;
            if (existing.tunnelStarted && existing.headY > goalY + 4) {
                existing.tunnelStarted = false;
                existing.tunnelLength = 0;
            }
            return existing;
        }
        // Start one near home, heading away from it, on natural ground.
        CompanionMemory.Location home = m.home();
        BlockPos base = home != null && home.dimension.equals(dim) ? new BlockPos(home.x, home.y, home.z) : c.getBlockPos();
        Direction away = home != null ? Direction.getFacing(c.getX() - home.x, 0, c.getZ() - home.z) : c.getHorizontalFacing();
        if (away.getAxis().isVertical()) away = Direction.NORTH;
        for (int dist = 6; dist <= 20; dist += 2) {
            BlockPos probe = base.offset(away, dist);
            BlockPos ground = c.getWorld().getTopPosition(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, probe);
            BlockPos below = ground.down();
            if (!c.getWorld().getBlockState(below).isOpaqueFullCube(c.getWorld(), below)) continue;
            if (!BreakPolicy.allowed(c, below, BreakPolicy.Purpose.GATHER)) continue;
            CompanionMemory.Mine mine = new CompanionMemory.Mine();
            mine.name = "mine";
            mine.dimension = dim;
            mine.entranceX = ground.getX();
            mine.entranceY = ground.getY();
            mine.entranceZ = ground.getZ();
            mine.direction = away.asString();
            mine.stairX = ground.getX();
            mine.stairY = ground.getY();
            mine.stairZ = ground.getZ();
            m.mines.add(mine);
            CompanionMemory.Location loc = new CompanionMemory.Location(dim, ground.getX(), ground.getY(), ground.getZ());
            loc.type = "mine";
            loc.note = "staircase heading " + away.asString();
            m.places.put("mine", loc);
            c.log("note", "Started a mine at " + ground.toShortString() + ", heading " + away.asString(), false);
            return mine;
        }
        return null;
    }

    private Result finish(String reason) {
        String where = mine == null ? "" : " The mine now goes " + (mine.tunnelStarted ? "down to y=" + mine.headY + " with a " + mine.tunnelLength + "-block tunnel and "
                + mine.branches + " branches" : "down to y=" + mine.stairY) + ".";
        String msg = "Dug " + dug + " blocks" + (oresFound > 0 ? ", including " + oresFound + " ore" : "") + " (" + reason + ")." + where;
        return dug > 0 ? ok(msg) : fail(msg);
    }
}
