package dev.aicompanion.game.build;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A building as a player thinks of it: rooms (with a purpose, a floor they're on, and a size), how they connect
 * (doors, openings, hallways, stairs), balconies, and the building's materials. Rooms are stored by their outer
 * shell: walls sit on the boundary, so neighbouring rooms share a wall.
 */
public class BuildingModel {
    public String name = "home";
    public String dimension;
    /** gable or flat */
    public String roof = "gable";
    /** Material roles: wall, pillar, floor, ceiling, roof (a wood/stone type with stairs and slabs), window, light, door, fence, foundation. */
    public Map<String, String> palette = new LinkedHashMap<>();
    public List<Room> rooms = new ArrayList<>();
    public List<Link> links = new ArrayList<>();
    public List<Balcony> balconies = new ArrayList<>();
    /** Read from the world rather than designed; its roof shape is unknown. */
    public boolean scanned;
    /** When it was last built onto or scanned. */
    public long updated;
    /** When building this design started (its first build_plan), so throwaway blocks from every round get cleared. */
    public long buildStarted;

    public static class Room {
        public String id;
        public String purpose = "room";
        public int level;
        /** Outer shell, inclusive (walls on these lines). */
        public int x0, z0, x1, z1;
        /** The floor's block layer; people stand at y + 1. */
        public int y;
        /** Air height inside. */
        public int height = 3;
        public List<String> features = new ArrayList<>();
        /** Already in the world (built earlier or scanned); only new rooms get generated. */
        public boolean built;

        public int interiorWidth() {
            return x1 - x0 - 1;
        }

        public int interiorDepth() {
            return z1 - z0 - 1;
        }

        public int ceilingY() {
            return y + height + 1;
        }

        public boolean containsColumn(int x, int z) {
            return x >= x0 && x <= x1 && z >= z0 && z <= z1;
        }

        public boolean interiorColumn(int x, int z) {
            return x > x0 && x < x1 && z > z0 && z < z1;
        }

        public String describe() {
            return id + ": " + purpose + ", floor " + level + ", " + interiorWidth() + "x" + interiorDepth() + " inside"
                    + (features.isEmpty() ? "" : ", has " + String.join(", ", features)) + (built ? "" : " (planned)");
        }
    }

    public static class Link {
        public String a, b;
        /** door, opening or stairs */
        public String type = "door";
        public int x, y, z;
        public boolean built;
    }

    public static class Balcony {
        public String room;
        public String side;
        public int depth = 2;
        public boolean built;
    }

    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

    public BuildingModel copy() {
        return GSON.fromJson(GSON.toJson(this), BuildingModel.class);
    }

    public void translate(int dx, int dy, int dz) {
        for (Room r : rooms) {
            r.x0 += dx; r.x1 += dx; r.z0 += dz; r.z1 += dz; r.y += dy;
        }
        for (Link l : links) {
            l.x += dx; l.y += dy; l.z += dz;
        }
    }

    /** min x, min z, max x, max z of the shells. */
    public int[] bounds() {
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (Room r : rooms) {
            b[0] = Math.min(b[0], r.x0); b[1] = Math.min(b[1], r.z0);
            b[2] = Math.max(b[2], r.x1); b[3] = Math.max(b[3], r.z1);
        }
        return b;
    }

    public boolean contains(String dim, int x, int y, int z, int margin) {
        if (dimension != null && dim != null && !dimension.equals(dim)) return false;
        for (Room r : rooms) {
            if (x >= r.x0 - margin && x <= r.x1 + margin && z >= r.z0 - margin && z <= r.z1 + margin && y >= r.y - margin && y <= r.ceilingY() + margin) return true;
        }
        return false;
    }

    public boolean fullyBuilt() {
        return rooms.stream().allMatch(r -> r.built) && links.stream().allMatch(l -> l.built) && balconies.stream().allMatch(b -> b.built);
    }

    public void markBuilt() {
        rooms.forEach(r -> r.built = true);
        links.forEach(l -> l.built = true);
        balconies.forEach(b -> b.built = true);
        updated = System.currentTimeMillis();
        buildStarted = 0;
    }

    public int[] center() {
        int[] b = bounds();
        int y = rooms.isEmpty() ? 0 : rooms.get(0).y + 1;
        return new int[]{(b[0] + b[2]) / 2, y, (b[1] + b[3]) / 2};
    }

    public Room room(String id) {
        if (id == null) return null;
        for (Room r : rooms) if (r.id.equalsIgnoreCase(id)) return r;
        return null;
    }

    public String nextRoomId() {
        return "r" + (rooms.size() + 1);
    }

    public String describe() {
        StringBuilder sb = new StringBuilder(name + " (" + roof + " roof, " + palette.getOrDefault("wall", "?") + " walls):\n");
        for (Room r : rooms) sb.append("  ").append(r.describe()).append("\n");
        for (Balcony b : balconies) sb.append("  balcony on the ").append(b.side).append(" of ").append(b.room).append(b.built ? "" : " (planned)").append("\n");
        return sb.toString();
    }
}
