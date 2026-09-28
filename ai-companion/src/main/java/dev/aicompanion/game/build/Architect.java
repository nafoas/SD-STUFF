package dev.aicompanion.game.build;

import dev.aicompanion.ai.PersonaProfile;
import dev.aicompanion.entity.CompanionEntity;
import dev.aicompanion.game.Ids;
import dev.aicompanion.world.BreakPolicy;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.WallTorchBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.item.Item;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns design changes ("a storage room east of the kitchen through a short hallway", "a second floor with stairs
 * from the living room", "a balcony on the south") into rooms, and rooms into blocks by building rules: floors on a
 * foundation, walls with log corners, windows on outside walls, doors where rooms meet, lights so nothing is dark,
 * climbable stairs with headroom, a roof reworked over the new shape, and furniture against the walls. Then checks
 * the result: every room reachable on foot, nothing cutting into anyone else's build.
 */
public final class Architect {
    private Architect() {}

    /** One design change, as the designer states it. */
    public record Op(String op, String ref, String purpose, String attachTo, String side, int width, int depth, int height,
                     String connect, int hallwayLength, List<String> features) {}

    /** Finds a room by id, by a ref given to a room earlier in the same list of changes, or by a unique purpose. */
    @Nullable
    private static BuildingModel.Room resolve(BuildingModel m, Map<String, String> refs, @Nullable String name) {
        if (name == null || name.isBlank()) return null;
        BuildingModel.Room r = m.room(name);
        if (r != null) return r;
        String id = refs.get(name.trim().toLowerCase(Locale.ROOT));
        if (id != null) return m.room(id);
        BuildingModel.Room found = null;
        for (BuildingModel.Room o : m.rooms) {
            if (o.purpose.equalsIgnoreCase(name.trim())) {
                if (found != null) return null;
                found = o;
            }
        }
        return found;
    }

    // ------------------------------------------------------------------ materials

    public static void choosePalette(BuildingModel m, PersonaProfile p, @Nullable Map<String, String> requested) {
        List<String> pal = p.buildPalette() == null ? List.of() : p.buildPalette();
        put(m, "wall", pick(pal, "planks", "bricks", "stone", "cobblestone"), "oak_planks");
        put(m, "pillar", pick(pal, "_log", "_wood", "stone_bricks", "cobblestone"), "oak_log");
        put(m, "floor", pick(pal, "planks", "stone"), m.palette.getOrDefault("wall", "spruce_planks"));
        put(m, "ceiling", m.palette.get("floor"), "oak_planks");
        String roofBase = pick(pal, "planks", "stone_brick", "cobblestone");
        put(m, "roof", roofBase == null ? null : roofBase.replace("_planks", "").replace("stone_bricks", "stone_brick"), "spruce");
        put(m, "window", pick(pal, "glass"), "glass_pane");
        put(m, "light", pick(pal, "lantern", "torch"), "lantern");
        String wood = woodType(m.palette.get("wall"), woodType(m.palette.get("pillar"), "oak"));
        put(m, "door", wood + "_door", "oak_door");
        put(m, "fence", wood + "_fence", "oak_fence");
        put(m, "foundation", pick(pal, "cobblestone", "stone_bricks"), "cobblestone");
        if (requested != null) requested.forEach((k, v) -> {
            if (v != null && !v.isBlank() && Ids.blockState(v).isPresent()) m.palette.put(k, Ids.normalize(v));
            else if (k.equals("roof") && v != null && !v.isBlank() && Ids.blockState(Ids.normalize(v) + "_stairs").isPresent()) m.palette.put(k, Ids.normalize(v));
        });
        // Make sure every material exists.
        if (Ids.blockState(m.palette.get("roof") + "_stairs").isEmpty()) m.palette.put("roof", "spruce");
        for (String role : List.of("wall", "pillar", "floor", "ceiling", "window", "light", "door", "fence", "foundation")) {
            if (Ids.blockState(m.palette.get(role)).isEmpty()) m.palette.put(role, switch (role) {
                case "pillar" -> "oak_log";
                case "window" -> "glass_pane";
                case "light" -> "lantern";
                case "door" -> "oak_door";
                case "fence" -> "oak_fence";
                case "foundation" -> "cobblestone";
                default -> "oak_planks";
            });
        }
    }

    private static void put(BuildingModel m, String role, @Nullable String value, String fallback) {
        if (!m.palette.containsKey(role)) m.palette.put(role, value != null && Ids.blockState(value).isPresent() ? value : fallback);
    }

    @Nullable
    private static String pick(List<String> palette, String... hints) {
        for (String h : hints) for (String b : palette) if (b != null && Ids.normalize(b).contains(h)) return Ids.normalize(b);
        return null;
    }

    private static String woodType(@Nullable String block, String fallback) {
        if (block == null) return fallback;
        for (String w : List.of("dark_oak", "oak", "spruce", "birch", "jungle", "acacia", "mangrove", "cherry", "bamboo", "crimson", "warped")) {
            if (block.startsWith(w + "_") || block.startsWith("stripped_" + w + "_")) return w;
        }
        return fallback;
    }

    private static BlockState mat(BuildingModel m, String role) {
        return Ids.blockState(m.palette.getOrDefault(role, "oak_planks")).orElse(Blocks.OAK_PLANKS.getDefaultState());
    }

    private static BlockState roofStairs(BuildingModel m, Direction facing) {
        return Ids.blockState(m.palette.get("roof") + "_stairs").orElse(Blocks.SPRUCE_STAIRS.getDefaultState()).with(StairsBlock.FACING, facing);
    }

    private static BlockState roofSlab(BuildingModel m) {
        return Ids.blockState(m.palette.get("roof") + "_slab").orElse(Blocks.SPRUCE_SLAB.getDefaultState());
    }

    // ------------------------------------------------------------------ design changes -> rooms

    /** Applies design changes to the model. Returns problems (empty if all went in). */
    public static List<String> apply(BuildingModel m, List<Op> ops, BlockPos site, Direction entranceSide, ServerWorld world) {
        List<String> problems = new ArrayList<>();
        Map<String, String> refs = new java.util.HashMap<>();
        for (Op given : ops) {
            String kind = given.op() == null ? "" : given.op().toLowerCase(Locale.ROOT);
            BuildingModel.Room target = resolve(m, refs, given.attachTo());
            Op op = new Op(given.op(), given.ref(), given.purpose(), target == null ? given.attachTo() : target.id, given.side(), given.width(),
                    given.depth(), given.height(), given.connect(), given.hallwayLength(), given.features());
            int before = m.rooms.size();
            try {
                switch (kind) {
                    case "room" -> addRoom(m, op, site, entranceSide, world, problems);
                    case "floor" -> addFloor(m, op, problems);
                    case "balcony" -> addBalcony(m, op, problems);
                    case "feature" -> {
                        BuildingModel.Room r = m.room(op.attachTo());
                        if (r == null) problems.add("feature: no room " + op.attachTo());
                        else if (op.features() != null) r.features.addAll(op.features());
                    }
                    default -> problems.add("unknown change '" + op.op() + "'");
                }
            } catch (Exception e) {
                problems.add(kind + ": " + e.getMessage());
            }
            if (m.rooms.size() > before && given.ref() != null && !given.ref().isBlank()) {
                refs.put(given.ref().trim().toLowerCase(Locale.ROOT), m.rooms.get(m.rooms.size() - 1).id);
            }
        }
        ensureEntrance(m, entranceSide);
        return problems;
    }

    /**
     * Keeps doors sensible after changes: two doors in one spot become one; a front door that a new room now covers
     * becomes a door into that room; and if nothing leads outside any more, a new front door goes in the middle of a
     * free stretch of outside wall on the ground floor, preferably on the side people come from.
     */
    private static void ensureEntrance(BuildingModel m, Direction preferred) {
        Set<String> spots = new HashSet<>();
        m.links.removeIf(l -> !l.type.equals("stairs") && !l.built && !spots.add(l.x + "," + l.y + "," + l.z) );
        for (BuildingModel.Link l : m.links) {
            if (!l.b.equals("outside")) continue;
            for (BuildingModel.Room o : m.rooms) {
                if (!o.id.equals(l.a) && o.level == levelOf(m, l.a) && o.containsColumn(l.x, l.z)) {
                    l.b = o.id;
                    break;
                }
            }
        }
        if (m.links.stream().anyMatch(l -> l.b.equals("outside"))) return;
        // Candidate spots: outside-wall cells of ground-floor rooms whose neighbours along the wall are outside wall too.
        BlockPos best = null;
        String bestRoom = null;
        int bestScore = Integer.MIN_VALUE;
        for (BuildingModel.Room r : m.rooms) {
            if (r.level != 0 || r.purpose.equals("hallway") && m.rooms.size() > 1) continue;
            for (Direction side : Direction.Type.HORIZONTAL) {
                boolean alongX = side.getAxis() == Direction.Axis.Z;
                int line = side == Direction.EAST ? r.x1 : side == Direction.WEST ? r.x0 : side == Direction.SOUTH ? r.z1 : r.z0;
                int lo = (alongX ? r.x0 : r.z0) + 2, hi = (alongX ? r.x1 : r.z1) - 2;
                int mid = ((alongX ? r.x0 : r.z0) + (alongX ? r.x1 : r.z1)) / 2;
                for (int t = lo; t <= hi; t++) {
                    boolean ok = true;
                    for (int dt = -1; dt <= 1 && ok; dt++) {
                        int x = alongX ? t + dt : line, z = alongX ? line : t + dt;
                        int ox = x + side.getOffsetX(), oz = z + side.getOffsetZ();
                        for (BuildingModel.Room o : m.rooms) if (o != r && o.level == 0 && o.containsColumn(ox, oz)) ok = false;
                        for (BuildingModel.Link l : m.links) if (!l.type.equals("stairs") && Math.abs(l.x - x) + Math.abs(l.z - z) <= 1 && l.y == r.y + 1) ok = false;
                    }
                    if (!ok) continue;
                    int score = (side == preferred ? 100 : side == preferred.getOpposite() ? 0 : 50) - Math.abs(t - mid) * 3
                            + (r.purpose.contains("living") || r.purpose.contains("hall") && !r.purpose.equals("hallway") ? 20 : 0);
                    if (score > bestScore) {
                        bestScore = score;
                        best = alongX ? new BlockPos(t, r.y + 1, line) : new BlockPos(line, r.y + 1, t);
                        bestRoom = r.id;
                    }
                }
            }
        }
        if (best == null) return;
        BuildingModel.Link door = new BuildingModel.Link();
        door.a = bestRoom;
        door.b = "outside";
        door.type = "door";
        door.x = best.getX();
        door.y = best.getY();
        door.z = best.getZ();
        m.links.add(door);
    }

    private static int levelOf(BuildingModel m, String roomId) {
        BuildingModel.Room r = m.room(roomId);
        return r == null ? 0 : r.level;
    }

    private static void addRoom(BuildingModel m, Op op, BlockPos site, Direction entranceSide, ServerWorld world, List<String> problems) {
        int w = clamp(op.width(), 4, 15), d = clamp(op.depth(), 4, 15), h = clamp(op.height() <= 0 ? 3 : op.height(), 2, 6);
        BuildingModel.Room r = new BuildingModel.Room();
        r.id = m.nextRoomId();
        r.purpose = op.purpose() == null || op.purpose().isBlank() ? "room" : op.purpose().toLowerCase(Locale.ROOT);
        r.height = h;
        if (op.features() != null) r.features.addAll(op.features());
        if (m.rooms.isEmpty() || op.attachTo() == null || op.attachTo().isBlank() || m.room(op.attachTo()) == null) {
            if (!m.rooms.isEmpty()) {
                problems.add("room: attach_to must name an existing room (" + String.join(", ", m.rooms.stream().map(x -> x.id).toList()) + ")");
                return;
            }
            // The first room: centred on the site, standing on the ground there.
            r.x0 = site.getX() - w / 2;
            r.z0 = site.getZ() - d / 2;
            r.x1 = r.x0 + w - 1;
            r.z1 = r.z0 + d - 1;
            r.y = site.getY();
            r.level = 0;
            m.rooms.add(r);
            // Front door facing where it's approached from.
            BuildingModel.Link door = new BuildingModel.Link();
            door.a = r.id;
            door.b = "outside";
            door.type = "door";
            door.y = r.y + 1;
            switch (entranceSide) {
                case NORTH -> { door.x = (r.x0 + r.x1) / 2; door.z = r.z0; }
                case SOUTH -> { door.x = (r.x0 + r.x1) / 2; door.z = r.z1; }
                case WEST -> { door.x = r.x0; door.z = (r.z0 + r.z1) / 2; }
                default -> { door.x = r.x1; door.z = (r.z0 + r.z1) / 2; }
            }
            m.links.add(door);
            return;
        }
        BuildingModel.Room a = m.room(op.attachTo());
        Direction side = Direction.byName(op.side() == null ? "" : op.side().toLowerCase(Locale.ROOT));
        if (side == null || side.getAxis().isVertical()) {
            problems.add("room " + r.purpose + ": side must be north, south, east or west");
            return;
        }
        String connect = op.connect() == null ? "door" : op.connect().toLowerCase(Locale.ROOT);
        BuildingModel.Room from = a;
        if (connect.equals("hallway")) {
            int len = clamp(op.hallwayLength() <= 0 ? 3 : op.hallwayLength(), 1, 12);
            BuildingModel.Room hall = new BuildingModel.Room();
            hall.id = m.nextRoomId();
            hall.purpose = "hallway";
            hall.height = Math.min(h, a.height);
            place(hall, a, side, 3, len + 2, 0);
            if (overlaps(m, hall)) {
                problems.add("hallway from " + a.id + " to the " + side.asString() + " would run into another room");
                return;
            }
            m.rooms.add(hall);
            link(m, a, hall, side, "door");
            from = hall;
        }
        place(r, from, side, w, d, 0);
        r.height = h;
        r.id = m.nextRoomId(); // after any hallway took its id
        if (overlaps(m, r)) {
            if (from != a) {
                BuildingModel.Room hall = from;
                m.rooms.remove(hall);
                m.links.removeIf(l -> l.b.equals(hall.id) || l.a.equals(hall.id));
            }
            problems.add("a " + r.purpose + " to the " + side.asString() + " of " + from.id + " would overlap another room; pick a free side or a smaller size");
            return;
        }
        m.rooms.add(r);
        link(m, from, r, side, connect.equals("opening") ? "opening" : "door");
    }

    /** Puts room r against `a` on `side`, sharing a's wall, `width` along the wall and `depth` outward, centred. */
    private static void place(BuildingModel.Room r, BuildingModel.Room a, Direction side, int width, int depth, int offset) {
        r.level = a.level;
        r.y = a.y;
        switch (side) {
            case EAST -> { r.x0 = a.x1; r.x1 = a.x1 + depth - 1; r.z0 = centered(a.z0, a.z1, width) + offset; r.z1 = r.z0 + width - 1; }
            case WEST -> { r.x1 = a.x0; r.x0 = a.x0 - depth + 1; r.z0 = centered(a.z0, a.z1, width) + offset; r.z1 = r.z0 + width - 1; }
            case SOUTH -> { r.z0 = a.z1; r.z1 = a.z1 + depth - 1; r.x0 = centered(a.x0, a.x1, width) + offset; r.x1 = r.x0 + width - 1; }
            default -> { r.z1 = a.z0; r.z0 = a.z0 - depth + 1; r.x0 = centered(a.x0, a.x1, width) + offset; r.x1 = r.x0 + width - 1; }
        }
    }

    private static int centered(int lo, int hi, int width) {
        return (lo + hi) / 2 - (width - 1) / 2;
    }

    /** A door (or opening) in the middle of the wall two rooms share. */
    private static void link(BuildingModel m, BuildingModel.Room a, BuildingModel.Room b, Direction side, String type) {
        BuildingModel.Link l = new BuildingModel.Link();
        l.a = a.id;
        l.b = b.id;
        l.type = type;
        l.y = a.y + 1;
        if (side.getAxis() == Direction.Axis.X) {
            l.x = side == Direction.EAST ? a.x1 : a.x0;
            int lo = Math.max(a.z0, b.z0) + 1, hi = Math.min(a.z1, b.z1) - 1;
            l.z = (lo + hi) / 2;
        } else {
            l.z = side == Direction.SOUTH ? a.z1 : a.z0;
            int lo = Math.max(a.x0, b.x0) + 1, hi = Math.min(a.x1, b.x1) - 1;
            l.x = (lo + hi) / 2;
        }
        m.links.add(l);
    }

    private static boolean overlaps(BuildingModel m, BuildingModel.Room r) {
        for (BuildingModel.Room o : m.rooms) {
            if (o == r || o.level != r.level) continue;
            // Interiors must not overlap (sharing a wall line is fine).
            boolean xo = r.x0 + 1 <= o.x1 - 1 && o.x0 + 1 <= r.x1 - 1;
            boolean zo = r.z0 + 1 <= o.z1 - 1 && o.z0 + 1 <= r.z1 - 1;
            if (xo && zo) return true;
        }
        return false;
    }

    private static void addFloor(BuildingModel m, Op op, List<String> problems) {
        BuildingModel.Room a = m.room(op.attachTo());
        if (a == null) {
            problems.add("floor: no room " + op.attachTo());
            return;
        }
        if (roomAbove(m, a) != null) {
            problems.add("floor: " + a.id + " already has a room above it");
            return;
        }
        int h = clamp(op.height() <= 0 ? a.height : op.height(), 2, 6);
        if (Math.max(a.interiorWidth(), a.interiorDepth()) < a.height + 3) {
            problems.add("floor: " + a.id + " is too small inside for stairs up (needs " + (a.height + 3) + " blocks along one side)");
            return;
        }
        BuildingModel.Room u = new BuildingModel.Room();
        u.id = m.nextRoomId();
        u.purpose = op.purpose() == null || op.purpose().isBlank() ? "room" : op.purpose().toLowerCase(Locale.ROOT);
        u.level = a.level + 1;
        u.x0 = a.x0;
        u.z0 = a.z0;
        u.x1 = a.x1;
        u.z1 = a.z1;
        u.y = a.ceilingY();
        u.height = h;
        if (op.features() != null) u.features.addAll(op.features());
        m.rooms.add(u);
        BuildingModel.Link stairs = new BuildingModel.Link();
        stairs.a = a.id;
        stairs.b = u.id;
        stairs.type = "stairs";
        m.links.add(stairs);
    }

    private static void addBalcony(BuildingModel m, Op op, List<String> problems) {
        BuildingModel.Room r = m.room(op.attachTo());
        Direction side = Direction.byName(op.side() == null ? "" : op.side().toLowerCase(Locale.ROOT));
        if (r == null || side == null || side.getAxis().isVertical()) {
            problems.add("balcony: needs an existing room and a side (north/south/east/west)");
            return;
        }
        for (BuildingModel.Room o : m.rooms) {
            if (o != r && o.level == r.level && sharesWallOn(r, o, side)) {
                problems.add("balcony: the " + side.asString() + " side of " + r.id + " isn't an outside wall");
                return;
            }
        }
        BuildingModel.Balcony b = new BuildingModel.Balcony();
        b.room = r.id;
        b.side = side.asString();
        b.depth = clamp(op.depth() <= 0 ? 2 : op.depth(), 2, 4);
        m.balconies.add(b);
        BuildingModel.Link door = new BuildingModel.Link();
        door.a = r.id;
        door.b = "balcony";
        door.type = "door";
        door.y = r.y + 1;
        if (side.getAxis() == Direction.Axis.X) {
            door.x = side == Direction.EAST ? r.x1 : r.x0;
            door.z = (r.z0 + r.z1) / 2;
        } else {
            door.z = side == Direction.SOUTH ? r.z1 : r.z0;
            door.x = (r.x0 + r.x1) / 2;
        }
        m.links.add(door);
    }

    private static boolean sharesWallOn(BuildingModel.Room r, BuildingModel.Room o, Direction side) {
        return switch (side) {
            case EAST -> o.x0 == r.x1 && o.z0 < r.z1 && o.z1 > r.z0;
            case WEST -> o.x1 == r.x0 && o.z0 < r.z1 && o.z1 > r.z0;
            case SOUTH -> o.z0 == r.z1 && o.x0 < r.x1 && o.x1 > r.x0;
            default -> o.z1 == r.z0 && o.x0 < r.x1 && o.x1 > r.x0;
        };
    }

    @Nullable
    private static BuildingModel.Room roomAbove(BuildingModel m, BuildingModel.Room r) {
        for (BuildingModel.Room o : m.rooms) {
            if (o.level == r.level + 1 && o.x0 < r.x1 && o.x1 > r.x0 && o.z0 < r.z1 && o.z1 > r.z0) return o;
        }
        return null;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * Finds a spot for a new building of this footprint near `near`: flat-ish dry ground that isn't anyone's build,
     * not too close to other things. Returns the ground block under the footprint's centre, or null.
     */
    @Nullable
    public static BlockPos findSite(CompanionEntity c, BlockPos near, int width, int depth, List<BuildingModel> avoid) {
        ServerWorld world = c.serverWorld();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int r = 0; r <= 28; r += 2) {
            for (int dx = -r; dx <= r; dx += 2) {
                for (int dz = -r; dz <= r; dz += 2) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int cx = near.getX() + dx, cz = near.getZ() + dz;
                    int x0 = cx - width / 2 - 2, z0 = cz - depth / 2 - 2, x1 = x0 + width + 3, z1 = z0 + depth + 3;
                    boolean bad = false;
                    for (BuildingModel b : avoid) {
                        int[] bb = b.bounds();
                        if (x0 <= bb[2] + 2 && x1 >= bb[0] - 2 && z0 <= bb[3] + 2 && z1 >= bb[1] - 2) bad = true;
                    }
                    if (bad) continue;
                    int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, water = 0, builds = 0, samples = 0;
                    List<Integer> heights = new ArrayList<>();
                    for (int x = x0; x <= x1; x += 2) {
                        for (int z = z0; z <= z1; z += 2) {
                            int y = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                            BlockPos g = new BlockPos(x, y, z);
                            BlockState s = world.getBlockState(g);
                            if (!s.getFluidState().isEmpty()) water++;
                            if (s.isIn(BlockTags.LOGS)) y -= 1; // tree trunks don't count as ground
                            if (dev.aicompanion.world.BuildAwareness.isCrafted(s) || dev.aicompanion.world.BlockOwnership.get(world).owner(g) != null) builds++;
                            min = Math.min(min, y);
                            max = Math.max(max, y);
                            heights.add(y);
                            samples++;
                        }
                    }
                    if (builds > 0 || water > samples / 6) continue;
                    double score = (max - min) * 4 + water * 3 + r * 0.4;
                    if (score < bestScore) {
                        heights.sort(Integer::compare);
                        bestScore = score;
                        best = new BlockPos(cx, heights.get(heights.size() / 2), cz);
                    }
                }
            }
            if (best != null && bestScore < 6 + r * 0.4) break;
        }
        return best;
    }

    /** The side of a footprint centred at `site` that faces toward `toward`. */
    public static Direction sideToward(BlockPos site, BlockPos toward) {
        int dx = toward.getX() - site.getX(), dz = toward.getZ() - site.getZ();
        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? Direction.EAST : Direction.WEST;
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    // ------------------------------------------------------------------ rooms -> blocks

    /** Blocks for everything planned but not built yet (plus the changes it makes to what's built). */
    public static Map<BlockPos, BlockState> compile(ServerWorld world, BuildingModel m) {
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        Set<BlockPos> reserved = new HashSet<>();
        // Roofs of built rooms that are getting a floor on top come off first.
        for (BuildingModel.Room r : m.rooms) {
            BuildingModel.Room up = roomAbove(m, r);
            if (r.built && up != null && !up.built) removeRoof(world, m, r, out);
        }
        // A new room built against a finished one: that room's eaves and roof edge over the new room come off.
        for (BuildingModel.Room b : m.rooms) {
            if (!b.built || roomAbove(m, b) != null && !roomAbove(m, b).built) continue;
            Map<BlockPos, BlockState> old = new LinkedHashMap<>();
            roof(m, b, old);
            for (Map.Entry<BlockPos, BlockState> e : old.entrySet()) {
                BlockPos p = e.getKey();
                for (BuildingModel.Room n : m.rooms) {
                    if (n.built || n.level != b.level || !n.containsColumn(p.getX(), p.getZ())) continue;
                    if (p.getY() > n.ceilingY() + roofLayers(n) + 1) continue;
                    if (dev.aicompanion.game.tasks.BuildTask.matches(world.getBlockState(p), e.getValue())) out.put(p, Blocks.AIR.getDefaultState());
                }
            }
        }
        for (BuildingModel.Room r : m.rooms) if (!r.built) shell(world, m, r, out);
        for (BuildingModel.Link l : m.links) if (!l.built) connect(world, m, l, out, reserved);
        for (BuildingModel.Balcony b : m.balconies) if (!b.built) balcony(m, b, out);
        for (BuildingModel.Room r : m.rooms) if (!r.built) {
            windows(m, r, out, reserved);
            lights(m, r, out, reserved);
            furnish(m, r, out, reserved);
        }
        entranceLight(world, m, out);
        return out;
    }

    /**
     * Takes the roof off a built room so a floor can go on top. A roof it designed comes off block for block;
     * on a scanned building, roof-like blocks (stairs, slabs, the wall and roof materials) above the ceiling go.
     */
    private static void removeRoof(ServerWorld world, BuildingModel m, BuildingModel.Room r, Map<BlockPos, BlockState> out) {
        Map<BlockPos, BlockState> old = new LinkedHashMap<>();
        roof(m, r, old);
        for (Map.Entry<BlockPos, BlockState> e : old.entrySet()) {
            if (dev.aicompanion.game.tasks.BuildTask.matches(world.getBlockState(e.getKey()), e.getValue())) out.put(e.getKey(), Blocks.AIR.getDefaultState());
        }
        if (!m.scanned) return;
        Set<Block> roofish = new HashSet<>();
        for (String role : List.of("wall", "pillar", "ceiling")) roofish.add(mat(m, role).getBlock());
        int layers = roofLayers(r);
        for (int x = r.x0 - 1; x <= r.x1 + 1; x++)
            for (int z = r.z0 - 1; z <= r.z1 + 1; z++)
                for (int y = r.ceilingY(); y <= r.ceilingY() + layers + 1; y++) {
                    if (y == r.ceilingY() && r.containsColumn(x, z)) continue; // the ceiling becomes the new floor
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState s = world.getBlockState(p);
                    if (s.getBlock() instanceof StairsBlock || s.getBlock() instanceof net.minecraft.block.SlabBlock || roofish.contains(s.getBlock())
                            || s.isIn(BlockTags.PLANKS) || s.isIn(BlockTags.LOGS)) out.put(p, Blocks.AIR.getDefaultState());
                }
    }

    private static void shell(ServerWorld world, BuildingModel m, BuildingModel.Room r, Map<BlockPos, BlockState> out) {
        BlockState floor = mat(m, "floor"), wall = mat(m, "wall"), pillar = mat(m, "pillar"), ceiling = mat(m, "ceiling"), foundation = mat(m, "foundation");
        boolean hasAbove = roomAbove(m, r) != null;
        for (int x = r.x0; x <= r.x1; x++) {
            for (int z = r.z0; z <= r.z1; z++) {
                boolean edge = x == r.x0 || x == r.x1 || z == r.z0 || z == r.z1;
                boolean corner = (x == r.x0 || x == r.x1) && (z == r.z0 || z == r.z1);
                // A foundation down to the ground for ground-floor rooms.
                if (r.level == 0 && edge) {
                    for (int y = r.y - 1; y >= r.y - 4; y--) {
                        BlockPos p = new BlockPos(x, y, z);
                        BlockState s = world.getBlockState(p);
                        if (!(s.isAir() || s.isReplaceable() || !s.getFluidState().isEmpty())) break;
                        out.put(p, foundation);
                    }
                }
                BlockPos floorPos = new BlockPos(x, r.y, z);
                if (!sharedWithBuilt(m, r, x, r.y, z) || r.level > 0) out.putIfAbsent(floorPos, corner ? pillar : edge ? foundationOrFloor(r, foundation, floor) : floor);
                if (r.level > 0 && !edge) out.put(floorPos, floor);
                for (int y = r.y + 1; y <= r.y + r.height; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (edge) {
                        if (!sharedWithBuilt(m, r, x, y, z)) out.put(p, corner ? pillar : wall);
                    } else {
                        out.put(p, Blocks.AIR.getDefaultState());
                    }
                }
                if (!hasAbove && !(edge && sharedWithBuilt(m, r, x, r.ceilingY(), z))) out.put(new BlockPos(x, r.ceilingY(), z), corner ? pillar : edge ? wall : ceiling);
            }
        }
        if (!hasAbove) roof(m, r, out);
    }

    private static BlockState foundationOrFloor(BuildingModel.Room r, BlockState foundation, BlockState floor) {
        return r.level == 0 ? foundation : floor;
    }

    /** Wall lines shared with a room that's already built stay as they are (doors and windows there are kept). */
    private static boolean sharedWithBuilt(BuildingModel m, BuildingModel.Room r, int x, int y, int z) {
        for (BuildingModel.Room o : m.rooms) {
            if (o == r || !o.built || o.level != r.level || y > o.ceilingY() || y < o.y) continue;
            boolean onOEdge = (x == o.x0 || x == o.x1) && z >= o.z0 && z <= o.z1 || (z == o.z0 || z == o.z1) && x >= o.x0 && x <= o.x1;
            if (onOEdge) return true;
        }
        return false;
    }

    private static int roofLayers(BuildingModel.Room r) {
        int span = Math.min(r.x1 - r.x0, r.z1 - r.z0) + 3;
        return span / 2 + 1;
    }

    /** Gable roof along the long side with a one-block overhang (or a flat slab roof). */
    private static void roof(BuildingModel m, BuildingModel.Room r, Map<BlockPos, BlockState> out) {
        int base = r.ceilingY();
        if (m.roof.equals("flat")) {
            for (int x = r.x0 - 1; x <= r.x1 + 1; x++)
                for (int z = r.z0 - 1; z <= r.z1 + 1; z++) roofPut(m, r, out, new BlockPos(x, base + 1, z), roofSlab(m));
            return;
        }
        boolean alongX = (r.x1 - r.x0) >= (r.z1 - r.z0); // ridge runs along the longer side
        int lo = (alongX ? r.z0 : r.x0) - 1, hi = (alongX ? r.z1 : r.x1) + 1;
        int from = (alongX ? r.x0 : r.z0) - 1, to = (alongX ? r.x1 : r.z1) + 1;
        BlockState wall = mat(m, "wall");
        for (int k = 0; lo + k <= hi - k; k++) {
            int y = base + k;
            int a = lo + k, b = hi - k;
            for (int t = from; t <= to; t++) {
                if (a == b) {
                    roofPut(m, r, out, pos(alongX, t, y, a), Ids.blockState(m.palette.get("roof") + "_planks").orElse(roofSlab(m)));
                } else {
                    roofPut(m, r, out, pos(alongX, t, y, a), roofStairs(m, alongX ? Direction.SOUTH : Direction.EAST));
                    roofPut(m, r, out, pos(alongX, t, y, b), roofStairs(m, alongX ? Direction.NORTH : Direction.WEST));
                }
            }
            // Gable ends: fill the triangle under the roof at both ends of the ridge.
            if (k > 0) {
                for (int c = a + 1; c <= b - 1; c++) {
                    if (c < (alongX ? r.z0 : r.x0) || c > (alongX ? r.z1 : r.x1)) continue;
                    roofPut(m, r, out, pos(alongX, from + 1, y, c), wall);
                    roofPut(m, r, out, pos(alongX, to - 1, y, c), wall);
                }
            }
        }
    }

    /** Roof blocks never go inside another room (a taller neighbour, a floor above) or through its ceiling. */
    private static void roofPut(BuildingModel m, BuildingModel.Room self, Map<BlockPos, BlockState> out, BlockPos p, BlockState s) {
        for (BuildingModel.Room o : m.rooms) {
            if (o == self) continue;
            if (o.containsColumn(p.getX(), p.getZ()) && p.getY() > o.y && p.getY() <= o.ceilingY()) return;
        }
        out.put(p, s);
    }

    private static BlockPos pos(boolean alongX, int along, int y, int across) {
        return alongX ? new BlockPos(along, y, across) : new BlockPos(across, y, along);
    }

    private static void connect(ServerWorld world, BuildingModel m, BuildingModel.Link l, Map<BlockPos, BlockState> out, Set<BlockPos> reserved) {
        if (l.type.equals("stairs")) {
            stairs(m, l, out, reserved);
            return;
        }
        BlockPos p = new BlockPos(l.x, l.y, l.z);
        if (l.type.equals("opening")) {
            out.put(p, Blocks.AIR.getDefaultState());
            out.put(p.up(), Blocks.AIR.getDefaultState());
        } else {
            Direction facing = wallNormal(m, l);
            BlockState door = mat(m, "door");
            if (door.contains(DoorBlock.FACING)) {
                door = door.with(DoorBlock.FACING, facing);
                out.put(p, door.with(DoorBlock.HALF, DoubleBlockHalf.LOWER));
                out.put(p.up(), door.with(DoorBlock.HALF, DoubleBlockHalf.UPPER));
            }
        }
        // Keep the way through clear of furniture on both sides.
        for (Direction d : Direction.Type.HORIZONTAL) reserved.add(p.offset(d));
    }

    /** Which way a door in a wall faces (out of the room it belongs to). */
    private static Direction wallNormal(BuildingModel m, BuildingModel.Link l) {
        BuildingModel.Room a = m.room(l.a);
        if (a == null) return Direction.NORTH;
        if (l.x == a.x0) return Direction.WEST;
        if (l.x == a.x1) return Direction.EAST;
        if (l.z == a.z0) return Direction.NORTH;
        return Direction.SOUTH;
    }

    /**
     * A straight flight of stairs along one of the lower room's inside walls (one with no door beside the flight),
     * with headroom cut into the floor above.
     */
    private static void stairs(BuildingModel m, BuildingModel.Link l, Map<BlockPos, BlockState> out, Set<BlockPos> reserved) {
        BuildingModel.Room a = m.room(l.a), u = m.room(l.b);
        if (a == null || u == null) return;
        int steps = a.height + 1;
        // Candidates: along x (against the north or south wall, going east or west), or along z.
        record Run(boolean alongX, int row, Direction up, int startAlong) {}
        List<Run> runs = new ArrayList<>();
        boolean preferX = a.interiorWidth() >= a.interiorDepth();
        for (int pass = 0; pass < 2; pass++) {
            boolean alongX = pass == 0 == preferX;
            int len = alongX ? a.interiorWidth() : a.interiorDepth();
            if (len < steps + 2) continue;
            int lo = alongX ? a.x0 : a.z0, hi = alongX ? a.x1 : a.z1;
            int rowA = alongX ? a.z0 + 1 : a.x0 + 1, rowB = alongX ? a.z1 - 1 : a.x1 - 1;
            Direction fwd = alongX ? Direction.EAST : Direction.SOUTH, back = fwd.getOpposite();
            runs.add(new Run(alongX, rowA, fwd, lo + 2));
            runs.add(new Run(alongX, rowB, fwd, lo + 2));
            runs.add(new Run(alongX, rowA, back, hi - 2));
            runs.add(new Run(alongX, rowB, back, hi - 2));
        }
        Run chosen = null;
        for (Run run : runs) {
            boolean clear = true;
            for (int i = -1; i <= steps && clear; i++) {
                int along = run.startAlong() + i * run.up().getDirection().offset();
                int x = run.alongX() ? along : run.row(), z = run.alongX() ? run.row() : along;
                for (BuildingModel.Link o : m.links) {
                    if (o == l || o.type.equals("stairs")) continue;
                    if (Math.abs(o.x - x) + Math.abs(o.z - z) <= 1 && (o.y == a.y + 1 || o.y == u.y + 1)) clear = false;
                }
            }
            if (clear) {
                chosen = run;
                break;
            }
        }
        if (chosen == null) {
            if (runs.isEmpty()) return;
            chosen = runs.get(0);
        }
        int dir = chosen.up().getDirection().offset();
        BlockState stair = Ids.blockState(m.palette.get("roof") + "_stairs").orElse(Blocks.SPRUCE_STAIRS.getDefaultState()).with(StairsBlock.FACING, chosen.up());
        Direction side = chosen.alongX() ? (chosen.row() == a.z0 + 1 ? Direction.SOUTH : Direction.NORTH) : (chosen.row() == a.x0 + 1 ? Direction.EAST : Direction.WEST);
        for (int i = 0; i < steps; i++) {
            int along = chosen.startAlong() + i * dir;
            BlockPos s = chosen.alongX() ? new BlockPos(along, a.y + 1 + i, chosen.row()) : new BlockPos(chosen.row(), a.y + 1 + i, along);
            out.put(s, stair);
            // Three clear above each step: walking between steps, your head passes where the next one's headroom is.
            out.put(s.up(), Blocks.AIR.getDefaultState());
            out.put(s.up(2), Blocks.AIR.getDefaultState());
            if (s.up(3).getY() <= u.y + 1) out.put(s.up(3), Blocks.AIR.getDefaultState());
            reserved.add(new BlockPos(s.getX(), a.y + 1, s.getZ()));
            reserved.add(new BlockPos(s.getX(), u.y + 1, s.getZ()));
            // The cell beside each step stays clear so it can be walked onto.
            BlockPos beside = s.offset(side);
            reserved.add(new BlockPos(beside.getX(), a.y + 1, beside.getZ()));
            if (i >= steps - 3) reserved.add(new BlockPos(beside.getX(), u.y + 1, beside.getZ()));
        }
        // Landing at the top.
        int topAlong = chosen.startAlong() + steps * dir;
        reserved.add(chosen.alongX() ? new BlockPos(topAlong, u.y + 1, chosen.row()) : new BlockPos(chosen.row(), u.y + 1, topAlong));
        BlockPos start = chosen.alongX() ? new BlockPos(chosen.startAlong() - dir, a.y + 1, chosen.row()) : new BlockPos(chosen.row(), a.y + 1, chosen.startAlong() - dir);
        reserved.add(start);
        l.x = start.getX();
        l.y = start.getY();
        l.z = start.getZ();
    }

    private static void balcony(BuildingModel m, BuildingModel.Balcony b, Map<BlockPos, BlockState> out) {
        BuildingModel.Room r = m.room(b.room);
        Direction side = Direction.byName(b.side);
        if (r == null || side == null) return;
        BlockState floor = mat(m, "floor"), fence = mat(m, "fence");
        boolean alongX = side.getAxis() == Direction.Axis.Z;
        int from = (alongX ? r.x0 : r.z0) + 1, to = (alongX ? r.x1 : r.z1) - 1;
        int wallLine = switch (side) {
            case EAST -> r.x1;
            case WEST -> r.x0;
            case SOUTH -> r.z1;
            default -> r.z0;
        };
        int dir = side == Direction.EAST || side == Direction.SOUTH ? 1 : -1;
        for (int k = 1; k <= b.depth; k++) {
            for (int t = from; t <= to; t++) {
                int across = wallLine + dir * k;
                BlockPos f = alongX ? new BlockPos(t, r.y, across) : new BlockPos(across, r.y, t);
                out.put(f, floor);
                out.put(f.up(), Blocks.AIR.getDefaultState());
                out.put(f.up(2), Blocks.AIR.getDefaultState());
                boolean outer = k == b.depth || t == from || t == to;
                if (outer) out.put(f.up(), fence);
            }
        }
    }

    /** Glass panes along outside walls, every third block, away from corners and doors. */
    private static void windows(BuildingModel m, BuildingModel.Room r, Map<BlockPos, BlockState> out, Set<BlockPos> reserved) {
        if (r.purpose.equals("hallway") && r.interiorWidth() <= 1 && r.interiorDepth() <= 1) return;
        BlockState glass = mat(m, "window");
        int top = r.height >= 4 ? r.y + 3 : r.y + 2;
        for (int x = r.x0 + 2; x <= r.x1 - 2; x++) {
            if ((x - r.x0) % 3 != 2 && r.x1 - r.x0 > 5) continue;
            for (int z : new int[]{r.z0, r.z1}) windowAt(m, r, x, z, top, glass, out);
        }
        for (int z = r.z0 + 2; z <= r.z1 - 2; z++) {
            if ((z - r.z0) % 3 != 2 && r.z1 - r.z0 > 5) continue;
            for (int x : new int[]{r.x0, r.x1}) windowAt(m, r, x, z, top, glass, out);
        }
    }

    private static void windowAt(BuildingModel m, BuildingModel.Room r, int x, int z, int top, BlockState glass, Map<BlockPos, BlockState> out) {
        for (BuildingModel.Room o : m.rooms) if (o != r && o.level == r.level && o.containsColumn(x, z)) return; // inside wall
        for (BuildingModel.Link l : m.links) if (Math.abs(l.x - x) + Math.abs(l.z - z) <= 1 && l.y <= top && l.y + 1 >= r.y + 1 && !l.type.equals("stairs")) return;
        for (BuildingModel.Balcony b : m.balconies) if (b.room.equals(r.id)) {
            Direction side = Direction.byName(b.side);
            if (side != null && ((side == Direction.EAST && x == r.x1) || (side == Direction.WEST && x == r.x0) || (side == Direction.SOUTH && z == r.z1) || (side == Direction.NORTH && z == r.z0))) {
                // windows either side of the balcony door are fine; skip only the door cell (handled by links)
            }
        }
        for (int y = r.y + 2; y <= top; y++) out.put(new BlockPos(x, y, z), glass);
    }

    /** Wall torches around the inside, so no spot is far from light (monsters spawn in the dark). */
    private static void lights(BuildingModel m, BuildingModel.Room r, Map<BlockPos, BlockState> out, Set<BlockPos> reserved) {
        BlockState light = mat(m, "light");
        if (light.contains(Properties.HANGING) && r.height >= 3) {
            // Lanterns hanging from the ceiling, spread evenly over the room.
            int w = r.interiorWidth(), d = r.interiorDepth();
            int nx = Math.max(1, (w + 4) / 5), nz = Math.max(1, (d + 4) / 5);
            for (int i = 0; i < nx; i++) {
                for (int j = 0; j < nz; j++) {
                    int x = r.x0 + 1 + (2 * i + 1) * w / (2 * nx), z = r.z0 + 1 + (2 * j + 1) * d / (2 * nz);
                    if (reserved.contains(new BlockPos(x, r.y + 1, z))) continue; // stairs run here
                    BlockPos p = new BlockPos(x, r.y + r.height, z);
                    BlockState there = out.get(p);
                    if (there != null && !there.isAir()) continue;
                    out.put(p, light.with(Properties.HANGING, true));
                }
            }
            return;
        }
        List<BlockPos> placed = new ArrayList<>();
        int y = r.y + 2;
        for (int pass = 0; pass < 2; pass++) {
            for (int x = r.x0 + 1; x <= r.x1 - 1; x++) {
                for (int z = r.z0 + 1; z <= r.z1 - 1; z++) {
                    Direction wall = x == r.x0 + 1 ? Direction.WEST : x == r.x1 - 1 ? Direction.EAST : z == r.z0 + 1 ? Direction.NORTH : z == r.z1 - 1 ? Direction.SOUTH : null;
                    if (wall == null) continue;
                    BlockPos p = new BlockPos(x, y, z);
                    BlockPos wallPos = p.offset(wall);
                    BlockState behind = out.get(wallPos);
                    if (behind != null && (behind.isAir() || behind.isIn(BlockTags.DOORS) || behind.getBlock() == mat(m, "window").getBlock())) continue;
                    if (reserved.contains(new BlockPos(x, r.y + 1, z)) || out.containsKey(p) && !out.get(p).isAir()) continue;
                    int spacing = pass == 0 ? 6 : 5;
                    if (placed.stream().anyMatch(q -> q.getManhattanDistance(p) < spacing)) continue;
                    out.put(p, Blocks.WALL_TORCH.getDefaultState().with(WallTorchBlock.FACING, wall.getOpposite()));
                    placed.add(p);
                }
            }
            if (!placed.isEmpty()) break;
        }
    }

    private static void entranceLight(ServerWorld world, BuildingModel m, Map<BlockPos, BlockState> out) {
        for (BuildingModel.Link l : m.links) {
            if (!l.b.equals("outside") || l.built) continue;
            Direction n = wallNormal(m, l);
            // A step up to the door if the floor is above the ground outside.
            BlockPos step = new BlockPos(l.x, l.y - 1, l.z).offset(n);
            BlockState there = world.getBlockState(step);
            if (!out.containsKey(step) && (there.isAir() || there.isReplaceable()) && !world.getBlockState(step.down()).isAir()) {
                out.put(step, roofStairs(m, n.getOpposite()));
                out.put(step.up(), Blocks.AIR.getDefaultState());
                out.put(step.up(2), Blocks.AIR.getDefaultState());
            }
            BlockPos outside = new BlockPos(l.x, l.y + 1, l.z).offset(n).offset(n.rotateYClockwise());
            if (!out.containsKey(outside)) out.put(outside, Blocks.WALL_TORCH.getDefaultState().with(WallTorchBlock.FACING, n));
        }
    }

    /** Furniture for what the room is for (plus anything asked for), against the walls, clear of doors and stairs. */
    private static void furnish(BuildingModel m, BuildingModel.Room r, Map<BlockPos, BlockState> out, Set<BlockPos> reserved) {
        List<String> wanted = new ArrayList<>(r.features);
        if (wanted.isEmpty()) {
            switch (r.purpose) {
                case "bedroom" -> wanted.addAll(List.of("bed", "chest"));
                case "storage" -> wanted.addAll(List.of("chest", "chest", "chest", "chest"));
                case "kitchen" -> wanted.addAll(List.of("furnace", "smoker", "crafting_table", "chest"));
                case "workshop" -> wanted.addAll(List.of("crafting_table", "furnace", "chest"));
                case "library", "study" -> wanted.addAll(List.of("bookshelf", "bookshelf", "bookshelf"));
                default -> {
                }
            }
        }
        int y = r.y + 1;
        List<BlockPos> spots = new ArrayList<>();
        for (int x = r.x0 + 1; x <= r.x1 - 1; x++)
            for (int z = r.z0 + 1; z <= r.z1 - 1; z++)
                if (x == r.x0 + 1 || x == r.x1 - 1 || z == r.z0 + 1 || z == r.z1 - 1) spots.add(new BlockPos(x, y, z));
        Set<BlockPos> used = new HashSet<>();
        for (String f : wanted) {
            String feature = Ids.normalize(f);
            for (BlockPos p : spots) {
                if (used.contains(p) || reserved.contains(p) || nearDoor(m, p)) continue;
                if (feature.contains("chest") && (hasChestNextTo(out, p))) continue;
                if (feature.contains("bed")) {
                    Direction toWall = wallSide(r, p);
                    if (toWall == null) continue;
                    BlockPos foot = p.offset(toWall.getOpposite());
                    if (!r.interiorColumn(foot.getX(), foot.getZ()) || used.contains(foot) || reserved.contains(foot) || nearDoor(m, foot)) continue;
                    BlockState bed = Ids.blockState(feature.endsWith("_bed") ? feature : "red_bed").orElse(Blocks.RED_BED.getDefaultState())
                            .with(BedBlock.FACING, toWall);
                    out.put(foot, bed.with(BedBlock.PART, BedPart.FOOT));
                    out.put(p, bed.with(BedBlock.PART, BedPart.HEAD));
                    used.add(p);
                    used.add(foot);
                    break;
                }
                BlockState state = Ids.blockState(feature.equals("light") ? m.palette.get("light") : feature).orElse(null);
                if (state == null || state.getBlock().asItem() == net.minecraft.item.Items.AIR) break;
                if (state.contains(Properties.HORIZONTAL_FACING)) {
                    Direction toWall = wallSide(r, p);
                    if (toWall != null) state = state.with(Properties.HORIZONTAL_FACING, toWall.getOpposite());
                }
                out.put(p, state);
                used.add(p);
                break;
            }
        }
    }

    @Nullable
    private static Direction wallSide(BuildingModel.Room r, BlockPos p) {
        if (p.getX() == r.x0 + 1) return Direction.WEST;
        if (p.getX() == r.x1 - 1) return Direction.EAST;
        if (p.getZ() == r.z0 + 1) return Direction.NORTH;
        if (p.getZ() == r.z1 - 1) return Direction.SOUTH;
        return null;
    }

    private static boolean nearDoor(BuildingModel m, BlockPos p) {
        for (BuildingModel.Link l : m.links) {
            if (!l.type.equals("stairs") && Math.abs(l.x - p.getX()) + Math.abs(l.z - p.getZ()) <= 1 && Math.abs(l.y - p.getY()) <= 1) return true;
        }
        return false;
    }

    private static boolean hasChestNextTo(Map<BlockPos, BlockState> out, BlockPos p) {
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockState s = out.get(p.offset(d));
            if (s != null && s.isOf(Blocks.CHEST)) return true;
        }
        return false;
    }

    /**
     * Build order for a design: 0 structure (floors, walls, stairs, windows, bottom-up), 1 roofs, 2 top ceilings
     * (after the roof, so the roof can be reached from inside while the top is still open), 3 doors and furniture.
     */
    public static int phase(BuildingModel m, BlockPos p, BlockState s) {
        if (s.isIn(BlockTags.DOORS) || s.isIn(BlockTags.BEDS) || s.getBlock() instanceof net.minecraft.block.BlockWithEntity
                || s.isOf(Blocks.CRAFTING_TABLE) || s.isOf(Blocks.BOOKSHELF)) return 3;
        for (BuildingModel.Room r : m.rooms) {
            if (r.built || roomAbove(m, r) != null) continue;
            int y = p.getY(), cy = r.ceilingY();
            boolean near = p.getX() >= r.x0 - 1 && p.getX() <= r.x1 + 1 && p.getZ() >= r.z0 - 1 && p.getZ() <= r.z1 + 1;
            if (!near) continue;
            if (y == cy && r.interiorColumn(p.getX(), p.getZ())) return 2;
            if (y > cy || y == cy && !r.containsColumn(p.getX(), p.getZ())) return 1;
        }
        return 0;
    }

    // ------------------------------------------------------------------ checks

    /** Problems with a design: blocks cutting into someone else's build, and rooms that can't be walked to. */
    public static List<String> validate(CompanionEntity c, BuildingModel m, Map<BlockPos, BlockState> placements) {
        List<String> problems = new ArrayList<>();
        ServerWorld world = c.serverWorld();
        int conflicts = 0;
        BlockPos firstConflict = null;
        for (Map.Entry<BlockPos, BlockState> e : placements.entrySet()) {
            BlockState now = world.getBlockState(e.getKey());
            if (dev.aicompanion.game.tasks.BuildTask.matches(now, e.getValue()) || now.isAir() || now.isReplaceable()) continue;
            if (BreakPolicy.check(c, e.getKey(), BreakPolicy.Purpose.GATHER) != null) {
                conflicts++;
                if (firstConflict == null) firstConflict = e.getKey();
            }
        }
        if (conflicts > 0) problems.add(conflicts + " blocks would cut into someone else's build (e.g. at " + firstConflict.toShortString() + "); move or shrink that part");

        // Walk it: from outside the front door, can every room be reached?
        BuildingModel.Link front = null;
        for (BuildingModel.Link l : m.links) if (l.b.equals("outside")) front = l;
        if (front != null) {
            Direction out = wallNormal(m, front);
            BlockPos start = new BlockPos(front.x, front.y, front.z).offset(out);
            Set<BlockPos> reached = walk(world, placements, start, m);
            List<String> unreachable = new ArrayList<>();
            int builtReached = 0, built = 0;
            for (BuildingModel.Room r : m.rooms) {
                boolean ok = false;
                for (int x = r.x0 + 1; x < r.x1 && !ok; x++)
                    for (int z = r.z0 + 1; z < r.z1 && !ok; z++) if (reached.contains(new BlockPos(x, r.y + 1, z))) ok = true;
                if (r.built) {
                    built++;
                    if (ok) builtReached++;
                } else if (!ok) unreachable.add("room " + r.id + " (" + r.purpose + ") can't be walked to from the front door");
            }
            if (built > 0 && builtReached == 0) {
                // Can't even get into what's there: the way in is blocked, not the design's fault.
                problems.add("note: the front door at " + front.x + ", " + front.y + ", " + front.z + " seems blocked from outside; clear it before building");
            } else {
                problems.addAll(unreachable);
            }
        }
        return problems;
    }

    private static BlockState at(ServerWorld world, Map<BlockPos, BlockState> placements, BlockPos p) {
        BlockState s = placements.get(p);
        return s != null ? s : world.getBlockState(p);
    }

    private static boolean open(ServerWorld world, Map<BlockPos, BlockState> placements, BlockPos p) {
        BlockState s = at(world, placements, p);
        return s.isAir() || s.isIn(BlockTags.DOORS) || s.getCollisionShape(world, p).isEmpty() && !s.isOf(Blocks.LAVA);
    }

    private static boolean floorAt(ServerWorld world, Map<BlockPos, BlockState> placements, BlockPos feet) {
        BlockState below = at(world, placements, feet.down());
        return !below.isAir() && !below.getCollisionShape(world, feet.down()).isEmpty() || at(world, placements, feet.down()).getBlock() instanceof StairsBlock;
    }

    private static Set<BlockPos> walk(ServerWorld world, Map<BlockPos, BlockState> placements, BlockPos start, BuildingModel m) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, minY = Integer.MAX_VALUE;
        for (BuildingModel.Room r : m.rooms) {
            minX = Math.min(minX, r.x0 - 4); maxX = Math.max(maxX, r.x1 + 4);
            minZ = Math.min(minZ, r.z0 - 4); maxZ = Math.max(maxZ, r.z1 + 4);
            minY = Math.min(minY, r.y - 4); maxY = Math.max(maxY, r.ceilingY() + 2);
        }
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        // Find standing room at or near the start.
        for (int dy = -3; dy <= 3; dy++) {
            BlockPos s = start.up(dy);
            if (open(world, placements, s) && open(world, placements, s.up()) && floorAt(world, placements, s)) {
                q.add(s);
                seen.add(s);
                break;
            }
        }
        while (!q.isEmpty() && seen.size() < 20000) {
            BlockPos p = q.poll();
            for (Direction d : Direction.Type.HORIZONTAL) {
                for (int dy = 1; dy >= -2; dy--) {
                    BlockPos n = p.offset(d).up(dy);
                    if (n.getX() < minX || n.getX() > maxX || n.getZ() < minZ || n.getZ() > maxZ || n.getY() < minY || n.getY() > maxY) continue;
                    if (seen.contains(n)) break;
                    if (dy == 1 && !open(world, placements, p.up(2))) continue;
                    boolean clear = open(world, placements, n) && open(world, placements, n.up());
                    // Stepping or dropping down: the body passes through the next column at its current height first.
                    if (dy < 0) {
                        clear = clear && open(world, placements, p.offset(d).up());
                        for (int k = 0; k > dy && clear; k--) clear = open(world, placements, p.offset(d).up(k));
                    }
                    if (clear && floorAt(world, placements, n)) {
                        seen.add(n);
                        q.add(n);
                        break;
                    }
                }
            }
        }
        return seen;
    }

    // ------------------------------------------------------------------ floor maps and materials

    /** A floor-by-floor map of the building as it would look (for the designer to review). */
    public static String floorMaps(ServerWorld world, BuildingModel m, Map<BlockPos, BlockState> placements) {
        if (m.rooms.isEmpty()) return "";
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE, levels = 0;
        for (BuildingModel.Room r : m.rooms) {
            minX = Math.min(minX, r.x0 - 3); maxX = Math.max(maxX, r.x1 + 3);
            minZ = Math.min(minZ, r.z0 - 3); maxZ = Math.max(maxZ, r.z1 + 3);
            levels = Math.max(levels, r.level + 1);
        }
        StringBuilder sb = new StringBuilder("Legend: # wall, W window, D door, S stairs, . floor, B bed, C chest, F furnace, T crafting table, L light, | railing, space = outside. North is up, x grows to the right.\n");
        for (int level = 0; level < levels; level++) {
            int y = Integer.MIN_VALUE;
            for (BuildingModel.Room r : m.rooms) if (r.level == level) y = r.y + 1;
            if (y == Integer.MIN_VALUE) continue;
            sb.append("Floor ").append(level).append(" (standing at y=").append(y).append(", x ").append(minX).append("..").append(maxX)
                    .append(", z ").append(minZ).append("..").append(maxZ).append("):\n");
            for (int z = minZ; z <= maxZ; z++) {
                StringBuilder row = new StringBuilder();
                for (int x = minX; x <= maxX; x++) row.append(symbol(world, placements, m, new BlockPos(x, y, z), level));
                sb.append(row.toString().replaceAll("\\s+$", "")).append("\n");
            }
        }
        return sb.toString();
    }

    private static char symbol(ServerWorld world, Map<BlockPos, BlockState> placements, BuildingModel m, BlockPos p, int level) {
        BlockState s = at(world, placements, p);
        BlockState up = at(world, placements, p.up());
        BlockState below = at(world, placements, p.down());
        String id = Ids.name(s.getBlock());
        if (s.getBlock() instanceof StairsBlock || below.getBlock() instanceof StairsBlock && s.isAir() && inside(m, p, level)) return 'S';
        if (s.isIn(BlockTags.DOORS)) return 'D';
        if (s.isIn(BlockTags.BEDS)) return 'B';
        if (id.contains("chest") || id.contains("barrel")) return 'C';
        if (id.contains("furnace") || id.contains("smoker")) return 'F';
        if (id.contains("crafting")) return 'T';
        if (id.contains("fence")) return '|';
        if (id.contains("glass") || Ids.name(up.getBlock()).contains("glass")) return 'W';
        if (id.contains("torch") || id.contains("lantern") || Ids.name(up.getBlock()).contains("torch") || Ids.name(up.getBlock()).contains("lantern")) {
            if (inside(m, p, level)) return 'L';
        }
        if (!s.isAir() && !s.getCollisionShape(world, p).isEmpty()) return inside(m, p, level) || onShell(m, p, level) ? '#' : '%';
        return inside(m, p, level) || onBalcony(m, p) ? '.' : ' ';
    }

    private static boolean inside(BuildingModel m, BlockPos p, int level) {
        for (BuildingModel.Room r : m.rooms) if (r.level == level && r.interiorColumn(p.getX(), p.getZ())) return true;
        return false;
    }

    private static boolean onShell(BuildingModel m, BlockPos p, int level) {
        for (BuildingModel.Room r : m.rooms) if (r.level == level && r.containsColumn(p.getX(), p.getZ())) return true;
        return false;
    }

    private static boolean onBalcony(BuildingModel m, BlockPos p) {
        for (BuildingModel.Balcony b : m.balconies) {
            BuildingModel.Room r = m.room(b.room);
            if (r == null || p.getY() != r.y + 1) continue;
            Direction side = Direction.byName(b.side);
            if (side == null) continue;
            int wall = side == Direction.EAST ? r.x1 : side == Direction.WEST ? r.x0 : side == Direction.SOUTH ? r.z1 : r.z0;
            int c = side.getAxis() == Direction.Axis.X ? p.getX() : p.getZ();
            int t = side.getAxis() == Direction.Axis.X ? p.getZ() : p.getX();
            int lo = side.getAxis() == Direction.Axis.X ? r.z0 : r.x0, hi = side.getAxis() == Direction.Axis.X ? r.z1 : r.x1;
            int dir = side == Direction.EAST || side == Direction.SOUTH ? 1 : -1;
            if ((c - wall) * dir >= 1 && (c - wall) * dir <= b.depth && t > lo && t < hi) return true;
        }
        return false;
    }

    /** Items needed for the blocks that aren't already there. */
    public static Map<Item, Integer> materials(ServerWorld world, Map<BlockPos, BlockState> placements) {
        Map<Item, Integer> need = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockState> e : placements.entrySet()) {
            BlockState s = e.getValue();
            if (s.isAir() || dev.aicompanion.game.tasks.BuildTask.matches(world.getBlockState(e.getKey()), s)) continue;
            if (s.contains(Properties.DOUBLE_BLOCK_HALF) && s.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) continue;
            if (s.contains(Properties.BED_PART) && s.get(Properties.BED_PART) == BedPart.HEAD) continue;
            Block b = s.getBlock();
            Item item = b == Blocks.WALL_TORCH ? net.minecraft.item.Items.TORCH : b.asItem();
            if (item == net.minecraft.item.Items.AIR) continue;
            need.merge(item, 1, Integer::sum);
        }
        return need;
    }
}
