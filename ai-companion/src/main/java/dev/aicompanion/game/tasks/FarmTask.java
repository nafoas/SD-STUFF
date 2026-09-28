package dev.aicompanion.game.tasks;

import dev.aicompanion.ai.CompanionBrain;
import dev.aicompanion.ai.CompanionMemory;
import dev.aicompanion.entity.CompanionEntity;
import dev.aicompanion.game.Ids;
import dev.aicompanion.world.BreakPolicy;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.item.HoeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Its farm, the way a player keeps one: tend (harvest ripe crops, replant, fill gaps), make one near home,
 * or expand it by a row. The farm is remembered, so it always goes back to the same one.
 */
public class FarmTask extends Task {
    public enum Mode { TEND, CREATE, EXPAND }

    private final Mode mode;
    private final int size;
    private CompanionMemory.Farm farm;
    private List<BlockPos> cells;
    private int index;
    private int harvested, planted, tilled;
    private String problem = "";

    public FarmTask(Mode mode, int size) {
        this.mode = mode;
        this.size = Math.max(3, Math.min(9, size));
    }

    @Override
    public String describe() {
        return switch (mode) {
            case TEND -> "tending the farm";
            case CREATE -> "making a farm";
            case EXPAND -> "expanding the farm";
        };
    }

    @Override
    protected Result step(CompanionEntity c) {
        CompanionBrain brain = c.brain();
        if (brain == null) return fail("No memory.");
        CompanionMemory m = brain.memory();
        String dim = c.getWorld().getRegistryKey().getValue().toString();
        if (cells == null) {
            farm = nearestFarm(m, c, dim);
            if (mode == Mode.CREATE || farm == null && mode != Mode.TEND) {
                if (farm != null && mode == Mode.CREATE) return fail("Already has a farm at " + farm.minX + ", " + farm.y + ", " + farm.minZ + "; tend or expand it instead.");
                farm = chooseSite(c, m, dim);
                if (farm == null) return fail("No flat grassy spot near home for a " + size + "x" + size + " farm.");
                m.farms.add(farm);
                CompanionMemory.Location loc = new CompanionMemory.Location(dim, (farm.minX + farm.maxX) / 2, farm.y + 1, (farm.minZ + farm.maxZ) / 2);
                loc.type = "farm";
                loc.note = farm.crop + " farm";
                m.places.put(farm.name, loc);
                brain.log("note", "Started a farm at " + loc.x + ", " + loc.y + ", " + loc.z, false);
                placeWater(c);
            } else if (farm == null) {
                return fail("Doesn't have a farm yet (make one first).");
            } else if (mode == Mode.EXPAND) {
                if (!expand(c)) return fail("No room to expand the farm.");
            }
            cells = new ArrayList<>();
            for (int x = farm.minX; x <= farm.maxX; x++)
                for (int z = farm.minZ; z <= farm.maxZ; z++) {
                    BlockPos soil = new BlockPos(x, farm.y, z);
                    if (!c.getWorld().getBlockState(soil).isOf(Blocks.WATER)) cells.add(soil);
                }
            brain.saveLater();
        }
        if (ticks > 20 * 60 * 4) return finish("ran out of time");
        while (index < cells.size()) {
            BlockPos soil = cells.get(index);
            BlockPos crop = soil.up();
            BlockState soilState = c.getWorld().getBlockState(soil);
            BlockState cropState = c.getWorld().getBlockState(crop);
            boolean ripe = cropState.getBlock() instanceof CropBlock cb && cb.isMature(cropState);
            boolean needsTill = (soilState.isOf(Blocks.GRASS_BLOCK) || soilState.isOf(Blocks.DIRT)) && (cropState.isAir() || cropState.isReplaceable());
            boolean needsSeed = soilState.isOf(Blocks.FARMLAND) && cropState.isAir();
            if (!ripe && !needsTill && !needsSeed) {
                index++;
                continue;
            }
            if (!c.canReach(soil)) {
                if (approach(c, soil.up(), 2.5) == Move.FAILED) index++;
                return null;
            }
            c.getNavigation().stop();
            if (ripe) {
                if (c.mineStep(crop)) {
                    harvested++;
                    c.addExhaustion(0.01f);
                }
                return null;
            }
            if (needsTill) {
                if (!(c.getMainHandStack().getItem() instanceof HoeItem) && !holdHoe(c)) {
                    problem = "needs a hoe to till the soil";
                    index++;
                    return null;
                }
                if (!cropState.isAir()) c.getWorld().breakBlock(crop, false, c);
                c.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                c.getWorld().setBlockState(soil, Blocks.FARMLAND.getDefaultState());
                c.getMainHandStack().damage(1, c, e -> e.sendEquipmentBreakStatus(net.minecraft.entity.EquipmentSlot.MAINHAND));
                tilled++;
                return null;
            }
            Item seed = seedFor(c);
            if (seed == null) {
                problem = "out of seeds";
                index++;
                return null;
            }
            BlockState plant = cropBlockFor(seed);
            c.placeBlock(crop, plant);
            planted++;
            index++;
            return null;
        }
        return finish("done");
    }

