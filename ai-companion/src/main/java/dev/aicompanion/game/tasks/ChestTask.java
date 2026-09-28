package dev.aicompanion.game.tasks;

import dev.aicompanion.ModConfig;
import dev.aicompanion.ai.CompanionBrain;
import dev.aicompanion.ai.CompanionMemory;
import dev.aicompanion.entity.CompanionEntity;
import dev.aicompanion.game.Ids;
import dev.aicompanion.world.BlockOwnership;
import dev.aicompanion.world.BreakPolicy;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Puts items into, or takes items out of, a chest or barrel. Picks the chest from memory when it knows where an
 * item is (or where its own storage is), only takes from chests it's allowed to, and remembers what each chest holds.
 */
public class ChestTask extends Task {
    private final boolean deposit;
    @Nullable private final Item item;
    private final int count;
    @Nullable private BlockPos chest;
    private final String label;
    private String takeProblem;

    public ChestTask(boolean deposit, @Nullable Item item, int count, @Nullable BlockPos chest, String label) {
        this.deposit = deposit;
        this.item = item;
        this.count = count <= 0 ? Integer.MAX_VALUE : count;
        this.chest = chest;
        this.label = label == null ? "" : label;
    }

    @Override
    public boolean isMajor() {
        return false;
    }

    @Override
    public String describe() {
        return (deposit ? "storing " : "taking ") + (item == null ? "items" : Ids.name(item)) + (deposit ? " in" : " from") + " a chest";
    }

    @Override
    protected Result step(CompanionEntity c) {
        if (chest == null) {
            chest = choose(c);
            if (chest == null) return fail(takeProblem != null ? "No chest it may take from nearby: " + takeProblem + "."
                    : deposit ? "No chest or barrel of its own within 24 blocks (craft and place one first)." : "No chest nearby holds that.");
        }
        if (!c.canReach(chest)) {
            if (approach(c, chest, 2.5) == Move.FAILED) return fail("Couldn't reach the chest at " + chest.toShortString() + ".");
            return null;
        }
        if (!(c.getWorld().getBlockEntity(chest) instanceof Inventory container)) {
            CompanionBrain brain = c.brain();
            if (brain != null) brain.memory().forgetChest(dimension(c), chest.getX(), chest.getY(), chest.getZ());
            return fail("There's no chest at " + chest.toShortString() + " any more.");
        }
        if (!deposit) {
            String problem = mayTake(c, chest);
            if (problem != null) return fail("Won't take from that chest: " + problem + ".");
        }
        c.getNavigation().stop();
        c.getLookControl().lookAt(chest.toCenterPos());
        c.swingHand(net.minecraft.util.Hand.MAIN_HAND);
        int moved = deposit ? move(c.getInventory(), container) : move(container, c.getInventory());
        container.markDirty();
        remember(c, chest, container);
        String what = item == null ? "items" : Ids.name(item);
        return moved > 0 ? ok((deposit ? "Stored " : "Took ") + moved + " " + what + (deposit ? " in" : " from") + " the chest at " + chest.toShortString() + ".")
                : fail(deposit ? "Nothing to store, or the chest is full." : "The chest has no " + what + ".");
    }

    @Nullable
    private String mayTake(CompanionEntity c, BlockPos pos) {
        CompanionBrain brain = c.brain();
        String owner = BlockOwnership.get(c.serverWorld()).owner(pos);
        boolean permission = brain != null && owner != null && brain.memory().chestPermissions.contains(owner.toLowerCase());
        return BreakPolicy.checkTake(c, pos, permission, ModConfig.get().mischiefLevel().equals("mean"));
    }

