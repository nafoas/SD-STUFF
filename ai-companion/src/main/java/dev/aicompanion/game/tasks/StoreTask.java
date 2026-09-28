package dev.aicompanion.game.tasks;

import dev.aicompanion.ai.CompanionBrain;
import dev.aicompanion.ai.CompanionMemory;
import dev.aicompanion.entity.CompanionEntity;
import dev.aicompanion.game.Ids;
import dev.aicompanion.world.BlockOwnership;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.MiningToolItem;
import net.minecraft.item.SwordItem;
import net.minecraft.item.ToolItem;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Puts the bag away the way a tidy player does: into its own storage chests near home, sorted by kind (ores, stone,
 * wood, food, tools, mob drops, farming, misc), keeping its working kit (best tools, weapon, some food, torches,
 * blocks to build with). Makes a new chest when storage is full. Every chest it touches is remembered.
 */
public class StoreTask extends Task {
    private final List<BlockPos> chests = new ArrayList<>();
    private Map<String, List<Integer>> pending;
    private String currentCategory;
    private BlockPos target;
    private int stored;
    private boolean placedChest;

    @Override
    public boolean isMajor() {
        return false;
    }

    @Override
    public String describe() {
        return "putting things away in storage";
    }

    public static String category(Item item) {
        String id = Ids.name(item);
        if (item.isFood()) return "food";
        if (item instanceof ToolItem || item instanceof ArmorItem || item == Items.BOW || item == Items.SHIELD || item == Items.FISHING_ROD) return "tools";
        if (id.startsWith("raw_") || id.endsWith("_ingot") || id.endsWith("_nugget") || id.equals("coal") || id.equals("charcoal") || id.equals("diamond")
                || id.equals("emerald") || id.equals("lapis_lazuli") || id.equals("redstone") || id.equals("quartz") || id.endsWith("_ore")) return "ores";
        if (new ItemStack(item).isIn(ItemTags.LOGS) || new ItemStack(item).isIn(ItemTags.PLANKS) || item == Items.STICK || new ItemStack(item).isIn(ItemTags.SAPLINGS)) return "wood";
        if (id.contains("seeds") || id.equals("wheat") || id.equals("bone_meal") || id.equals("sugar_cane")) return "farming";
        if (id.equals("rotten_flesh") || id.equals("bone") || id.equals("string") || id.equals("gunpowder") || id.equals("spider_eye") || id.equals("arrow")
                || id.equals("slime_ball") || id.equals("ender_pearl") || id.equals("feather") || id.equals("leather")) return "mob drops";
        if (item instanceof BlockItem bi && (bi.getBlock().getDefaultState().isIn(net.minecraft.registry.tag.BlockTags.BASE_STONE_OVERWORLD)
                || id.contains("cobble") || id.equals("dirt") || id.equals("gravel") || id.equals("sand") || id.equals("flint"))) return "stone";
        return "misc";
    }

    /** What it keeps on itself: best tool of each kind, a weapon, a stack of food, torches, some building blocks. */
    private static Map<Integer, Integer> keepCounts(CompanionEntity c) {
        Map<Integer, Integer> keep = new HashMap<>();
        Map<String, Integer> bestToolSlot = new HashMap<>();
        Map<String, Float> bestToolScore = new HashMap<>();
        int foodKept = 0, torchesKept = 0, fillerKept = 0, seedsKept = 0;
        for (int i = 0; i < c.getInventory().size(); i++) {
            ItemStack s = c.getInventory().getStack(i);
            if (s.isEmpty()) continue;
            Item item = s.getItem();
            if (item instanceof MiningToolItem || item instanceof SwordItem || item == Items.SHIELD) {
                String kind = item.getClass().getSimpleName();
                float score = item instanceof ToolItem t ? t.getMaterial().getMiningSpeedMultiplier() + t.getMaterial().getAttackDamage() : 1;
                if (score > bestToolScore.getOrDefault(kind, -1f)) {
                    bestToolScore.put(kind, score);
                    bestToolSlot.put(kind, i);
                }
            } else if (item.isFood() && foodKept < 16) {
                int k = Math.min(s.getCount(), 16 - foodKept);
                keep.put(i, k);
                foodKept += k;
            } else if (item == Items.TORCH && torchesKept < 32) {
                int k = Math.min(s.getCount(), 32 - torchesKept);
                keep.put(i, k);
                torchesKept += k;
            } else if ((item == Items.COBBLESTONE || item == Items.DIRT || item == Items.COBBLED_DEEPSLATE) && fillerKept < 32) {
                int k = Math.min(s.getCount(), 32 - fillerKept);
                keep.put(i, k);
                fillerKept += k;
            } else if ((item == Items.WHEAT_SEEDS || item == Items.CHEST || item == Items.WATER_BUCKET || item == Items.BUCKET) && seedsKept < 64) {
                keep.put(i, s.getCount());
                seedsKept += s.getCount();
            }
        }
        for (int slot : bestToolSlot.values()) keep.put(slot, 1);
        return keep;
    }

