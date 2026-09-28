package dev.aicompanion.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Condenses a stretch of the activity log into a short account for the character's check-in. */
public final class Digest {
    private Digest() {}

    private static final Pattern ITEM_CHANGE = Pattern.compile("([+-]\\d+) (\\S+)");
    private static final int MAX_LINES = 18;

    public static String of(List<CompanionMemory.JournalEntry> entries) {
        Map<String, Integer> items = new LinkedHashMap<>();
        Map<String, Integer> fights = new LinkedHashMap<>();
        int ranAway = 0;
        int hurt = 0;
        List<String> lines = new ArrayList<>();
        for (CompanionMemory.JournalEntry e : entries) {
            switch (e.kind) {
                case "items" -> {
                    Matcher m = ITEM_CHANGE.matcher(e.text);
                    while (m.find()) items.merge(m.group(2), Integer.parseInt(m.group(1)), Integer::sum);
                }
                case "combat" -> {
                    if (e.text.startsWith("Fighting ")) fights.merge(e.text.substring("Fighting ".length()), 1, Integer::sum);
                    else if (e.text.startsWith("Ran away")) ranAway++;
                }
                case "damage" -> {
                    if (e.text.contains(" hit you")) lines.add(e.text);
                    else hurt++;
                }
                case "task_start", "chat", "said", "decision" -> {
                    // chat is shown separately; starts are implied by their ends; decisions the character remembers itself
                }
                default -> lines.add(e.text);
            }
        }
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, lines.size() - MAX_LINES);
        if (from > 0) sb.append("- (").append(from).append(" earlier things)\n");
        for (String l : lines.subList(from, lines.size())) sb.append("- ").append(l).append("\n");
        if (!fights.isEmpty()) {
            List<String> f = new ArrayList<>();
            fights.forEach((who, n) -> f.add(n > 1 ? n + "x " + who : who));
            sb.append("- Fought ").append(String.join(", ", f)).append(ranAway > 0 ? "; ran away " + ranAway + " time(s)" : "").append("\n");
        } else if (ranAway > 0) {
            sb.append("- Ran away from danger ").append(ranAway).append(" time(s)\n");
        }
        if (hurt > 0) sb.append("- Got hurt ").append(hurt).append(" time(s)\n");
        List<String> gained = new ArrayList<>();
        List<String> lost = new ArrayList<>();
        items.forEach((item, n) -> {
            if (n > 0) gained.add(n + " " + item);
            else if (n < 0) lost.add(-n + " " + item);
        });
        if (!gained.isEmpty()) sb.append("- Gained ").append(String.join(", ", gained)).append("\n");
        if (!lost.isEmpty()) sb.append("- Used up or gave away ").append(String.join(", ", lost)).append("\n");
        return sb.toString();
    }
}