    @Nullable
    private BlockPos choose(CompanionEntity c) {
        CompanionBrain brain = c.brain();
        String dim = dimension(c);
        BlockPos here = c.getBlockPos();
        // Taking a specific item: go to where memory says it is.
        if (!deposit && item != null && brain != null) {
            for (CompanionMemory.ChestRecord r : brain.memory().chestsWith(Ids.name(item))) {
                BlockPos p = new BlockPos(r.x, r.y, r.z);
                if (r.dimension.equals(dim) && p.getSquaredDistance(here) < 96 * 96 && mayTake(c, p) == null) return p;
            }
        }
        // Otherwise the nearest suitable container: its own first for storing, anything it may use for taking.
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        String self = BlockOwnership.companionOwner(c.getCharacterId());
        BlockOwnership owners = BlockOwnership.get(c.serverWorld());
        int r = deposit ? 24 : 16;
        for (BlockPos p : BlockPos.iterate(here.add(-r, -6, -r), here.add(r, 6, r))) {
            BlockEntity be = c.getWorld().getBlockEntity(p);
            if (!(be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity)) continue;
            if (deposit && !self.equals(owners.owner(p))) continue;
            if (!deposit) {
                String problem = mayTake(c, p);
                if (problem != null) {
                    takeProblem = problem;
                    continue;
                }
                if (item != null && !contains((Inventory) be, item)) continue;
            }
            double d = p.getSquaredDistance(here);
            if (d < bestD) {
                bestD = d;
                best = p.toImmutable();
            }
        }
        return best;
    }

    private static boolean contains(Inventory inv, Item item) {
        for (int i = 0; i < inv.size(); i++) if (inv.getStack(i).isOf(item)) return true;
        return false;
    }

    /** Updates the chest index with what this chest holds now. */
    private void remember(CompanionEntity c, BlockPos pos, Inventory container) {
        CompanionBrain brain = c.brain();
        if (brain == null) return;
        CompanionMemory.ChestRecord record = new CompanionMemory.ChestRecord();
        record.dimension = dimension(c);
        record.x = pos.getX();
        record.y = pos.getY();
        record.z = pos.getZ();
        String owner = BlockOwnership.get(c.serverWorld()).owner(pos);
        record.owner = owner == null ? "natural" : owner.equals(BlockOwnership.companionOwner(c.getCharacterId())) ? "self" : owner;
        CompanionMemory.ChestRecord old = brain.memory().chests.get(CompanionMemory.key(record.dimension, record.x, record.y, record.z));
        record.label = !label.isBlank() ? label : old != null ? old.label : "";
        Map<String, Integer> contents = new LinkedHashMap<>();
        for (int i = 0; i < container.size(); i++) {
            ItemStack s = container.getStack(i);
            if (!s.isEmpty()) contents.merge(Ids.name(s.getItem()), s.getCount(), Integer::sum);
        }
        record.contents = contents;
        record.seen = System.currentTimeMillis();
        brain.memory().recordChest(record);
        brain.saveLater();
    }

    private static String dimension(CompanionEntity c) {
        return c.getWorld().getRegistryKey().getValue().toString();
    }

    private int move(Inventory from, Inventory to) {
        int moved = 0;
        for (int i = 0; i < from.size() && moved < count; i++) {
            ItemStack s = from.getStack(i);
            if (s.isEmpty() || item != null && !s.isOf(item)) continue;
            int amount = Math.min(s.getCount(), count - moved);
            ItemStack moving = s.copyWithCount(amount);
            int leftover = insert(to, moving);
            int actually = amount - leftover;
            s.decrement(actually);
            moved += actually;
            if (leftover > 0) break;
        }
        return moved;
    }

    private static int insert(Inventory to, ItemStack stack) {
        for (int i = 0; i < to.size() && !stack.isEmpty(); i++) {
            ItemStack slot = to.getStack(i);
            if (slot.isEmpty()) {
                to.setStack(i, stack.copy());
                stack.setCount(0);
            } else if (ItemStack.canCombine(slot, stack) && slot.getCount() < slot.getMaxCount()) {
                int add = Math.min(stack.getCount(), slot.getMaxCount() - slot.getCount());
                slot.increment(add);
                stack.decrement(add);
            }
        }
        return stack.getCount();
    }
}
