package dev.aicompanion.game.tasks;

import dev.aicompanion.entity.CompanionEntity;
import dev.aicompanion.game.Ids;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/** Puts items into, or takes items out of, the nearest chest or barrel. */
public class ChestTask extends Task {
    private final boolean deposit;
    @Nullable private final Item item;
    private final int count;
    private BlockPos chest;

    public ChestTask(boolean deposit, @Nullable Item item, int count) {
        this.deposit = deposit;
        this.item = item;
        this.count = count <= 0 ? Integer.MAX_VALUE : count;
    }

    @Override
    public String describe() {
        return (deposit ? "storing " : "taking ") + (item == null ? "items" : Ids.name(item)) + (deposit ? " in" : " from") + " a chest";
    }

    @Override
    protected Result step(CompanionEntity c) {
        if (chest == null) {
            BlockPos origin = c.getBlockPos();
            double bestD = Double.MAX_VALUE;
            for (BlockPos p : BlockPos.iterate(origin.add(-16, -6, -16), origin.add(16, 6, 16))) {
                BlockEntity be = c.getWorld().getBlockEntity(p);
                if ((be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity) && p.getSquaredDistance(origin) < bestD) {
                    bestD = p.getSquaredDistance(origin);
                    chest = p.toImmutable();
                }
            }
            if (chest == null) return fail("No chest or barrel within 16 blocks.");
        }
        if (!c.canReach(chest)) {
            if (approach(c, chest, 2.5) == Move.FAILED) return fail("Couldn't reach the chest.");
            return null;
        }
        if (!(c.getWorld().getBlockEntity(chest) instanceof Inventory container)) return fail("The chest is gone.");
        c.getNavigation().stop();
        c.getLookControl().lookAt(chest.toCenterPos());
        c.swingHand(net.minecraft.util.Hand.MAIN_HAND);
        int moved = deposit ? move(c.getInventory(), container) : move(container, c.getInventory());
        container.markDirty();
        String what = item == null ? "items" : Ids.name(item);
        return moved > 0 ? ok((deposit ? "Stored " : "Took ") + moved + " " + what + (deposit ? " in" : " from") + " the chest at " + chest.toShortString() + ".")
                : fail(deposit ? "Nothing to store, or the chest is full." : "The chest has no " + what + ".");
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
