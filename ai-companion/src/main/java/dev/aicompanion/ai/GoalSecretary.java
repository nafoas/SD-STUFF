package dev.aicompanion.ai;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import dev.aicompanion.AiCompanionMod;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns what the character says about its plans (in its own words) into changes to its goal tree.
 * The character decides the goals; this only writes them down in a form the scheduler can use.
 */
public final class GoalSecretary {
    private GoalSecretary() {}

    public record GoalEdit(
            @JsonPropertyDescription("add, update, done or drop") String op,
            @JsonPropertyDescription("Existing goal id for update/done/drop; empty for add") String id,
            @JsonPropertyDescription("Short title in the character's spirit, e.g. 'build a cozy spruce cottage'") String title,
            @JsonPropertyDescription("short (one job, minutes), medium (several jobs, an hour or so) or long (a big project, days)") String horizon,
            @JsonPropertyDescription("1-10, how much the character cares") int importance,
            @JsonPropertyDescription("Id of the goal this is a step toward, or the exact title of a goal added earlier in this list, or empty") String parent,
            @JsonPropertyDescription("How to tell it's achieved: none, have_item (owning it is the point), gather_item (collecting more of it is the point), have_place or built") String conditionType,
            @JsonPropertyDescription("For have_item/gather_item: acceptable Minecraft item ids (e.g. all pickaxe types)") List<String> conditionItems,
            @JsonPropertyDescription("For have_item/gather_item: how many") int conditionCount,
            @JsonPropertyDescription("For have_place/built: the kind of place or build, e.g. farm, home, mine, storage") String conditionTarget) {}

    public record GoalEdits(List<GoalEdit> edits) {}

    /** Updates the brain's goals from what the character just said. Returns a short description of the changes. */
    public static String update(CompanionBrain brain, String characterWords) {
        CompanionMemory m = brain.memory();
        String prompt = "You keep the goal list for " + brain.name() + ", a character living in Minecraft. " + brain.name()
                + " decides their own goals; you only write them down.\n\nCurrent goals:\n" + Goals.describe(m)
                + "\n" + brain.name() + " just said:\n<words>\n" + characterWords + "\n</words>\n\n"
                + "Turn what they said into edits to the goal list. Only record goals they actually expressed or clearly implied, keeping their spirit and wording. "
                + "Mark goals done or dropped if they say so. Adjust importance if their priorities shifted. "
                + "For a new medium or long goal, add 1 to 3 first steps as children only if they mentioned them or they're obvious. "
                + "Add a checkable condition when there is one (e.g. 'get a better pickaxe' -> have_item with all better pickaxes; 'mine a pile of stone' -> gather_item cobblestone; "
                + "'start a farm' -> have_place farm), "
                + "otherwise conditionType none. Return no edits if nothing changed.";
        try {
            GoalEdits edits = ClaudeActionLayer.structured(prompt, GoalEdits.class);
            return apply(brain, edits);
        } catch (Exception e) {
            AiCompanionMod.LOGGER.warn("[{}] couldn't update goals: {}", brain.name(), e.getMessage());
            return "";
        }
    }

    static String apply(CompanionBrain brain, GoalEdits edits) {
        if (edits == null || edits.edits() == null) return "";
        CompanionMemory m = brain.memory();
        Map<String, String> addedByTitle = new HashMap<>();
        StringBuilder changes = new StringBuilder();
        for (GoalEdit e : edits.edits()) {
            String op = e.op() == null ? "" : e.op().toLowerCase();
            Goals.Goal existing = Goals.find(m, e.id());
            switch (op) {
                case "add" -> {
                    if (e.title() == null || e.title().isBlank()) continue;
                    String parent = e.parent() == null ? null : addedByTitle.getOrDefault(e.parent().toLowerCase(), e.parent());
                    Goals.Goal g = Goals.add(m, e.title(), e.horizon(), e.importance() <= 0 ? 5 : e.importance(), parent, "self", condition(e));
                    addedByTitle.put(e.title().toLowerCase(), g.id);
                    changes.append("new goal: ").append(g.title).append("; ");
                }
                case "update" -> {
                    if (existing == null) continue;
                    if (e.title() != null && !e.title().isBlank()) existing.title = e.title();
                    if (e.horizon() != null && !e.horizon().isBlank()) existing.horizon = Goals.normalizeHorizon(e.horizon());
                    if (e.importance() > 0) existing.importance = Math.min(10, e.importance());
                    Goals.Condition c = condition(e);
                    if (c != null) existing.condition = c;
                    changes.append("changed: ").append(existing.title).append("; ");
                }
                case "done", "drop", "dropped" -> {
                    if (existing == null || !existing.status.equals("active")) continue;
                    Goals.finish(m, existing, op.equals("done") ? "done" : "dropped");
                    changes.append(op.equals("done") ? "done: " : "dropped: ").append(existing.title).append("; ");
                }
                default -> {
                }
            }
        }
        return changes.toString();
    }

    private static Goals.Condition condition(GoalEdit e) {
        String type = e.conditionType() == null ? "none" : e.conditionType().trim().toLowerCase();
        if (!type.equals("have_item") && !type.equals("gather_item") && !type.equals("have_place") && !type.equals("built")) return null;
        if (type.endsWith("_item") && (e.conditionItems() == null || e.conditionItems().isEmpty())) return null;
        Goals.Condition c = new Goals.Condition();
        c.type = type;
        if (e.conditionItems() != null) c.items = new java.util.ArrayList<>(e.conditionItems());
        c.count = Math.max(1, e.conditionCount());
        c.target = e.conditionTarget() == null ? "" : e.conditionTarget();
        return c;
    }
}
