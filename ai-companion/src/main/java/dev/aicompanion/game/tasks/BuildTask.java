package dev.aicompanion.game.tasks;

import dev.aicompanion.entity.CompanionEntity;
import dev.aicompanion.game.Ids;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.state.property.Properties;
import net.minecraft.item.Item;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;

import dev.aicompanion.world.BreakPolicy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Places a list of blocks, bottom layer first, using materials from the companion's inventory.
 * Clears natural blocks (grass, dirt, stone, leaves, plants) that are in the way; never breaks anything else.
 */
public class BuildTask extends Task {
    public record Placement(BlockPos pos, BlockState state) {}

    private final String label;
    private final String purpose;
    private final List<Placement> all;
    private final List<Placement> remaining;
    private final int total;
    private int placed;
    private int skipped;
    private int blockedTicks;
    private int placeCooldown;
    private final List<String> problems = new ArrayList<>();

    public BuildTask(String label, String purpose, List<Placement> placements) {
        this.label = label;
        this.purpose = purpose == null ? "" : purpose;
        this.all = List.copyOf(placements);
        List<Placement> sorted = new ArrayList<>(placements);
        sorted.sort(Comparator.comparingInt((Placement p) -> p.pos().getY()).thenComparingInt(p -> p.pos().getX()).thenComparingInt(p -> p.pos().getZ()));
        this.remaining = sorted;
        this.total = sorted.size();
    }

    @Override
    public String describe() {
        return "building " + label + " (" + placed + "/" + total + " blocks)";
    }

    /** Materials still needed beyond what the companion carries, or empty if it has everything. */
    public static Map<Item, Integer> missingMaterials(CompanionEntity c, List<Placement> placements) {
        Map<Item, Integer> needed = new LinkedHashMap<>();
        for (Placement p : placements) {
            if (c.getWorld().getBlockState(p.pos()).equals(p.state()) || p.state().isAir()) continue;
            BlockState st = p.state();
            // The second half of doors and beds comes free with the first.
            if (st.contains(Properties.DOUBLE_BLOCK_HALF) && st.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) continue;
            if (st.contains(Properties.BED_PART) && st.get(Properties.BED_PART) == BedPart.HEAD) continue;
            needed.merge(p.state().getBlock().asItem(), 1, Integer::sum);
        }
        Map<Item, Integer> missing = new LinkedHashMap<>();
        needed.forEach((item, n) -> {
            int have = c.count(item);
            if (have < n) missing.put(item, n - have);
        });
        return missing;
    }

    @Override
    protected Result step(CompanionEntity c) {
        if (ticks > 20 * 60 * 10) return finish(c, "ran out of time");
        while (!remaining.isEmpty()) {
            Placement next = remaining.get(0);
            BlockState current = c.getWorld().getBlockState(next.pos());
            if (current.equals(next.state())) {
                remaining.remove(0);
                continue;
            }
            if (!c.canReach(next.pos())) {
                Move move = approach(c, next.pos(), 2.5);
                if (move == Move.FAILED) {
                    skipped++;
                    if (problems.size() < 3) problems.add("couldn't reach " + next.pos().toShortString());
                    remaining.remove(0);
                    resetMovement();
                }
                return null;
            }
            c.getNavigation().stop();
            if (next.state().isAir()) {
                String denied = BreakPolicy.check(c, next.pos(), BreakPolicy.Purpose.GATHER);
                if (denied != null) {
                    skipped++;
                    if (problems.size() < 3) problems.add("left " + Ids.name(current.getBlock()) + " at " + next.pos().toShortString() + " (" + denied + ")");
                    remaining.remove(0);
                    return null;
                }
                if (c.mineStep(next.pos())) remaining.remove(0);
                return null;
            }
            if (!current.isReplaceable() && !current.isAir()) {
                if (isNatural(current) && BreakPolicy.allowed(c, next.pos(), BreakPolicy.Purpose.GATHER)) {
                    c.mineStep(next.pos());
                    return null;
                }
                skipped++;
                if (problems.size() < 3) problems.add(Ids.name(current.getBlock()) + " in the way at " + next.pos().toShortString());
                remaining.remove(0);
                continue;
            }
            net.minecraft.util.math.Box space = new net.minecraft.util.math.Box(next.pos());
            if (c.getBoundingBox().intersects(space)) {
                // Standing where the block goes: step aside.
                approach(c, next.pos().add(2, 0, 0), 1.0);
                return null;
            }
            if (!next.state().getCollisionShape(c.getWorld(), next.pos()).isEmpty()
                    && !c.getWorld().getEntitiesByClass(net.minecraft.entity.LivingEntity.class, space, e -> e != c && e.isAlive()).isEmpty()) {
                // Never build a block inside someone. Wait a little for them to move, then skip the spot.
                if (++blockedTicks < 60) return null;
                blockedTicks = 0;
                skipped++;
                if (problems.size() < 3) problems.add("someone was standing at " + next.pos().toShortString());
                remaining.remove(0);
                return null;
            }
            blockedTicks = 0;
            if (placeCooldown-- > 0) return null;
            if (!c.placeBlock(next.pos(), next.state())) {
                return finish(c, "ran out of " + Ids.name(next.state().getBlock().asItem()));
            }
            placed++;
            remaining.remove(0);
            resetMovement();
            placeCooldown = 3; // about five blocks a second, like a quick player
            return null;
        }
        return finish(c, "finished");
    }

