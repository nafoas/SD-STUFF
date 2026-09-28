package dev.aicompanion.game.tasks;

import dev.aicompanion.entity.CompanionEntity;
import dev.aicompanion.game.Ids;
import dev.aicompanion.game.Recipes;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Crafts an item, including every intermediate step. Uses (or places) a crafting table when a 3x3 grid is needed. */
public class CraftTask extends Task {
    private final Item item;
    private final int count;
    private Recipes.Plan plan;
    private int stepIndex;
    private BlockPos table;
    private int cooldown;

    public CraftTask(Item item, int count) {
        this.item = item;
        this.count = Math.max(1, Math.min(256, count));
    }

    @Override
    public String describe() {
        return "crafting " + count + " " + Ids.name(item);
    }

    public static Map<Item, Integer> inventoryCounts(CompanionEntity c) {
        Map<Item, Integer> counts = new HashMap<>();
        for (int i = 0; i < c.getInventory().size(); i++) {
            ItemStack s = c.getInventory().getStack(i);
            if (!s.isEmpty()) counts.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        ItemStack hand = c.getMainHandStack();
        if (!hand.isEmpty()) counts.merge(hand.getItem(), hand.getCount(), Integer::sum);
        return counts;
    }

    @Override
    protected Result step(CompanionEntity c) {
        if (plan == null) {
            if (Recipes.recipesFor(c.serverWorld(), item).isEmpty()) {
                return fail(Ids.name(item) + " can't be crafted" + Recipes.smeltingProducing(c.serverWorld(), item)
                        .map(r -> "; smelt it from " + Ids.name(r.getIngredients().get(0).getMatchingStacks()[0].getItem())).orElse(""));
            }
            plan = Recipes.plan(c.serverWorld(), inventoryCounts(c), item, count);
            if (!plan.feasible()) return fail("Missing materials for " + count + " " + Ids.name(item) + ": need " + Recipes.describeMissing(c.serverWorld(), plan.missing()));
            if (plan.steps().isEmpty()) return ok("Already had " + count + " " + Ids.name(item) + ".");
        }
        if (plan.needsTable() && table == null) {
            Result r = getTable(c);
            if (r != null) return r;
            if (table == null) return null;
        }
        if (table != null && !c.canReach(table)) {
            if (approach(c, table, 2.5) == Move.FAILED) return fail("Couldn't reach the crafting table.");
            return null;
        }
        if (cooldown-- > 0) return null;
        if (stepIndex >= plan.steps().size()) return ok("Crafted " + count + " " + Ids.name(item) + ".");

        Recipes.Step step = plan.steps().get(stepIndex);
        for (Map.Entry<Item, Integer> e : step.consumes().entrySet()) {
            if (c.count(e.getKey()) < e.getValue()) return fail("Ran short of " + Ids.name(e.getKey()) + " while crafting.");
        }
        step.consumes().forEach((ingredient, n) -> {
            c.remove(ingredient, n);
            Item leftover = ingredient.getRecipeRemainder(); // e.g. empty buckets
            if (leftover != null) c.give(new ItemStack(leftover, n));
        });
        ItemStack out = step.recipe().getOutput(c.getWorld().getRegistryManager()).copy();
        out.setCount(out.getCount() * step.times());
        c.give(out);
        c.swingHand(net.minecraft.util.Hand.MAIN_HAND);
        stepIndex++;
        cooldown = 5;
        return null;
    }

    /** Finds a nearby crafting table, or places/crafts one. Returns a result only on failure. */
    private Result getTable(CompanionEntity c) {
        BlockPos found = findBlockNear(c, 16);
        if (found != null) {
            table = found;
            return null;
        }
        if (c.count(Items.CRAFTING_TABLE) == 0) {
            Recipes.Plan tablePlan = Recipes.plan(c.serverWorld(), inventoryCounts(c), Items.CRAFTING_TABLE, 1);
            if (!tablePlan.feasible()) return fail("Needs a crafting table and doesn't have the 4 planks to make one.");
            for (Recipes.Step s : tablePlan.steps()) {
                s.consumes().forEach(c::remove);
                ItemStack out = s.recipe().getOutput(c.getWorld().getRegistryManager()).copy();
                out.setCount(out.getCount() * s.times());
                c.give(out);
            }
        }
        BlockPos spot = findPlaceSpot(c);
        if (spot == null) return fail("No room to put down a crafting table.");
        c.placeBlock(spot, Blocks.CRAFTING_TABLE.getDefaultState());
        table = spot;
        // The plan was made before the table was crafted; redo it with the current inventory.
        plan = Recipes.plan(c.serverWorld(), inventoryCounts(c), item, count);
        if (!plan.feasible()) return fail("Missing materials: need " + Recipes.describeMissing(c.serverWorld(), plan.missing()));
        return null;
    }

    private static BlockPos findBlockNear(CompanionEntity c, int r) {
        BlockPos origin = c.getBlockPos();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.iterate(origin.add(-r, -4, -r), origin.add(r, 4, r))) {
            if (c.getWorld().getBlockState(p).isOf(Blocks.CRAFTING_TABLE)) {
                double d = p.getSquaredDistance(origin);
                if (d < bestD) {
                    bestD = d;
                    best = p.toImmutable();
                }
            }
        }
        return best;
    }

    static BlockPos findPlaceSpot(CompanionEntity c) {
        BlockPos origin = c.getBlockPos();
        for (BlockPos p : List.of(origin.north(2), origin.east(2), origin.south(2), origin.west(2), origin.north().east(), origin.south().west())) {
            if (c.getWorld().getBlockState(p).isReplaceable() && c.getWorld().getBlockState(p.down()).isSolidBlock(c.getWorld(), p.down())) return p;
        }
        return null;
    }
}
