package dev.aicompanion.ai;

import dev.aicompanion.entity.CompanionEntity;
import dev.aicompanion.game.Ids;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The character's goals as a tree (long-term goals broken into medium and short ones) plus the logic that picks
 * what to work on next. It's not a checklist: goals are scored by importance, urgency, momentum and today's day
 * plan, blockers become sub-goals that pause their parent, and free time competes with all of it.
 */
public final class Goals {
    private Goals() {}

    public static class Goal {
        public String id;
        public String title;
        /** short, medium or long */
        public String horizon = "medium";
        /** 1-10, set by the character */
        public int importance = 5;
        @Nullable public String parent;
        /** active, done, dropped */
        public String status = "active";
        /** "self", or the player who asked for it */
        public String from = "self";
        /** Blocks its parent until done (a tool that broke, missing materials...). */
        public boolean blocker;
        /** Optional check that tells when it's achieved, however that happens. */
        @Nullable public Condition condition;
        public String progress = "";
        public long created;
        public long lastWorked;
        public int attempts;
        public long finished;
    }

    public static class Condition {
        /**
         * have_item (own at least count of any of items), gather_item (get count more than it had when the goal started),
         * have_place (a place of this type), built (a structure for this purpose)
         */
        public String type;
        /** For gather_item: how many it had when the goal started (-1 until first checked). */
        public int baseline = -1;
        public List<String> items = new ArrayList<>();
        public int count = 1;
        public String target = "";

        public String describe() {
            return switch (type) {
                case "have_item" -> "have " + count + " " + String.join(" or ", items);
                case "gather_item" -> "gather " + count + " more " + String.join(" or ", items);
                case "have_place" -> "have a " + target;
                case "built" -> "built a " + target;
                default -> type;
            };
        }
    }

    // ------------------------------------------------------------------ the board

    public static Goal add(CompanionMemory m, String title, String horizon, int importance, @Nullable String parent, String from, @Nullable Condition condition) {
        Goal g = new Goal();
        g.id = nextId(m);
        g.title = title;
        g.horizon = normalizeHorizon(horizon);
        g.importance = Math.max(1, Math.min(10, importance));
        g.parent = parent != null && find(m, parent) != null ? parent : null;
        g.from = from == null ? "self" : from;
        g.condition = condition;
        g.created = System.currentTimeMillis();
        m.goals.add(g);
        return g;
    }

    private static String nextId(CompanionMemory m) {
        int max = 0;
        for (Goal g : m.goals) {
            try {
                max = Math.max(max, Integer.parseInt(g.id.substring(1)));
            } catch (Exception ignored) {
            }
        }
        return "g" + (max + 1);
    }

    public static String normalizeHorizon(String h) {
        String s = h == null ? "" : h.toLowerCase(Locale.ROOT);
        return s.startsWith("s") ? "short" : s.startsWith("l") ? "long" : "medium";
    }

    @Nullable
    public static Goal find(CompanionMemory m, String id) {
        if (id == null) return null;
        for (Goal g : m.goals) if (g.id.equalsIgnoreCase(id.trim())) return g;
        return null;
    }

    public static List<Goal> active(CompanionMemory m) {
        List<Goal> out = new ArrayList<>();
        for (Goal g : m.goals) if (g.status.equals("active")) out.add(g);
        return out;
    }

    /** A goal is blocked while any of its active sub-goals is a blocker. */
    public static boolean isBlocked(CompanionMemory m, Goal g) {
        for (Goal c : m.goals) if (g.id.equals(c.parent) && c.blocker && c.status.equals("active")) return true;
        return false;
    }

    public static boolean hasActiveChildren(CompanionMemory m, Goal g) {
        for (Goal c : m.goals) if (g.id.equals(c.parent) && c.status.equals("active")) return true;
        return false;
    }

    public static void finish(CompanionMemory m, Goal g, String status) {
        g.status = status;
        g.finished = System.currentTimeMillis();
        if (!status.equals("done")) {
            // Dropping a goal drops what it was for.
            for (Goal c : m.goals) if (g.id.equals(c.parent) && c.status.equals("active")) finish(m, c, status);
        }
        // Forget old finished goals.
        List<Goal> finished = new ArrayList<>();
        for (Goal x : m.goals) if (!x.status.equals("active")) finished.add(x);
        finished.sort(Comparator.comparingLong(x -> x.finished));
        for (int i = 0; i < finished.size() - 25; i++) m.goals.remove(finished.get(i));
    }

    /** The chain from a goal up to its long-term root, e.g. "mine the mountain > get a new pickaxe". */
    public static String chain(CompanionMemory m, Goal g) {
        List<String> parts = new ArrayList<>();
        Goal cur = g;
        int guard = 0;
        while (cur != null && guard++ < 6) {
            parts.add(0, cur.title);
            cur = find(m, cur.parent);
        }
        return String.join(" > ", parts);
    }

    /** The goal tree as text for the models. */
    public static String describe(CompanionMemory m) {
        List<Goal> act = active(m);
        if (act.isEmpty()) return "No goals right now.";
        StringBuilder sb = new StringBuilder();
        for (Goal g : act) if (g.parent == null || find(m, g.parent) == null) appendTree(m, g, 0, sb);
        List<Goal> recent = new ArrayList<>();
        for (Goal g : m.goals) if (g.status.equals("done") && System.currentTimeMillis() - g.finished < 30 * 60_000) recent.add(g);
        if (!recent.isEmpty()) {
            sb.append("Recently achieved: ");
            List<String> t = new ArrayList<>();
            for (Goal g : recent) t.add(g.title);
            sb.append(String.join("; ", t)).append("\n");
        }
        return sb.toString();
    }

