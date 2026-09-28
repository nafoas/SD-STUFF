package dev.aicompanion.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What a companion remembers across sessions. Saved to config/ai-companion/memory/<id>.json. */
public class CompanionMemory {
    public static class Location {
        public String dimension;
        public int x, y, z;

        public Location() {}

        public Location(String dimension, int x, int y, int z) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /** How the character feels about each player, from -10 (hates) to 10 (adores). Keyed by lowercase name. */
    public Map<String, Integer> opinions = new LinkedHashMap<>();
    public Map<String, Location> places = new LinkedHashMap<>();
    /** Recent notable events, oldest first. */
    public List<String> events = new ArrayList<>();
    /** Recent conversation with the character AI (compact form, without the situation reports). */
    public List<AicordClient.ChatMessage> conversation = new ArrayList<>();

    private static final int MAX_EVENTS = 30;
    private static final int MAX_CONVERSATION = 24;

    public int opinionOf(String player) {
        return opinions.getOrDefault(player.toLowerCase(), 0);
    }

    public int adjustOpinion(String player, int delta) {
        int value = Math.max(-10, Math.min(10, opinionOf(player) + delta));
        opinions.put(player.toLowerCase(), value);
        return value;
    }

    public void addEvent(String event) {
        events.add(event);
        while (events.size() > MAX_EVENTS) events.remove(0);
    }

    public void addConversation(AicordClient.ChatMessage message) {
        conversation.add(message);
        while (conversation.size() > MAX_CONVERSATION) conversation.remove(0);
        // AICord expects the history to start with a user turn.
        while (!conversation.isEmpty() && !conversation.get(0).role().equals("user")) conversation.remove(0);
    }

    public static String describeOpinion(int value) {
        if (value <= -7) return "hate";
        if (value <= -3) return "dislike";
        if (value < 3) return "neutral";
        if (value < 7) return "like";
        return "adore";
    }
}
