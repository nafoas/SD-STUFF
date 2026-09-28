package dev.aicompanion.world;

import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.gen.structure.Structure;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Looks at the blocks around a position the way a player would and decides whether it's part of someone's build.
 * Traces the connected build (crafted blocks, plus logs/stone/cobble joined to them, plus anything placed by someone)
 * and classifies it: a home (has a bed), a sizeable build, or just a few stray blocks.
 * Naturally generated structures (villages, temples, mineshafts...) don't count unless a player has added to them.
 */
public final class BuildAwareness {
    private BuildAwareness() {}

    public enum Kind { NONE, JUNK, BUILD, HOME }

    /** What a block belongs to. owner is a player/companion name when known. */
    public record Verdict(Kind kind, @Nullable String owner, int size) {
        public boolean protectedFrom(String selfOwner) {
            if (kind != Kind.HOME && kind != Kind.BUILD) return false;
            return owner == null || !owner.equals(selfOwner);
        }
    }

    private static final int MAX_TRACE = 1500;
    private static final int BUILD_SIZE = 20;
    private static final long CACHE_MS = 30_000;

    private record Cached(Verdict verdict, long time) {}

    private static final Map<Long, Cached> CACHE = new HashMap<>();

    public static synchronized Verdict classify(ServerWorld world, BlockPos pos) {
        long now = System.currentTimeMillis();
        Cached cached = CACHE.get(pos.asLong());
        if (cached != null && now - cached.time() < CACHE_MS) return cached.verdict();
        if (CACHE.size() > 50_000) CACHE.clear();

        BlockOwnership owners = BlockOwnership.get(world);
        if (!isStructural(world, pos, owners)) return new Verdict(Kind.NONE, null, 0);

        // Flood-fill the connected build.
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(pos.toImmutable());
        seen.add(pos.toImmutable());
        boolean hasBed = false;
        boolean hasCrafted = false;
        Map<String, Integer> ownerCounts = new HashMap<>();
        while (!queue.isEmpty() && seen.size() < MAX_TRACE) {
            BlockPos p = queue.poll();
            BlockState s = world.getBlockState(p);
            if (s.getBlock() instanceof BedBlock) hasBed = true;
            if (isCrafted(s)) hasCrafted = true;
            String o = owners.owner(p);
            if (o != null) ownerCounts.merge(o, 1, Integer::sum);
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos n = p.add(dx, dy, dz);
                        if (!seen.contains(n) && isStructural(world, n, owners)) {
                            seen.add(n);
                            queue.add(n);
                        }
                    }
        }
        String owner = ownerCounts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        boolean anyoneAdded = !ownerCounts.isEmpty();
        Kind kind;
        if (!anyoneAdded && inGeneratedStructure(world, pos)) kind = Kind.NONE; // village house, temple, mineshaft...
        else if (hasBed) kind = Kind.HOME;
        else if (hasCrafted && seen.size() >= BUILD_SIZE) kind = Kind.BUILD;
        else kind = Kind.JUNK;
        Verdict verdict = new Verdict(kind, owner, seen.size());
        // The whole build shares one verdict.
        for (BlockPos p : seen) CACHE.put(p.asLong(), new Cached(verdict, now));
        return verdict;
    }

    /** A player-style look at the build around pos: how big, what it's made of, and what's in it. */
    public static String describe(ServerWorld world, BlockPos around) {
        BlockOwnership owners = BlockOwnership.get(world);
        BlockPos start = null;
        double best = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.iterate(around.add(-8, -4, -8), around.add(8, 8, 8))) {
            if (isCrafted(world.getBlockState(p)) && p.getSquaredDistance(around) < best) {
                best = p.getSquaredDistance(around);
                start = p.toImmutable();
            }
        }
        if (start == null) return "There's no build here, just natural terrain.";
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        Map<String, Integer> materials = new HashMap<>();
        Map<String, Integer> features = new HashMap<>();
        Map<String, Integer> builders = new HashMap<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        while (!queue.isEmpty() && seen.size() < 6000) {
            BlockPos p = queue.poll();
            BlockState s = world.getBlockState(p);
            String id = Registries.BLOCK.getId(s.getBlock()).getPath();
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
            boolean bedHead = s.getBlock() instanceof BedBlock && s.get(net.minecraft.state.property.Properties.BED_PART) == net.minecraft.block.enums.BedPart.HEAD;
            String feature = bedHead ? null : s.getBlock() instanceof BedBlock ? "bed" : id.contains("chest") || id.contains("barrel") ? "storage"
                    : s.isIn(BlockTags.DOORS) ? "door" : id.contains("glass") ? "window" : id.contains("torch") || id.contains("lantern") || id.contains("lamp") ? "light"
                    : id.contains("furnace") || id.contains("crafting") || id.contains("smoker") || id.contains("anvil") ? "workstation"
                    : s.isIn(BlockTags.STAIRS) ? "stairs" : id.contains("ladder") ? "ladder" : id.contains("flower_pot") || s.isIn(BlockTags.FLOWERS) ? "plants" : null;
            if (feature != null) features.merge(feature, 1, Integer::sum);
            materials.merge(id, 1, Integer::sum);
            String o = owners.owner(p);
            if (o != null) builders.merge(o.startsWith(BlockOwnership.COMPANION_PREFIX) ? "a companion" : o, 1, Integer::sum);
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos n = p.add(dx, dy, dz);
                        if (!seen.contains(n) && isStructural(world, n, owners)) {
                            seen.add(n);
                            queue.add(n);
                        }
                    }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("A build of about ").append(seen.size()).append(" blocks, ").append(maxX - minX + 1).append(" x ").append(maxZ - minZ + 1)
                .append(" wide and ").append(maxY - minY + 1).append(" tall. ");
        List<Map.Entry<String, Integer>> top = new ArrayList<>(materials.entrySet());
        top.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        List<String> mats = new ArrayList<>();
        for (Map.Entry<String, Integer> m : top.subList(0, Math.min(6, top.size()))) mats.add(m.getKey().replace('_', ' '));
        sb.append("Mostly ").append(String.join(", ", mats)).append(" (").append(materials.size()).append(" kinds of block). ");
        if (!features.isEmpty()) {
            List<String> f = new ArrayList<>();
            features.forEach((k, v) -> f.add(v + " " + k + (v > 1 ? "s" : "")));
            sb.append("Has ").append(String.join(", ", f)).append(". ");
        }
        if (!builders.isEmpty()) {
            String who = builders.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
            sb.append("Built mostly by ").append(who).append(". ");
        } else if (inGeneratedStructure(world, start)) {
            sb.append("It's a naturally generated structure. ");
        }
        return sb.toString().trim();
    }

    /** Forget cached verdicts near a block that changed. */
    public static synchronized void invalidate(BlockPos pos) {
        for (BlockPos p : BlockPos.iterate(pos.add(-1, -1, -1), pos.add(1, 1, 1))) CACHE.remove(p.asLong());
    }

    /** Part of a build: crafted blocks, anything someone placed, and logs/stone/cobble touching crafted blocks (log cabins, stone houses). */
    private static boolean isStructural(ServerWorld world, BlockPos pos, BlockOwnership owners) {
        BlockState s = world.getBlockState(pos);
        if (s.isAir() || !s.getFluidState().isEmpty() && s.getCollisionShape(world, pos).isEmpty()) return false;
        if (isCrafted(s) || owners.owner(pos) != null) return true;
        if (s.isIn(BlockTags.LOGS) || isStoneLike(s)) {
            for (BlockPos n : BlockPos.iterate(pos.add(-1, -1, -1), pos.add(1, 1, 1))) {
                if (!n.equals(pos) && isCrafted(world.getBlockState(n))) return true;
            }
        }
        return false;
    }

    private static boolean isStoneLike(BlockState s) {
        String id = Registries.BLOCK.getId(s.getBlock()).getPath();
        return id.equals("cobblestone") || id.equals("mossy_cobblestone") || id.equals("stone") || id.equals("sandstone") || id.equals("deepslate")
                || id.equals("cobbled_deepslate") || id.equals("andesite") || id.equals("diorite") || id.equals("granite");
    }

    /** Blocks made by crafting, which natural terrain doesn't contain. */
    public static boolean isCrafted(BlockState s) {
        if (s.isAir()) return false;
        if (s.isIn(BlockTags.PLANKS) || s.isIn(BlockTags.STAIRS) || s.isIn(BlockTags.SLABS) || s.isIn(BlockTags.WALLS)
                || s.isIn(BlockTags.FENCES) || s.isIn(BlockTags.FENCE_GATES) || s.isIn(BlockTags.DOORS) || s.isIn(BlockTags.TRAPDOORS)
                || s.isIn(BlockTags.BEDS) || s.isIn(BlockTags.WOOL) || s.isIn(BlockTags.WOOL_CARPETS) || s.isIn(BlockTags.ALL_SIGNS)
                || s.isIn(BlockTags.BANNERS) || s.isIn(BlockTags.CANDLES) || s.isIn(BlockTags.RAILS)) return true;
        String id = Registries.BLOCK.getId(s.getBlock()).getPath();
        return id.contains("glass") || id.contains("brick") || id.contains("concrete") || id.contains("polished") || id.contains("glazed")
                || id.contains("chest") || id.contains("barrel") || id.contains("furnace") || id.contains("smoker") || id.contains("bookshelf")
                || id.contains("lantern") || id.contains("torch") || id.contains("ladder") || id.contains("stripped") || id.contains("chiseled")
                || id.startsWith("cut_") || id.startsWith("smooth_") || id.contains("quartz_block") || id.contains("_pillar")
                || id.equals("hay_block") || id.equals("crafting_table") || id.equals("scaffolding") || id.contains("_wood");
    }

    public static boolean inGeneratedStructure(ServerWorld world, BlockPos pos) {
        var accessor = world.getStructureAccessor();
        if (!accessor.hasStructureReferences(pos)) return false;
        for (Structure structure : accessor.getStructureReferences(pos).keySet()) {
            StructureStart start = accessor.getStructureAt(pos, structure);
            if (start != null && start.hasChildren()) return true;
        }
        return false;
    }
}
