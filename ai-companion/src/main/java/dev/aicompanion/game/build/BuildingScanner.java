package dev.aicompanion.game.build;

import dev.aicompanion.game.Ids;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads an existing building into rooms: the enclosed floor space at standing height, split into rooms by doors,
 * with stairs or ladders up to floors above. Rooms that aren't rectangles are kept as their bounding rectangle.
 */
public final class BuildingScanner {
    private BuildingScanner() {}

    private static final int MAX_ROOM_CELLS = 300;
    private static final int MAX_ROOMS = 16;

    private record Region(Set<BlockPos> cells, List<BlockPos> doors, List<BlockPos> holesUp, List<BlockPos> holesDown, int standY) {}

    /**
     * Scans the building around `start` (a spot inside it, at feet height, or right outside its door).
     * Returns null if no enclosed room is found there.
     */
    @Nullable
    public static BuildingModel scan(ServerWorld world, BlockPos start, String name) {
        BlockPos inside = findInside(world, start);
        if (inside == null) return null;
        BuildingModel m = new BuildingModel();
        m.name = name;
        m.dimension = world.getRegistryKey().getValue().toString();
        m.scanned = true;
        m.updated = System.currentTimeMillis();

        Map<BlockPos, BuildingModel.Room> roomOf = new HashMap<>();
        ArrayDeque<BlockPos[]> queue = new ArrayDeque<>(); // {start cell, door or null, from-room marker}
        queue.add(new BlockPos[]{inside, null});
        Map<String, Integer> materials = new HashMap<>();
        Set<BlockPos> doorsDone = new HashSet<>();
        Map<BlockPos, String> pendingLinkFrom = new HashMap<>();
        Map<BlockPos, Integer> levelHint = new HashMap<>();
        Set<BlockPos> stairLinks = new HashSet<>();
        levelHint.put(inside, 0);

        while (!queue.isEmpty() && m.rooms.size() < MAX_ROOMS) {
            BlockPos[] item = queue.poll();
            BlockPos cell = item[0];
            if (roomOf.containsKey(cell)) {
                if (item[1] != null) addLink(m, pendingLinkFrom.get(item[1]), roomOf.get(cell).id, item[1], stairLinks.contains(item[1]) ? "stairs" : "door");
                continue;
            }
            Region region = flood(world, cell);
            if (region == null) {
                // Not enclosed: this door leads outside.
                if (item[1] != null) addLink(m, pendingLinkFrom.get(item[1]), "outside", item[1], "door");
                continue;
            }
            BuildingModel.Room r = toRoom(world, region, m.nextRoomId(), levelHint.getOrDefault(cell, 0));
            m.rooms.add(r);
            for (BlockPos c : region.cells()) roomOf.put(c, r);
            tally(world, r, materials);
            if (item[1] != null) addLink(m, pendingLinkFrom.get(item[1]), r.id, item[1], stairLinks.contains(item[1]) ? "stairs" : "door");
            for (BlockPos door : region.doors()) {
                if (!doorsDone.add(door)) continue;
                pendingLinkFrom.put(door, r.id);
                for (Direction d : Direction.Type.HORIZONTAL) {
                    BlockPos beyond = door.offset(d);
                    if (region.cells().contains(beyond) || !standable(world, beyond)) continue;
                    levelHint.put(beyond, r.level);
                    queue.add(new BlockPos[]{beyond, door});
                }
            }
            for (BlockPos hole : region.holesUp()) {
                if (roomOf.containsKey(hole)) continue;
                pendingLinkFrom.put(hole, r.id);
                stairLinks.add(hole);
                levelHint.put(hole, r.level + 1);
                queue.add(new BlockPos[]{hole, hole});
            }
            for (BlockPos below : region.holesDown()) {
                if (roomOf.containsKey(below)) continue;
                pendingLinkFrom.put(below, r.id);
                stairLinks.add(below);
                levelHint.put(below, r.level - 1);
                queue.add(new BlockPos[]{below, below});
            }
        }
        // Number floors from the lowest one found.
        int lowest = m.rooms.stream().mapToInt(x -> x.level).min().orElse(0);
        for (BuildingModel.Room x : m.rooms) x.level -= lowest;
        pickPalette(m, materials);
        m.markBuilt();
        return m.rooms.isEmpty() ? null : m;
    }