    @Override
    protected Result step(CompanionEntity c) {
        CompanionBrain brain = c.brain();
        if (brain == null) return fail("No memory.");
        if (ticks > 20 * 120) return done("ran out of time");
        if (pending == null) {
            findStorage(c, brain.memory());
            pending = new LinkedHashMap<>();
            Map<Integer, Integer> keep = keepCounts(c);
            for (int i = 0; i < c.getInventory().size(); i++) {
                ItemStack s = c.getInventory().getStack(i);
                if (s.isEmpty() || keep.getOrDefault(i, 0) >= s.getCount()) continue;
                pending.computeIfAbsent(category(s.getItem()), k -> new ArrayList<>()).add(i);
            }
            // Wood goes away last: its planks may be needed to make more chests.
            List<Integer> wood = pending.remove("wood");
            if (wood != null) pending.put("wood", wood);
            if (pending.isEmpty()) return ok("Nothing to put away; the bag only holds its working kit.");
            this.keep = keep;
        }
        if (currentCategory == null) {
            if (pending.isEmpty()) return done("all put away");
            currentCategory = pending.keySet().iterator().next();
            target = chestFor(c, brain.memory(), currentCategory);
            if (target == null) {
                BlockPos placed = placeNewChest(c, brain.memory());
                if (placed == null) return done("storage is full and it has no chest to place (8 planks make one)");
                target = placed;
            }
            resetMovement();
        }
        if (!c.canReach(target)) {
            if (approach(c, target, 2.5) == Move.FAILED) return done("couldn't reach the storage chests");
            return null;
        }
        c.getNavigation().stop();
        if (!(c.getWorld().getBlockEntity(target) instanceof Inventory container)) {
            chests.remove(target);
            currentCategory = null;
            return null;
        }
        c.getLookControl().lookAt(target.toCenterPos());
        c.swingHand(net.minecraft.util.Hand.MAIN_HAND);
        for (int slot : pending.get(currentCategory)) {
            ItemStack s = c.getInventory().getStack(slot);
            int amount = s.getCount() - keep.getOrDefault(slot, 0);
            if (amount <= 0) continue;
            ItemStack moving = s.copyWithCount(amount);
            int before = moving.getCount();
            insert(container, moving);
            int moved = before - moving.getCount();
            s.decrement(moved);
            stored += moved;
        }
        container.markDirty();
        remember(c, brain.memory(), target, container, currentCategory);
        boolean leftovers = pending.get(currentCategory).stream().anyMatch(slot -> c.getInventory().getStack(slot).getCount() > keep.getOrDefault(slot, 0));
        if (leftovers) {
            // This chest filled up: try another for the same kind.
            chests.remove(target);
            target = null;
            BlockPos next = chestFor(c, brain.memory(), currentCategory);
            if (next == null) next = placeNewChest(c, brain.memory());
            if (next == null) {
                pending.remove(currentCategory);
                currentCategory = null;
            } else {
                target = next;
            }
            return null;
        }
        pending.remove(currentCategory);
        currentCategory = null;
        return null;
    }

    private Map<Integer, Integer> keep = new HashMap<>();

    private Result done(String reason) {
        String msg = "Put away " + stored + " items into " + chests.size() + " storage chests" + (placedChest ? " (made a new one)" : "") + " (" + reason + ").";
        return stored > 0 ? ok(msg) : fail(msg);
    }

    /** Its own chests near home (or here). */
    private void findStorage(CompanionEntity c, CompanionMemory m) {
        CompanionMemory.Location home = m.home();
        String dim = c.getWorld().getRegistryKey().getValue().toString();
        BlockPos center = home != null && home.dimension.equals(dim) ? new BlockPos(home.x, home.y, home.z) : c.getBlockPos();
        String self = BlockOwnership.companionOwner(c.getCharacterId());
        BlockOwnership owners = BlockOwnership.get(c.serverWorld());
        for (BlockPos p : BlockPos.iterate(center.add(-16, -6, -16), center.add(16, 6, 16))) {
            BlockEntity be = c.getWorld().getBlockEntity(p);
            if ((be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity) && self.equals(owners.owner(p))) chests.add(p.toImmutable());
        }
    }

    /** A chest already labeled for this kind, else one holding this kind, else an empty one (which gets this label). */
    @Nullable
    private BlockPos chestFor(CompanionEntity c, CompanionMemory m, String category) {
        String dim = c.getWorld().getRegistryKey().getValue().toString();
        BlockPos empty = null;
        for (BlockPos p : chests) {
            CompanionMemory.ChestRecord r = m.chests.get(CompanionMemory.key(dim, p.getX(), p.getY(), p.getZ()));
            if (r != null && r.label.equals(category) && !full(c, p)) return p;
        }
        for (BlockPos p : chests) {
            CompanionMemory.ChestRecord r = m.chests.get(CompanionMemory.key(dim, p.getX(), p.getY(), p.getZ()));
            if (r != null && r.label.isBlank() && !r.contents.isEmpty() && r.contents.keySet().stream().allMatch(k -> Ids.item(k).map(i -> category(i).equals(category)).orElse(false))
                    && !full(c, p)) return p;
            if ((r == null || r.contents.isEmpty() && r.label.isBlank()) && c.getWorld().getBlockEntity(p) instanceof Inventory inv && inv.isEmpty() && empty == null) empty = p;
        }
        return empty;
    }