    private static void appendTree(CompanionMemory m, Goal g, int depth, StringBuilder sb) {
        sb.append("  ".repeat(depth)).append("- [").append(g.id).append(", ").append(g.horizon).append(", importance ").append(g.importance).append("] ")
                .append(g.title);
        if (!g.from.equals("self")) sb.append(" (for ").append(g.from).append(")");
        if (g.condition != null) sb.append(" {done when: ").append(g.condition.describe()).append("}");
        if (isBlocked(m, g)) sb.append(" (waiting on a sub-goal)");
        if (!g.progress.isBlank()) sb.append(" - ").append(g.progress);
        sb.append("\n");
        for (Goal c : m.goals) if (g.id.equals(c.parent) && c.status.equals("active")) appendTree(m, c, depth + 1, sb);
    }

    // ------------------------------------------------------------------ achieved however it happened

    /** Checks goal conditions against inventory, own chests, places and builds. Returns goals newly achieved. */
    public static List<Goal> checkConditions(CompanionMemory m, CompanionEntity c) {
        List<Goal> achieved = new ArrayList<>();
        for (Goal g : active(m)) {
            if (g.condition == null || !met(m, c, g.condition)) continue;
            finish(m, g, "done");
            achieved.add(g);
        }
        return achieved;
    }

    private static boolean met(CompanionMemory m, CompanionEntity c, Condition cond) {
        switch (cond.type == null ? "" : cond.type) {
            case "have_item", "gather_item" -> {
                int total = 0;
                for (String name : cond.items) {
                    Item item = Ids.item(name).orElse(null);
                    if (item == null) continue;
                    total += c.count(item);
                    for (CompanionMemory.ChestRecord r : m.chests.values()) {
                        if (r.owner.equals("self")) total += r.contents.getOrDefault(Ids.name(item), 0);
                    }
                }
                if (cond.type.equals("gather_item")) {
                    if (cond.baseline < 0) {
                        cond.baseline = total;
                        return false;
                    }
                    return total - cond.baseline >= Math.max(1, cond.count);
                }
                return total >= Math.max(1, cond.count);
            }
            case "have_place" -> {
                String t = cond.target.toLowerCase(Locale.ROOT);
                for (var e : m.places.entrySet()) {
                    if (!e.getValue().type.equals("poi") && (e.getValue().type.equals(t) || e.getKey().contains(t))) return true;
                }
                return false;
            }
            case "built" -> {
                String t = cond.target.toLowerCase(Locale.ROOT);
                for (CompanionMemory.Structure s : m.structures) {
                    if (s.purpose.equalsIgnoreCase(t) || s.label.toLowerCase(Locale.ROOT).contains(t)) return true;
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    /** All items of the same kind of tool as this one (any pickaxe, any axe...), for "replace my broken tool" goals. */
    public static List<String> sameKindOfTool(Item broken) {
        List<String> out = new ArrayList<>();
        Class<?> kind = broken.getClass();
        for (Item item : Registries.ITEM) {
            if (item.getClass() == kind && new ItemStack(item).isDamageable()) out.add(Ids.name(item));
        }
        if (out.isEmpty()) out.add(Ids.name(broken));
        return out;
    }

    // ------------------------------------------------------------------ choosing what to do

    /** What the body should do next when it has nothing to do: a goal to work on, or free time. */
    public record Choice(@Nullable Goal goal, double score, String why) {
        public boolean isFreeTime() {
            return goal == null;
        }
    }

    public static Choice choose(CompanionMemory m, PersonaProfile p, @Nullable String lastGoalId) {
        String day = m.dayPlan.kind;
        Goal best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        String bestWhy = "";
        long now = System.currentTimeMillis();
        for (Goal g : active(m)) {
            if (isBlocked(m, g) || hasActiveChildren(m, g)) continue; // work on the leaves; sub-goals come first
            // A step toward something important matters almost as much as the thing itself.
            int importance = g.importance;
            boolean promise = !g.from.equals("self");
            Goal up = find(m, g.parent);
            for (int i = 0; up != null && i < 6; i++, up = find(m, up.parent)) {
                importance = Math.max(importance, up.importance - 1);
                if (!up.from.equals("self")) promise = true;
            }
            double score = importance;
            String why = "importance " + importance;
            if (promise) {
                score += 10;
                why += ", promised to " + g.from;
            }
            if (g.blocker) {
                score += 6;
                why += ", blocking " + (g.parent == null ? "something" : g.parent);
            }
            // Today's plan decides how goals and free time trade off.
            double dayWeight = switch (day) {
                case "work" -> g.horizon.equals("long") ? 0.8 : 1.2;
                case "goals" -> g.horizon.equals("short") && !g.blocker ? 1.0 : 1.4;
                case "free" -> promise || g.blocker || g.importance >= 9 ? 0.9 : 0.3;
                default -> 1.0;
            };
            score *= dayWeight;
            // Keep going with what it was doing, but don't hammer something that keeps failing.
            if (g.id.equals(lastGoalId)) {
                score += 2;
                why += ", already working on it";
            }
            if (g.attempts >= 3) score -= (g.attempts - 2) * 1.5;
            // Long-neglected goals slowly bubble back up so long-term ones still get done.
            double hoursIdle = (now - Math.max(g.lastWorked, g.created)) / 3_600_000.0;
            score += Math.min(2, hoursIdle);
            if (score > bestScore) {
                best = g;
                bestScore = score;
                bestWhy = why;
            }
        }
        double freeTime = switch (day) {
            case "free" -> 9;
            case "mixed" -> 4.5;
            case "work" -> 2;
            default -> 1.5;
        } + (10 - p.diligence()) * 0.35;
        if (best == null || freeTime > bestScore) return new Choice(null, freeTime, "free time (" + day + " day)");
        return new Choice(best, bestScore, bestWhy);
    }
}