    private static void addLink(BuildingModel m, @Nullable String a, String b, BlockPos at, String type) {
        if (a == null || a.equals(b)) return;
        for (BuildingModel.Link l : m.links) {
            if ((l.a.equals(a) && l.b.equals(b)) || (l.a.equals(b) && l.b.equals(a))) return;
        }
        BuildingModel.Link l = new BuildingModel.Link();
        l.a = a;
        l.b = b;
        l.type = type;
        l.x = at.getX();
        l.y = at.getY();
        l.z = at.getZ();
        l.built = true;
        m.links.add(l);
    }

    /**
     * From a spot near or in the building, a standing cell inside an enclosed space: the one in the biggest room
     * around (so standing on a table or a bookshelf still finds the room's real floor).
     */
    @Nullable
    private static BlockPos findInside(ServerWorld world, BlockPos start) {
        BlockPos best = null;
        int bestSize = 0;
        Set<BlockPos> covered = new HashSet<>();
        for (int r = 0; r <= 4; r++) {
            for (int dy = -2; dy <= 1; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                        BlockPos p = start.add(dx, dy, dz);
                        if (covered.contains(p) || !standable(world, p) || !hasCeiling(world, p)) continue;
                        Region region = flood(world, p);
                        if (region == null) continue;
                        covered.addAll(region.cells());
                        // Prefer the room it's in (same level) unless another is much bigger.
                        int size = region.cells().size() + (Math.abs(dy) <= 1 ? 4 : 0);
                        if (size > bestSize) {
                            bestSize = size;
                            best = p;
                        }
                    }
                }
            }
        }
        return best;
    }

    /** Floor-level flood fill of the enclosed space, stopping at walls and doors. Null if it isn't enclosed. */
    @Nullable
    private static Region flood(ServerWorld world, BlockPos start) {
        if (!standable(world, start) || !hasCeiling(world, start)) return null;
        Set<BlockPos> seen = new HashSet<>();
        List<BlockPos> doors = new ArrayList<>();
        List<BlockPos> holes = new ArrayList<>();
        List<BlockPos> down = new ArrayList<>();
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        seen.add(start);
        q.add(start);
        while (!q.isEmpty()) {
            BlockPos p = q.poll();
            if (seen.size() > MAX_ROOM_CELLS) return null;
            if (!hasCeiling(world, p)) return null; // open sky: outside
            // Stairs or a ladder going up through the ceiling.
            BlockPos hole = holeAbove(world, p);
            if (hole != null) holes.add(hole);
            BlockPos under = stairsDown(world, p);
            if (under != null) down.add(under);
            for (Direction d : Direction.Type.HORIZONTAL) {
                BlockPos n = p.offset(d);
                if (seen.contains(n)) continue;
                BlockState s = world.getBlockState(n);
                if (s.getBlock() instanceof DoorBlock || s.isIn(BlockTags.FENCE_GATES)) {
                    BlockPos lower = s.getBlock() instanceof DoorBlock && s.get(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? n.down() : n;
                    if (!doors.contains(lower)) doors.add(lower);
                    continue;
                }
                if (wallLike(world, n)) continue;
                // Steps of a staircase inside the room count as part of it; so does a one-block step up or down.
                BlockPos next = null;
                if (standable(world, n)) next = n;
                else if (standable(world, n.down()) && !wallLike(world, n.down())) next = n.down();
                if (next == null) {
                    // Furniture or a counter: part of the room, not a wall.
                    if (furniture(s)) {
                        seen.add(n);
                        q.add(n);
                    }
                    continue;
                }
                if (next.getY() != start.getY()) continue; // other levels are other rooms (reached by stairs)
                seen.add(next);
                q.add(next);
            }
        }
        return new Region(seen, doors, holes, down, start.getY());
    }

    /** If the ceiling above this cell is open and there's a way up (stairs, ladder), the standing cell up there. */
    @Nullable
    private static BlockPos holeAbove(ServerWorld world, BlockPos p) {
        BlockState here = world.getBlockState(p);
        boolean climbable = here.isIn(BlockTags.CLIMBABLE) || here.getBlock() instanceof StairsBlock;
        if (!climbable) return null;
        // Walk up the staircase / ladder until reaching a floor with standing room.
        BlockPos cur = p;
        for (int i = 0; i < 8; i++) {
            BlockState s = world.getBlockState(cur);
            if (s.isIn(BlockTags.CLIMBABLE)) {
                cur = cur.up();
                continue;
            }
            if (s.getBlock() instanceof StairsBlock) {
                Direction f = s.get(StairsBlock.FACING);
                cur = cur.offset(f).up();
                continue;
            }
            break;
        }
        if (cur.getY() - p.getY() < 3) return null;
        for (Direction d : Direction.values()) {
            if (d == Direction.DOWN) continue;
            BlockPos c = d == Direction.UP ? cur : cur.offset(d);
            if (standable(world, c) && !world.getBlockState(c.down()).isIn(BlockTags.CLIMBABLE)) return c;
        }
        return standable(world, cur) ? cur : null;
    }

    /** If this cell stands on the top step of a staircase going down, the standing cell at the bottom. */
    @Nullable
    private static BlockPos stairsDown(ServerWorld world, BlockPos p) {
        BlockState top = world.getBlockState(p.down());
        if (!(top.getBlock() instanceof StairsBlock)) return null;
        Direction up = top.get(StairsBlock.FACING);
        BlockPos cur = p.down();
        int steps = 0;
        while (steps < 8) {
            BlockPos lower = cur.offset(up.getOpposite()).down();
            if (!(world.getBlockState(lower).getBlock() instanceof StairsBlock)) break;
            cur = lower;
            steps++;
        }
        if (steps < 2) return null;
        BlockPos foot = cur.offset(up.getOpposite());
        return standable(world, foot) ? foot : null;
    }

    private static boolean standable(ServerWorld world, BlockPos feet) {
        return open(world, feet) && open(world, feet.up()) && solidBelow(world, feet);
    }

    private static boolean solidBelow(ServerWorld world, BlockPos feet) {
        BlockState below = world.getBlockState(feet.down());
        return !below.getCollisionShape(world, feet.down()).isEmpty() || below.isIn(BlockTags.CLIMBABLE);
    }

    private static boolean open(ServerWorld world, BlockPos p) {
        BlockState s = world.getBlockState(p);
        return s.getCollisionShape(world, p).isEmpty() && s.getFluidState().isEmpty() || s.getBlock() instanceof StairsBlock || s.isIn(BlockTags.WOOL_CARPETS);
    }

    private static boolean wallLike(ServerWorld world, BlockPos p) {
        BlockState s = world.getBlockState(p);
        if (furniture(s) || s.getBlock() instanceof StairsBlock) return false;
        return !s.getCollisionShape(world, p).isEmpty();
    }

    private static boolean furniture(BlockState s) {
        String id = Ids.name(s.getBlock());
        return s.isIn(BlockTags.BEDS) || id.contains("chest") || id.contains("furnace") || id.equals("smoker") || id.equals("crafting_table")
                || id.contains("lantern") || id.equals("anvil") || id.equals("cauldron") || id.equals("brewing_stand") || id.equals("enchanting_table")
                || id.equals("flower_pot") || id.startsWith("potted_") || id.equals("composter") || id.equals("lectern") || id.equals("stonecutter")
                || id.equals("smithing_table") || id.equals("loom") || id.equals("cartography_table") || id.equals("fletching_table") || id.equals("grindstone");
    }

    private static boolean hasCeiling(ServerWorld world, BlockPos p) {
        for (int y = 2; y <= 10; y++) {
            BlockPos c = p.up(y);
            BlockState s = world.getBlockState(c);
            if (!s.getCollisionShape(world, c).isEmpty() && !s.isIn(BlockTags.LEAVES)) return true;
        }
        return false;
    }

    private static BuildingModel.Room toRoom(ServerWorld world, Region region, String id, int level) {
        BuildingModel.Room r = new BuildingModel.Room();
        r.id = id;
        r.level = level;
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos c : region.cells()) {
            minX = Math.min(minX, c.getX()); maxX = Math.max(maxX, c.getX());
            minZ = Math.min(minZ, c.getZ()); maxZ = Math.max(maxZ, c.getZ());
        }
        r.x0 = minX - 1;
        r.x1 = maxX + 1;
        r.z0 = minZ - 1;
        r.z1 = maxZ + 1;
        r.y = region.standY() - 1;
        // Height: the most common clear height over the room.
        Map<Integer, Integer> heights = new HashMap<>();
        for (BlockPos c : region.cells()) {
            int h = 0;
            while (h < 12 && open(world, c.up(h)) && !(world.getBlockState(c.up(h)).getBlock() instanceof StairsBlock && h > 0)) h++;
            heights.merge(h, 1, Integer::sum);
        }
        r.height = heights.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(3);
        r.height = Math.max(2, r.height);
        // What's in it says what it's for.
        int beds = 0, chests = 0, furnaces = 0, tables = 0, books = 0;
        for (int x = r.x0; x <= r.x1; x++) {
            for (int z = r.z0; z <= r.z1; z++) {
                for (int y = r.y + 1; y <= r.y + r.height; y++) {
                    BlockState s = world.getBlockState(new BlockPos(x, y, z));
                    String bid = Ids.name(s.getBlock());
                    if (s.isIn(BlockTags.BEDS)) beds++;
                    else if (bid.contains("chest") || bid.equals("barrel")) chests++;
                    else if (bid.contains("furnace") || bid.equals("smoker")) furnaces++;
                    else if (bid.equals("crafting_table")) tables++;
                    else if (bid.equals("bookshelf")) books++;
                }
            }
        }
        if (beds > 0) r.features.add("bed");
        if (chests > 0) r.features.add(chests + " chests");
        if (furnaces > 0) r.features.add("furnace");
        if (tables > 0) r.features.add("crafting_table");
        if (books > 0) r.features.add("bookshelves");
        r.purpose = beds > 0 ? "bedroom" : chests >= 3 ? "storage" : furnaces > 0 && tables > 0 ? "workshop" : books >= 3 ? "library"
                : region.cells().size() <= Math.max(maxX - minX, maxZ - minZ) + 2 && Math.min(maxX - minX, maxZ - minZ) == 0 ? "hallway" : "room";
        r.built = true;
        return r;
    }

    private static void tally(ServerWorld world, BuildingModel.Room r, Map<String, Integer> materials) {
        for (int x = r.x0; x <= r.x1; x++) {
            for (int z = r.z0; z <= r.z1; z++) {
                boolean edge = x == r.x0 || x == r.x1 || z == r.z0 || z == r.z1;
                boolean corner = (x == r.x0 || x == r.x1) && (z == r.z0 || z == r.z1);
                BlockPos floor = new BlockPos(x, r.y, z);
                if (!edge) count(materials, "floor", world.getBlockState(floor));
                for (int y = r.y + 1; y <= r.y + r.height; y++) {
                    if (!edge) continue;
                    BlockState s = world.getBlockState(new BlockPos(x, y, z));
                    String id = Ids.name(s.getBlock());
                    if (id.contains("glass")) count(materials, "window", s);
                    else if (s.getBlock() instanceof DoorBlock) count(materials, "door", s);
                    else count(materials, corner ? "pillar" : "wall", s);
                }
                if (!edge) count(materials, "ceiling", world.getBlockState(new BlockPos(x, r.ceilingY(), z)));
            }
        }
    }

    private static void count(Map<String, Integer> materials, String role, BlockState s) {
        if (s.isAir() || !s.getFluidState().isEmpty() || s.getBlock().asItem() == net.minecraft.item.Items.AIR) return;
        materials.merge(role + "|" + Ids.name(s.getBlock()), 1, Integer::sum);
    }

    private static void pickPalette(BuildingModel m, Map<String, Integer> materials) {
        Map<String, Integer> best = new LinkedHashMap<>();
        materials.forEach((k, n) -> {
            String role = k.substring(0, k.indexOf('|'));
            String id = k.substring(k.indexOf('|') + 1);
            if (n > best.getOrDefault(role, 0)) {
                best.put(role, n);
                m.palette.put(role, id);
            }
        });
        String wall = m.palette.getOrDefault("wall", "oak_planks");
        m.palette.putIfAbsent("pillar", wall);
        m.palette.putIfAbsent("floor", wall);
        m.palette.putIfAbsent("ceiling", m.palette.get("floor"));
        // Guess the roof material from the walls' wood (stairs of it exist for every wood and most stones).
        String base = wall.replace("_planks", "").replace("stone_bricks", "stone_brick").replace("bricks", "brick");
        m.palette.put("roof", Ids.blockState(base + "_stairs").isPresent() ? base : "spruce");
        m.palette.putIfAbsent("light", "torch");
    }
}