    private Result finish(String reason) {
        String what = "Farm: harvested " + harvested + ", planted " + planted + (tilled > 0 ? ", tilled " + tilled : "")
                + " (" + farm.size() + " plots at " + farm.minX + ", " + farm.y + ", " + farm.minZ + ")"
                + (problem.isBlank() ? "" : "; " + problem) + ".";
        return harvested + planted + tilled > 0 || mode == Mode.TEND ? ok(what) : fail(what);
    }

    @Nullable
    private static CompanionMemory.Farm nearestFarm(CompanionMemory m, CompanionEntity c, String dim) {
        CompanionMemory.Farm best = null;
        double bestD = Double.MAX_VALUE;
        for (CompanionMemory.Farm f : m.farms) {
            if (!f.dimension.equals(dim)) continue;
            double d = c.getBlockPos().getSquaredDistance(f.minX, f.y, f.minZ);
            if (d < bestD) {
                bestD = d;
                best = f;
            }
        }
        return best;
    }

    /** A flat patch of grass or dirt near home (or here), open to the sky, not part of anyone's build. */
    @Nullable
    private CompanionMemory.Farm chooseSite(CompanionEntity c, CompanionMemory m, String dim) {
        CompanionMemory.Location home = m.home();
        BlockPos center = home != null && home.dimension.equals(dim) ? new BlockPos(home.x, home.y, home.z) : c.getBlockPos();
        for (int r = 4; r <= 24; r += 2) {
            for (int dx = -r; dx <= r; dx += 2) {
                for (int dz = -r; dz <= r; dz += 2) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue; // ring by ring, nearest first
                    BlockPos corner = c.getWorld().getTopPosition(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, center.add(dx, 0, dz)).down();
                    if (fits(c, corner)) {
                        CompanionMemory.Farm f = new CompanionMemory.Farm();
                        f.name = "farm" + (m.farms.isEmpty() ? "" : " " + (m.farms.size() + 1));
                        f.dimension = dim;
                        f.minX = corner.getX();
                        f.minZ = corner.getZ();
                        f.maxX = corner.getX() + size - 1;
                        f.maxZ = corner.getZ() + size - 1;
                        f.y = corner.getY();
                        f.crop = cropName(c);
                        return f;
                    }
                }
            }
        }
        return null;
    }

    private boolean fits(CompanionEntity c, BlockPos corner) {
        for (int x = 0; x < size; x++)
            for (int z = 0; z < size; z++) {
                BlockPos soil = corner.add(x, 0, z);
                BlockState s = c.getWorld().getBlockState(soil);
                BlockState above = c.getWorld().getBlockState(soil.up());
                if (!(s.isOf(Blocks.GRASS_BLOCK) || s.isOf(Blocks.DIRT)) || !(above.isAir() || above.isReplaceable() && !above.isIn(BlockTags.SAPLINGS))) return false;
                if (!c.getWorld().isSkyVisible(soil.up())) return false;
                if (!BreakPolicy.allowed(c, soil, BreakPolicy.Purpose.GATHER)) return false;
            }
        return true;
    }

    /** Irrigation: a water source in the middle if it carries a bucket of water (farmland near water grows faster). */
    private void placeWater(CompanionEntity c) {
        BlockPos middle = new BlockPos((farm.minX + farm.maxX) / 2, farm.y, (farm.minZ + farm.maxZ) / 2);
        for (BlockPos p : BlockPos.iterate(middle.add(-4, -1, -4), middle.add(4, 1, 4))) {
            if (c.getWorld().getFluidState(p).isIn(FluidTags.WATER)) return; // already watered
        }
        if (c.count(Items.WATER_BUCKET) > 0) {
            c.remove(Items.WATER_BUCKET, 1);
            c.give(new ItemStack(Items.BUCKET));
            c.getWorld().setBlockState(middle, Blocks.WATER.getDefaultState());
        }
    }

    /** Adds a row along the side with room for it. */
    private boolean expand(CompanionEntity c) {
        int[][] sides = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
        for (int[] side : sides) {
            List<BlockPos> row = new ArrayList<>();
            if (side[1] != 0) {
                int z = side[1] < 0 ? farm.minZ - 1 : farm.maxZ + 1;
                for (int x = farm.minX; x <= farm.maxX; x++) row.add(new BlockPos(x, farm.y, z));
            } else {
                int x = side[0] < 0 ? farm.minX - 1 : farm.maxX + 1;
                for (int z = farm.minZ; z <= farm.maxZ; z++) row.add(new BlockPos(x, farm.y, z));
            }
            boolean ok = true;
            for (BlockPos p : row) {
                BlockState s = c.getWorld().getBlockState(p);
                BlockState above = c.getWorld().getBlockState(p.up());
                if (!(s.isOf(Blocks.GRASS_BLOCK) || s.isOf(Blocks.DIRT)) || !(above.isAir() || above.isReplaceable())
                        || !BreakPolicy.allowed(c, p, BreakPolicy.Purpose.GATHER)) {
                    ok = false;
                    break;
                }
            }
            if (!ok) continue;
            if (side[1] < 0) farm.minZ--;
            else if (side[1] > 0) farm.maxZ++;
            else if (side[0] < 0) farm.minX--;
            else farm.maxX++;
            return true;
        }
        return false;
    }

    private static boolean holdHoe(CompanionEntity c) {
        for (int i = 0; i < c.getInventory().size(); i++) {
            if (c.getInventory().getStack(i).getItem() instanceof HoeItem) {
                c.hold(c.getInventory().getStack(i));
                return true;
            }
        }
        return false;
    }

    @Nullable
    private Item seedFor(CompanionEntity c) {
        Item preferred = farm == null ? null : Ids.item(farm.crop.equals("wheat") ? "wheat_seeds" : farm.crop).orElse(null);
        if (preferred != null && c.count(preferred) > 0) return preferred;
        for (Item seed : new Item[]{Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.BEETROOT_SEEDS}) {
            if (c.count(seed) > 0) return seed;
        }
        return null;
    }

    private static String cropName(CompanionEntity c) {
        if (c.count(Items.WHEAT_SEEDS) > 0) return "wheat";
        if (c.count(Items.CARROT) > 0) return "carrot";
        if (c.count(Items.POTATO) > 0) return "potato";
        if (c.count(Items.BEETROOT_SEEDS) > 0) return "beetroot";
        return "wheat";
    }

    private static BlockState cropBlockFor(Item seed) {
        if (seed == Items.CARROT) return Blocks.CARROTS.getDefaultState();
        if (seed == Items.POTATO) return Blocks.POTATOES.getDefaultState();
        if (seed == Items.BEETROOT_SEEDS) return Blocks.BEETROOTS.getDefaultState();
        return Blocks.WHEAT.getDefaultState();
    }
}
