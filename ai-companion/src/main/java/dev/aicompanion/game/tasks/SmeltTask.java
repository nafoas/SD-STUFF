package dev.aicompanion.game.tasks;

import dev.aicompanion.entity.CompanionEntity;
import dev.aicompanion.game.Ids;
import dev.aicompanion.game.Recipes;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.AbstractCookingRecipe;
import net.minecraft.util.math.BlockPos;

import java.util.Map;

/** Smelts items at a furnace (placing one if carried or craftable), using fuel from the inventory. Vanilla timing. */
public class SmeltTask extends Task {
    private final Item input;
    private final int count;
    private AbstractCookingRecipe recipe;
    private BlockPos furnace;
    private int done;
    private int cookTicks;
    private int fuelTicksLeft;
    private String outputName = "?";

    public SmeltTask(Item input, int count) {
        this.input = input;
        this.count = Math.max(1, Math.min(64, count));
    }

    @Override
    public String describe() {
        return "smelting " + Ids.name(input) + " (" + done + "/" + count + ")";
    }

    @Override
    protected Result step(CompanionEntity c) {
        if (recipe == null) {
            recipe = Recipes.smeltingFor(c.serverWorld(), input).orElse(null);
            if (recipe == null) return fail(Ids.name(input) + " can't be smelted.");
            outputName = Ids.name(recipe.getOutput(c.getWorld().getRegistryManager()).getItem());
            if (c.count(input) < 1) return fail("Doesn't have any " + Ids.name(input) + ".");
        }
        if (furnace == null) {
            Result r = findOrPlaceFurnace(c);
            if (r != null) return r;
        }
        if (!c.getWorld().getBlockState(furnace).isOf(Blocks.FURNACE)) return finish("the furnace is gone");
        if (c.squaredDistanceTo(furnace.toCenterPos()) > 3 * 3) {
            if (approach(c, furnace, 2.5) == Move.FAILED) return fail("Couldn't reach the furnace.");
            return null;
        }
        c.getNavigation().stop();
        c.getLookControl().lookAt(furnace.toCenterPos());
        if (done >= count) return finish("done");
        if (c.count(input) < 1) return finish("out of " + Ids.name(input));
        if (fuelTicksLeft <= 0 && !burnFuel(c)) return finish("out of fuel (coal, charcoal, wood or planks)");
        fuelTicksLeft--;
        if (++cookTicks >= recipe.getCookTime()) {
            cookTicks = 0;
            c.remove(input, 1);
            c.give(recipe.getOutput(c.getWorld().getRegistryManager()).copy());
            done++;
        }
        return null;
    }

    private Result finish(String reason) {
        return done > 0 ? ok("Smelted " + done + " " + Ids.name(input) + " into " + outputName + " (" + reason + ").") : fail("Smelted nothing: " + reason + ".");
    }

    private boolean burnFuel(CompanionEntity c) {
        Map<Item, Integer> fuels = AbstractFurnaceBlockEntity.createFuelTimeMap();
        Item best = null;
        for (Item candidate : new Item[]{Items.COAL, Items.CHARCOAL, Items.COAL_BLOCK, Items.BLAZE_ROD}) {
            if (c.count(candidate) > 0) {
                best = candidate;
                break;
            }
        }
        if (best == null) {
            for (int i = 0; i < c.getInventory().size(); i++) {
                ItemStack s = c.getInventory().getStack(i);
                if (!s.isEmpty() && fuels.containsKey(s.getItem()) && s.getItem() != input && !s.isOf(Items.LAVA_BUCKET)) {
                    best = s.getItem();
                    break;
                }
            }
        }
        if (best == null) return false;
        c.remove(best, 1);
        fuelTicksLeft = fuels.get(best);
        return true;
    }

    private Result findOrPlaceFurnace(CompanionEntity c) {
        BlockPos origin = c.getBlockPos();
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.iterate(origin.add(-16, -4, -16), origin.add(16, 4, 16))) {
            if (c.getWorld().getBlockState(p).isOf(Blocks.FURNACE) && p.getSquaredDistance(origin) < bestD) {
                bestD = p.getSquaredDistance(origin);
                furnace = p.toImmutable();
            }
        }
        if (furnace != null) return null;
        if (c.count(Items.FURNACE) == 0) {
            if (c.count(Items.COBBLESTONE) < 8) return fail("No furnace nearby and not enough cobblestone (8) to make one.");
            c.remove(Items.COBBLESTONE, 8);
            c.give(new ItemStack(Items.FURNACE));
        }
        BlockPos spot = CraftTask.findPlaceSpot(c);
        if (spot == null) return fail("No room to put down a furnace.");
        c.placeBlock(spot, Blocks.FURNACE.getDefaultState());
        furnace = spot;
        return null;
    }
}