    private Result finish(CompanionEntity c, String reason) {
        if (placed > 0 && total > 1) remember(c);
        return finish(reason);
    }

    /** Remembers what was built and where; homes, farms, mines and storage also become named places. */
    private void remember(CompanionEntity c) {
        var brain = c.brain();
        if (brain == null) return;
        String dim = c.getWorld().getRegistryKey().getValue().toString();
        dev.aicompanion.ai.CompanionMemory.Structure st = new dev.aicompanion.ai.CompanionMemory.Structure();
        st.label = label;
        st.purpose = purpose;
        st.dimension = dim;
        st.minX = st.minY = st.minZ = Integer.MAX_VALUE;
        st.maxX = st.maxY = st.maxZ = Integer.MIN_VALUE;
        for (Placement p : all) {
            st.minX = Math.min(st.minX, p.pos().getX()); st.maxX = Math.max(st.maxX, p.pos().getX());
            st.minY = Math.min(st.minY, p.pos().getY()); st.maxY = Math.max(st.maxY, p.pos().getY());
            st.minZ = Math.min(st.minZ, p.pos().getZ()); st.maxZ = Math.max(st.maxZ, p.pos().getZ());
        }
        st.built = System.currentTimeMillis();
        brain.memory().addStructure(st);
        if (List.of("home", "farm", "mine", "storage").contains(purpose)) {
            var loc = new dev.aicompanion.ai.CompanionMemory.Location(dim, (st.minX + st.maxX) / 2, st.minY, (st.minZ + st.maxZ) / 2);
            loc.type = purpose;
            loc.note = label;
            String key = label.toLowerCase();
            brain.memory().places.put(key, loc);
        }
        brain.saveLater();
    }

    private Result finish(String reason) {
        String summary = "Placed " + placed + " of " + total + " blocks for " + label + " (" + reason + ")";
        if (skipped > 0) summary += "; skipped " + skipped + ": " + String.join("; ", problems);
        return placed > 0 || total == 0 ? ok(summary + ".") : fail(summary + ".");
    }

    private static boolean isNatural(BlockState s) {
        return s.isIn(BlockTags.DIRT) || s.isIn(BlockTags.LEAVES) || s.isIn(BlockTags.BASE_STONE_OVERWORLD) || s.isIn(BlockTags.SAND)
                || s.isIn(BlockTags.FLOWERS) || s.isIn(BlockTags.SAPLINGS) || s.isIn(BlockTags.SNOW) || s.isOf(net.minecraft.block.Blocks.GRAVEL)
                || s.isOf(net.minecraft.block.Blocks.TALL_GRASS) || s.isOf(net.minecraft.block.Blocks.GRASS);
    }
}