    private static boolean full(CompanionEntity c, BlockPos p) {
        if (!(c.getWorld().getBlockEntity(p) instanceof Inventory inv)) return true;
        for (int i = 0; i < inv.size(); i++) if (inv.getStack(i).isEmpty()) return false;
        return true;
    }

    /** Places a new chest next to its storage (or by its home), crafting one from planks if needed. */
    @Nullable
    private BlockPos placeNewChest(CompanionEntity c, CompanionMemory m) {
        if (c.count(Items.CHEST) == 0) {
            int planks = 0;
            Item plank = null;
            for (int i = 0; i < c.getInventory().size(); i++) {
                ItemStack s = c.getInventory().getStack(i);
                if (s.isIn(ItemTags.PLANKS) && s.getCount() >= 8) {
                    plank = s.getItem();
                    planks = s.getCount();
                    break;
                }
            }
            if (plank == null || planks < 8) {
                // Two logs make eight planks.
                for (int i = 0; i < c.getInventory().size() && plank == null; i++) {
                    ItemStack s = c.getInventory().getStack(i);
                    if (s.isIn(ItemTags.LOGS) && s.getCount() >= 2) {
                        Item planksItem = Ids.item(Ids.name(s.getItem()).replace("stripped_", "").replace("_log", "_planks").replace("_wood", "_planks")
                                .replace("_stem", "_planks").replace("_hyphae", "_planks")).orElse(Items.OAK_PLANKS);
                        s.decrement(2);
                        c.give(new ItemStack(planksItem, 8));
                        plank = planksItem;
                    }
                }
                if (plank == null) return null;
            }
            c.remove(plank, 8);
            c.give(new ItemStack(Items.CHEST));
        }
        List<BlockPos> anchors = new ArrayList<>(chests);
        CompanionMemory.Location home = m.home();
        if (anchors.isEmpty()) anchors.add(home != null ? new BlockPos(home.x, home.y, home.z) : c.getBlockPos());
        for (BlockPos anchor : anchors) {
            for (Direction d : Direction.Type.HORIZONTAL) {
                for (int dist = 1; dist <= 3; dist++) {
                    BlockPos p = anchor.offset(d, dist);
                    // Keep a gap between single chests so they don't merge into one double chest.
                    boolean gap = true;
                    for (Direction n : Direction.Type.HORIZONTAL) if (c.getWorld().getBlockState(p.offset(n)).isOf(Blocks.CHEST)) gap = false;
                    if (gap && c.getWorld().getBlockState(p).isReplaceable() && c.getWorld().getBlockState(p.down()).isSolidBlock(c.getWorld(), p.down())) {
                        if (!c.placeBlock(p, Blocks.CHEST.getDefaultState())) return null;
                        chests.add(p);
                        placedChest = true;
                        return p;
                    }
                }
            }
        }
        return null;
    }

    private void remember(CompanionEntity c, CompanionMemory m, BlockPos pos, Inventory container, String category) {
        CompanionMemory.ChestRecord r = new CompanionMemory.ChestRecord();
        r.dimension = c.getWorld().getRegistryKey().getValue().toString();
        r.x = pos.getX();
        r.y = pos.getY();
        r.z = pos.getZ();
        r.owner = "self";
        r.label = category;
        for (int i = 0; i < container.size(); i++) {
            ItemStack s = container.getStack(i);
            if (!s.isEmpty()) r.contents.merge(Ids.name(s.getItem()), s.getCount(), Integer::sum);
        }
        r.seen = System.currentTimeMillis();
        m.recordChest(r);
        if (m.places.values().stream().noneMatch(l -> l.type.equals("storage"))) {
            CompanionMemory.Location loc = new CompanionMemory.Location(r.dimension, pos.getX(), pos.getY(), pos.getZ());
            loc.type = "storage";
            loc.note = "storage chests";
            m.places.put("storage", loc);
        }
    }

    private static void insert(Inventory to, ItemStack stack) {
        for (int i = 0; i < to.size() && !stack.isEmpty(); i++) {
            ItemStack slot = to.getStack(i);
            if (ItemStack.canCombine(slot, stack) && slot.getCount() < slot.getMaxCount()) {
                int add = Math.min(stack.getCount(), slot.getMaxCount() - slot.getCount());
                slot.increment(add);
                stack.decrement(add);
            }
        }
        for (int i = 0; i < to.size() && !stack.isEmpty(); i++) {
            if (to.getStack(i).isEmpty()) {
                to.setStack(i, stack.copy());
                stack.setCount(0);
            }
        }
    }
}
