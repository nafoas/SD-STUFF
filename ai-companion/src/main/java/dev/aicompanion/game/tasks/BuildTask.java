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
import net.minecraft.util.math.Vec3d;

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
    /** Leave out blocks it has no materials for instead of stopping (they can be added on a later run). */
    private boolean skipMissing;
    private int missingSkipped;
    private final Map<Item, Integer> missingItems = new LinkedHashMap<>();
    /** Runs after a normal finish (on the server thread); its text is added to the result. */
    private java.util.function.Function<CompanionEntity, String> afterwards;
    private int timeLimitTicks = 20 * 60 * 10;
    /** Ticks spent trying to get to the current block; blocks that take too long go to the back of the queue once. */
    private int approachTicks;
    private final java.util.Set<BlockPos> deferred = new java.util.HashSet<>();
    /** Throwaway blocks it stood on to reach high places (like a player's scaffolding); taken down afterwards. */
    private final List<BlockPos> scaffold = new ArrayList<>();
    private BlockPos pillarBase;
    private boolean cleanedUp;
    private long startedAt = System.currentTimeMillis();
    /** Build order phases (design builds: roof before ceilings, furniture last). */
    private java.util.function.ToIntFunction<Placement> phase;

    public BuildTask(String label, String purpose, List<Placement> placements) {
        this.label = label;
        this.purpose = purpose == null ? "" : purpose;
        this.all = List.copyOf(placements);
        List<Placement> sorted = new ArrayList<>(placements);
        // Bottom layer first; things that hang on a wall or sit on a block (torches, lanterns, ladders) go in last.
        sorted.sort(Comparator.comparingInt((Placement p) -> attached(p.state()) ? 1 : 0).thenComparingInt(p -> p.pos().getY())
                .thenComparingInt(p -> p.pos().getX()).thenComparingInt(p -> p.pos().getZ()));
        this.remaining = sorted;
        this.total = sorted.size();
    }

    public BuildTask skipMissing(boolean skip) {
        this.skipMissing = skip;
        return this;
    }

    /** Orders the build by phase first (then bottom-up as usual). */
    public BuildTask phases(java.util.function.ToIntFunction<Placement> phase) {
        this.phase = phase;
        remaining.sort(Comparator.comparingInt((Placement p) -> attached(p.state()) ? 1 : 0).thenComparingInt(phase)
                .thenComparingInt(p -> p.pos().getY()).thenComparingInt(p -> p.pos().getX()).thenComparingInt(p -> p.pos().getZ()));
        return this;
    }

    /** Clear up throwaway blocks placed since this time (default: since this task started). */
    public BuildTask cleanupSince(long time) {
        if (time > 0) this.startedAt = Math.min(startedAt, time);
        return this;
    }

    public BuildTask timeLimitSeconds(int seconds) {
        this.timeLimitTicks = seconds * 20;
        return this;
    }

    public BuildTask afterwards(java.util.function.Function<CompanionEntity, String> hook) {
        this.afterwards = hook;
        return this;
    }

    private static boolean attached(BlockState s) {
        return s.isOf(net.minecraft.block.Blocks.LANTERN) || s.isOf(net.minecraft.block.Blocks.SOUL_LANTERN) || s.isOf(net.minecraft.block.Blocks.SOUL_WALL_TORCH) || s.isOf(net.minecraft.block.Blocks.LADDER)
                || s.isOf(net.minecraft.block.Blocks.WALL_TORCH) || s.isOf(net.minecraft.block.Blocks.TORCH);
    }

    /**
     * The block that's there counts as the one wanted: same block, facing and half. Connections (panes, fences),
     * stair corner shapes, open/powered and so on settle by themselves once placed.
     */
    public static boolean matches(BlockState current, BlockState wanted) {
        if (wanted.isAir()) return current.isAir();
        if (current.getBlock() != wanted.getBlock()) return false;
        for (var prop : List.of(Properties.HORIZONTAL_FACING, Properties.FACING, Properties.BLOCK_HALF, Properties.DOUBLE_BLOCK_HALF,
                Properties.BED_PART, Properties.SLAB_TYPE, Properties.AXIS, Properties.HORIZONTAL_AXIS)) {
            if (wanted.contains(prop) && current.contains(prop) && !current.get(prop).equals(wanted.get(prop))) return false;
        }
        return true;
    }

    @Override
    public String describe() {
        return "building " + label + " (" + placed + "/" + total + " blocks)";
    }

    /** Materials still needed beyond what the companion carries, or empty if it has everything. */
    public static Map<Item, Integer> missingMaterials(CompanionEntity c, List<Placement> placements) {
        Map<Item, Integer> needed = new LinkedHashMap<>();
        for (Placement p : placements) {
            if (p.state().isAir() || matches(c.getWorld().getBlockState(p.pos()), p.state())) continue;
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
        if (ticks > timeLimitTicks + 20 * 60 || ticks > timeLimitTicks && scaffold.isEmpty()) return finish(c, "ran out of time");
        if (ticks > timeLimitTicks) return takeDownScaffold(c) ? null : finish(c, "ran out of time");
        while (!remaining.isEmpty()) {
            Placement next = remaining.get(0);
            BlockState current = c.getWorld().getBlockState(next.pos());
            if (matches(current, next.state())) {
                remaining.remove(0);
                continue;
            }
            // Out of reach: first do whatever else in this stage and layer it can reach from where it stands.
            if (!c.canReach(next.pos()) && c.isOnGround() && pillarBase == null) {
                int stage = phase == null ? 0 : phase.applyAsInt(next);
                int maxY = next.pos().getY() + (scaffold.isEmpty() ? 0 : 1);
                for (int i = 1; i < remaining.size() && i < 400; i++) {
                    Placement other = remaining.get(i);
                    if (other.pos().getY() > maxY || (phase == null ? 0 : phase.applyAsInt(other)) != stage || attached(other.state()) != attached(next.state())) continue;
                    if (c.canReach(other.pos()) && !matches(c.getWorld().getBlockState(other.pos()), other.state())
                            && !c.getBoundingBox().intersects(new net.minecraft.util.math.Box(other.pos()))) {
                        remaining.remove(i);
                        remaining.add(0, other);
                        next = other;
                        current = c.getWorld().getBlockState(next.pos());
                        break;
                    }
                }
            }
            // Up on the scaffold but the next block is somewhere else: come down first.
            if (!scaffold.isEmpty() && !c.canReach(next.pos()) && (horizontal(c, next.pos()) > 3.0 || next.pos().getY() < c.getBlockY() - 1)) {
                if (takeDownScaffold(c)) return null;
            }
            if (!c.canReach(next.pos()) && next.pos().getY() > c.getEyeY() + 1.5 && horizontal(c, next.pos()) <= 3.0) {
                // Too high to reach: stand on throwaway blocks, like a player would.
                if (pillarUp(c)) return null;
            }
            if (!c.canReach(next.pos())) {
                if (pillarBase == null && next.pos().getY() > c.getEyeY() + 1.5) {
                    BlockPos spot = scaffoldSpot(c, next.pos());
                    if (spot != null && approach(c, Vec3d.ofBottomCenter(spot), 0.45) != Move.FAILED) {
                        if (++approachTicks > 300) {
                            approachTicks = 0;
                            remaining.remove(0);
                            resetMovement();
                            if (deferred.add(next.pos())) remaining.add(next);
                            else {
                                skipped++;
                                if (problems.size() < 3) problems.add("couldn't get up to " + next.pos().toShortString());
                            }
                        }
                        return null;
                    }
                }
                Move move = approach(c, next.pos(), 2.5);
                if (move != Move.FAILED && ++approachTicks > 300) {
                    // Taking too long to get there: try the rest first, come back to it at the end.
                    approachTicks = 0;
                    remaining.remove(0);
                    resetMovement();
                    if (deferred.add(next.pos())) {
                        remaining.add(next);
                        return null;
                    }
                    skipped++;
                    if (problems.size() < 3) problems.add("couldn't get to " + next.pos().toShortString());
                    return null;
                }
                if (move == Move.FAILED) {
                    approachTicks = 0;
                    skipped++;
                    if (problems.size() < 3) problems.add("couldn't reach " + next.pos().toShortString());
                    remaining.remove(0);
                    resetMovement();
                }
                return null;
            }
            c.getNavigation().stop();
            approachTicks = 0;
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
                if ((isNatural(current) || ownBlock(c, next.pos())) && BreakPolicy.allowed(c, next.pos(), BreakPolicy.Purpose.GATHER)) {
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
                if (!skipMissing) return finish(c, "ran out of " + Ids.name(next.state().getBlock().asItem()));
                missingSkipped++;
                missingItems.merge(next.state().getBlock().asItem(), 1, Integer::sum);
                remaining.remove(0);
                continue;
            }
            placed++;
            remaining.remove(0);
            resetMovement();
            placeCooldown = 3; // about five blocks a second, like a quick player
            return null;
        }
        if (takeDownScaffold(c)) return null;
        if (!cleanedUp) {
            cleanedUp = true;
            if (queueCleanup(c)) return null;
        }
        return finish(c, "finished");
    }

    /**
     * Throwaway blocks it put down around the build to climb on (pillars from getting about) come out again,
     * unless the design wants that very block there. Returns true if there's something to clear.
     */
    private boolean queueCleanup(CompanionEntity c) {
        if (all.size() < 20) return false;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        java.util.Map<BlockPos, BlockState> wanted = new java.util.HashMap<>();
        for (Placement p : all) {
            wanted.put(p.pos(), p.state());
            minX = Math.min(minX, p.pos().getX()); maxX = Math.max(maxX, p.pos().getX());
            minY = Math.min(minY, p.pos().getY()); maxY = Math.max(maxY, p.pos().getY());
            minZ = Math.min(minZ, p.pos().getZ()); maxZ = Math.max(maxZ, p.pos().getZ());
        }
        for (Map.Entry<BlockPos, Long> e : c.recentFiller().entrySet()) {
            BlockPos p = e.getKey();
            if (e.getValue() < startedAt || p.getX() < minX - 3 || p.getX() > maxX + 3 || p.getZ() < minZ - 3 || p.getZ() > maxZ + 3
                    || p.getY() < minY - 2 || p.getY() > maxY + 1) continue;
            BlockState s = c.getWorld().getBlockState(p);
            if (s.isAir() || !CompanionEntity.isFiller(s.getBlock().asItem())) continue;
            BlockState want = wanted.get(p);
            if (want != null && want.getBlock() == s.getBlock()) continue;
            remaining.add(new Placement(p, net.minecraft.block.Blocks.AIR.getDefaultState()));
        }
        // Top down, so it never stands on what it's about to remove.
        remaining.sort(Comparator.comparingInt((Placement p) -> -p.pos().getY()));
        return !remaining.isEmpty();
    }

    private static double horizontal(CompanionEntity c, BlockPos p) {
        double dx = p.getX() + 0.5 - c.getX(), dz = p.getZ() + 0.5 - c.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Jumps and puts a throwaway block underneath. Returns false if it can't (no blocks, no room overhead, too tall). */
    private boolean pillarUp(CompanionEntity c) {
        if (pillarBase == null) {
            if (!c.isOnGround() || c.fillerCount() < 1 || scaffold.size() >= 12) return false;
            BlockPos feet = c.getBlockPos();
            for (int dy = 2; dy <= 3; dy++) if (!c.getWorld().getBlockState(feet.up(dy)).getCollisionShape(c.getWorld(), feet.up(dy)).isEmpty()) return false;
            if (!c.getWorld().getBlockState(feet).isReplaceable()) return false;
            pillarBase = feet;
        }
        c.getNavigation().stop();
        c.getMoveControl().moveTo(pillarBase.getX() + 0.5, c.getY(), pillarBase.getZ() + 0.5, 0.6);
        if (c.isOnGround() && c.getY() < pillarBase.getY() + 0.5) c.getJumpControl().setActive();
        if (c.getY() >= pillarBase.getY() + 1.0 && !c.getBoundingBox().intersects(new net.minecraft.util.math.Box(pillarBase))) {
            if (c.placeFiller(pillarBase)) scaffold.add(pillarBase);
            pillarBase = null;
        } else if (c.isOnGround() && c.getY() < pillarBase.getY() + 0.5 && Vec3d.ofBottomCenter(pillarBase).squaredDistanceTo(c.getX(), pillarBase.getY(), c.getZ()) > 0.8 * 0.8) {
            pillarBase = null; // slid off; start again from where it stands
        }
        return true;
    }

    /**
     * Somewhere to build a scaffold from: an open column near below the target, with nothing overhead up to its level.
     * Null if there's none.
     */
    private BlockPos scaffoldSpot(CompanionEntity c, BlockPos target) {
        var world = c.getWorld();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        int baseY = c.getBlockY();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos p = new BlockPos(target.getX() + dx, baseY + dy, target.getZ() + dz);
                    if (target.getY() - p.getY() > 12) continue;
                    if (world.getBlockState(p.down()).getCollisionShape(world, p.down()).isEmpty()) continue;
                    boolean open = true;
                    for (int y = p.getY(); y < target.getY() && open; y++) {
                        BlockPos q = new BlockPos(p.getX(), y, p.getZ());
                        if (q.equals(target)) continue;
                        open = world.getBlockState(q).getCollisionShape(world, q).isEmpty();
                    }
                    if (!open) continue;
                    double d = Math.abs(dx) + Math.abs(dz) + c.getPos().squaredDistanceTo(Vec3d.ofBottomCenter(p)) * 0.02 + (dx == 0 && dz == 0 ? 3 : 0);
                    if (d < bestDist) {
                        bestDist = d;
                        best = p;
                    }
                }
            }
        }
        return best;
    }

    /** Takes the scaffold down, top first. Returns true while there's still some to take down. */
    private boolean takeDownScaffold(CompanionEntity c) {
        pillarBase = null;
        while (!scaffold.isEmpty()) {
            BlockPos top = scaffold.get(scaffold.size() - 1);
            if (c.getWorld().getBlockState(top).isReplaceable()) {
                scaffold.remove(scaffold.size() - 1);
                continue;
            }
            if (!c.canReach(top)) {
                Move move = approach(c, top, 2.5);
                if (move == Move.FAILED) {
                    scaffold.remove(scaffold.size() - 1);
                    resetMovement();
                }
                return true;
            }
            c.getNavigation().stop();
            if (c.mineStep(top)) scaffold.remove(scaffold.size() - 1);
            return true;
        }
        return false;
    }

    private Result finish(CompanionEntity c, String reason) {
        if (placed > 0 && total > 1) remember(c);
        Result r = finish(reason);
        if (missingSkipped > 0) {
            StringBuilder sb = new StringBuilder();
            missingItems.forEach((item, n) -> sb.append(sb.isEmpty() ? "" : ", ").append(n).append(" ").append(Ids.name(item)));
            r = new Result(r.success(), r.message() + " Left out for lack of materials: " + sb + ".");
        }
        if (afterwards != null) {
            try {
                String extra = afterwards.apply(c);
                if (extra != null && !extra.isBlank()) r = new Result(r.success(), r.message() + " " + extra);
            } catch (Exception e) {
                dev.aicompanion.AiCompanionMod.LOGGER.warn("After-build step failed", e);
            }
        }
        return r;
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

    /** Its own blocks can be reworked (a roof edge where a new room joins, a wall turned into a doorway). */
    private static boolean ownBlock(CompanionEntity c, BlockPos pos) {
        String owner = dev.aicompanion.world.BlockOwnership.get(c.serverWorld()).owner(pos);
        return owner != null && owner.equals(dev.aicompanion.world.BlockOwnership.companionOwner(c.getCharacterId()));
    }

    private static boolean isNatural(BlockState s) {
        return s.isIn(BlockTags.DIRT) || s.isIn(BlockTags.LEAVES) || s.isIn(BlockTags.BASE_STONE_OVERWORLD) || s.isIn(BlockTags.SAND)
                || s.isIn(BlockTags.FLOWERS) || s.isIn(BlockTags.SAPLINGS) || s.isIn(BlockTags.SNOW) || s.isOf(net.minecraft.block.Blocks.GRAVEL)
                || s.isOf(net.minecraft.block.Blocks.TALL_GRASS) || s.isOf(net.minecraft.block.Blocks.GRASS);
    }
}
