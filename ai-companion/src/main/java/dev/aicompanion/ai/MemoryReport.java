package dev.aicompanion.ai;

import dev.aicompanion.entity.CompanionEntity;
import dev.aicompanion.game.Ids;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Turns a companion's memory into short text for the models (recall tool, situation reports, commands). */
public final class MemoryReport {
    private MemoryReport() {}

    /** Compact overview for every situation report: places, storage and remembered resources. */
    public static String summary(CompanionMemory m, CompanionEntity c) {
        StringBuilder sb = new StringBuilder();
        String dim = c.getWorld().getRegistryKey().getValue().toString();
        BlockPos here = c.getBlockPos();
        if (!m.places.isEmpty()) {
            List<String> parts = new ArrayList<>();
            m.places.forEach((name, l) -> parts.add(name + " [" + l.type + "] " + where(l.dimension, l.x, l.y, l.z, dim, here)));
            sb.append("Places you know: ").append(String.join("; ", parts)).append(".\n");
        }
        if (!m.chests.isEmpty()) {
            long mine = m.chests.values().stream().filter(r -> r.owner.equals("self")).count();
            sb.append("Chests you remember: ").append(m.chests.size()).append(" (").append(mine).append(" your own). Use recall to find items in them.\n");
        }
        List<String> resources = new ArrayList<>();
        for (Map.Entry<String, List<CompanionMemory.Sighting>> e : m.sightings.entrySet()) {
            e.getValue().stream().filter(s -> s.dimension.equals(dim))
                    .min(Comparator.comparingDouble(s -> here.getSquaredDistance(s.x, s.y, s.z)))
                    .ifPresent(s -> resources.add(e.getKey() + " " + where(s.dimension, s.x, s.y, s.z, dim, here)));
        }
        if (!m.buildings.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (var b : m.buildings) {
                int[] ctr = b.center();
                parts.add(b.name + " (" + b.rooms.size() + " rooms: " + String.join(", ", b.rooms.stream().map(r -> r.purpose).distinct().toList()) + ") "
                        + where(b.dimension, ctr[0], ctr[1], ctr[2], dim, here));
            }
            sb.append("Your buildings: ").append(String.join("; ", parts)).append(".\n");
        }
        if (!m.drafts.isEmpty()) sb.append("Designs waiting to be built (build_plan): ").append(String.join(", ", m.drafts.keySet())).append(".\n");
        if (!resources.isEmpty()) sb.append("Resources you've seen: ").append(String.join("; ", resources.subList(0, Math.min(12, resources.size())))).append(".\n");
        return sb.toString();
    }

    public static String recall(CompanionMemory m, CompanionEntity c, String query) {
        String dim = c.getWorld().getRegistryKey().getValue().toString();
        BlockPos here = c.getBlockPos();
        String q = Ids.normalize(query == null ? "" : query);
        StringBuilder sb = new StringBuilder();
        if (q.isBlank()) {
            sb.append(summary(m, c));
            if (!m.structures.isEmpty()) {
                List<String> built = new ArrayList<>();
                for (CompanionMemory.Structure s : m.structures) built.add(s.label + (s.purpose.isBlank() ? "" : " (" + s.purpose + ")") + " at "
                        + ((s.minX + s.maxX) / 2) + ", " + s.minY + ", " + ((s.minZ + s.maxZ) / 2));
                sb.append("Things you built: ").append(String.join("; ", built)).append(".\n");
            }
            for (CompanionMemory.ChestRecord r : m.chests.values()) sb.append(chestLine(r, dim, here)).append("\n");
            if (!m.chestPermissions.isEmpty()) sb.append("Players who let you use their chests: ").append(String.join(", ", m.chestPermissions)).append(".\n");
            return sb.isEmpty() ? "You don't remember anything in particular yet." : sb.toString();
        }
        List<CompanionMemory.ChestRecord> chests = m.chestsWith(q);
        for (CompanionMemory.ChestRecord r : chests) sb.append(chestLine(r, dim, here)).append("\n");
        m.places.forEach((name, l) -> {
            if (name.contains(q) || l.type.equals(q) || l.note.toLowerCase().contains(q)) {
                sb.append("Place '").append(name).append("' [").append(l.type).append("] ").append(where(l.dimension, l.x, l.y, l.z, dim, here))
                        .append(l.note.isBlank() ? "" : " - " + l.note).append("\n");
            }
        });
        m.sightings.forEach((kind, list) -> {
            if (kind.contains(q)) for (CompanionMemory.Sighting s : list) {
                sb.append("Saw ").append(kind).append(" ").append(where(s.dimension, s.x, s.y, s.z, dim, here)).append(" ").append(ago(s.seen)).append("\n");
            }
        });
        for (CompanionMemory.Structure s : m.structures) {
            if (s.label.toLowerCase().contains(q) || s.purpose.equals(q)) sb.append("You built ").append(s.label).append(" around ")
                    .append((s.minX + s.maxX) / 2).append(", ").append(s.minY).append(", ").append((s.minZ + s.maxZ) / 2).append("\n");
        }
        return sb.isEmpty() ? "Nothing in memory about '" + query + "'." : sb.toString();
    }

    private static String chestLine(CompanionMemory.ChestRecord r, String dim, BlockPos here) {
        List<String> items = new ArrayList<>();
        r.contents.forEach((k, v) -> items.add(v + " " + k));
        String owner = r.owner.equals("self") ? "your chest" : r.owner.equals("natural") ? "a natural chest" : r.owner + "'s chest";
        return (r.label.isBlank() ? "" : "'" + r.label + "' ") + owner + " " + where(r.dimension, r.x, r.y, r.z, dim, here) + ": "
                + (items.isEmpty() ? "empty" : String.join(", ", items)) + " (checked " + ago(r.seen) + ")";
    }

    private static String where(String d, int x, int y, int z, String dim, BlockPos here) {
        if (!d.equals(dim)) return "at " + x + ", " + y + ", " + z + " (other dimension)";
        return "at " + x + ", " + y + ", " + z + " (" + Math.round(Math.sqrt(here.getSquaredDistance(x, y, z))) + " blocks away)";
    }

    private static String ago(long time) {
        long min = (System.currentTimeMillis() - time) / 60000;
        return min < 1 ? "just now" : min < 60 ? min + " min ago" : (min / 60) + " h ago";
    }
}
