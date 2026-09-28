package dev.aicompanion.game;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.AbstractCookingRecipe;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Works out full crafting chains from the game's real recipe data, so the AI never has to remember recipes.
 * "iron_pickaxe" becomes: planks -> sticks -> iron_pickaxe, with a list of anything still missing.
 */
public final class Recipes {
    private Recipes() {}

    public record Step(CraftingRecipe recipe, Item output, int times, Map<Item, Integer> consumes) {}

    public record Plan(List<Step> steps, Map<Item, Integer> missing, boolean needsTable) {
        public boolean feasible() {
            return missing.isEmpty();
        }
    }

    private static final int MAX_DEPTH = 6;

    public static Plan plan(ServerWorld world, Map<Item, Integer> inventory, Item target, int count) {
        Map<Item, Integer> sim = new HashMap<>(inventory);
        List<Step> steps = new ArrayList<>();
        Map<Item, Integer> missing = new LinkedHashMap<>();
        int have = sim.getOrDefault(target, 0);
        sim.put(target, 0); // target already in inventory counts toward the goal but can't be used as its own ingredient
        make(world, sim, target, Math.max(0, count - have), steps, missing, 0, new ArrayList<>());
        boolean needsTable = steps.stream().anyMatch(s -> needsTable(s.recipe()));
        return new Plan(steps, missing, needsTable);
    }

    /** Tries to end up with `need` more of item in sim. Records crafting steps and anything that must be gathered. */
    private static void make(ServerWorld world, Map<Item, Integer> sim, Item item, int need, List<Step> steps,
                             Map<Item, Integer> missing, int depth, List<Item> chain) {
        if (need <= 0) return;
        int available = sim.getOrDefault(item, 0);
        if (available >= need) {
            sim.put(item, available - need);
            return;
        }
        int toMake = need - available;
        List<CraftingRecipe> recipes = depth >= MAX_DEPTH || chain.contains(item) ? List.of() : recipesFor(world, item);
        Map<Item, Integer> firstFailure = null;
        for (CraftingRecipe recipe : recipes) {
            int perCraft = recipe.getOutput(world.getRegistryManager()).getCount();
            int times = (toMake + perCraft - 1) / perCraft;
            // Try the recipe on a copy; keep it only if it works without anything missing.
            Map<Item, Integer> trySim = new HashMap<>(sim);
            List<Step> trySteps = new ArrayList<>();
            Map<Item, Integer> tryMissing = new LinkedHashMap<>();
            Map<Item, Integer> consumes = new LinkedHashMap<>();
            List<Item> newChain = new ArrayList<>(chain);
            newChain.add(item);
            for (int t = 0; t < times; t++) {
                for (Ingredient ingredient : recipe.getIngredients()) {
                    if (ingredient.isEmpty()) continue;
                    Item choice = chooseIngredient(world, trySim, ingredient, depth);
                    make(world, trySim, choice, 1, trySteps, tryMissing, depth + 1, newChain);
                    consumes.merge(choice, 1, Integer::sum);
                }
            }
            if (tryMissing.isEmpty()) {
                sim.clear();
                sim.putAll(trySim);
                steps.addAll(trySteps);
                steps.add(new Step(recipe, item, times, consumes));
                int made = times * perCraft;
                sim.put(item, available + made - need);
                return;
            }
            if (firstFailure == null) firstFailure = tryMissing;
        }
        sim.put(item, 0);
        if (firstFailure != null) {
            // Craftable in principle, but the ingredients have to be gathered first: report those.
            firstFailure.forEach((k, v) -> missing.merge(k, v, Integer::sum));
        } else {
            // No recipe: this has to be gathered, smelted or traded.
            missing.merge(item, toMake, Integer::sum);
        }
    }

    /** Picks which item to use for a recipe slot: something we have, else something craftable, else the first option. */
    private static Item chooseIngredient(ServerWorld world, Map<Item, Integer> sim, Ingredient ingredient, int depth) {
        ItemStack[] options = ingredient.getMatchingStacks();
        Item best = null;
        int bestCount = 0;
        for (ItemStack option : options) {
            int c = sim.getOrDefault(option.getItem(), 0);
            if (c > bestCount) {
                best = option.getItem();
                bestCount = c;
            }
        }
        if (best != null) return best;
        if (depth < MAX_DEPTH) {
            for (ItemStack option : options) {
                if (!recipesFor(world, option.getItem()).isEmpty()) return option.getItem();
            }
        }
        return options.length > 0 ? options[0].getItem() : net.minecraft.item.Items.AIR;
    }

    public static List<CraftingRecipe> recipesFor(ServerWorld world, Item item) {
        List<CraftingRecipe> result = new ArrayList<>();
        for (CraftingRecipe recipe : world.getRecipeManager().listAllOfType(RecipeType.CRAFTING)) {
            if (recipe.isIgnoredInRecipeBook() || recipe.getIngredients().isEmpty()) continue;
            if (recipe.getOutput(world.getRegistryManager()).isOf(item)) result.add(recipe);
        }
        return result;
    }

    public static boolean needsTable(CraftingRecipe recipe) {
        if (recipe instanceof ShapedRecipe shaped) return shaped.getWidth() > 2 || shaped.getHeight() > 2;
        return recipe.getIngredients().stream().filter(i -> !i.isEmpty()).count() > 4;
    }

    /** Finds the smelting recipe that turns input into something, if any. */
    public static Optional<AbstractCookingRecipe> smeltingFor(ServerWorld world, Item input) {
        for (AbstractCookingRecipe recipe : world.getRecipeManager().listAllOfType(RecipeType.SMELTING)) {
            if (recipe.getIngredients().get(0).test(new ItemStack(input))) return Optional.of(recipe);
        }
        return Optional.empty();
    }

    /** Finds a smelting recipe that produces output (e.g. iron_ingot from raw_iron). */
    public static Optional<AbstractCookingRecipe> smeltingProducing(ServerWorld world, Item output) {
        for (AbstractCookingRecipe recipe : world.getRecipeManager().listAllOfType(RecipeType.SMELTING)) {
            if (recipe.getOutput(world.getRegistryManager()).isOf(output)) return Optional.of(recipe);
        }
        return Optional.empty();
    }

    public static String describeMissing(ServerWorld world, Map<Item, Integer> missing) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : missing.entrySet()) {
            String hint = smeltingProducing(world, e.getKey())
                    .map(r -> " (smelt from " + Ids.name(r.getIngredients().get(0).getMatchingStacks()[0].getItem()) + ")")
                    .orElse("");
            parts.add(e.getValue() + " " + Ids.name(e.getKey()) + hint);
        }
        return String.join(", ", parts);
    }
}
