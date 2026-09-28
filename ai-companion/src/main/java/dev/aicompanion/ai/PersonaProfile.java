package dev.aicompanion.ai;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * Behavior profile derived from a character's own description of itself (the spawn "interview").
 * The traits drive the fast reflex code directly, and the whole profile is given to the action layer
 * so the character does things its own way. Saved to config/ai-companion/profiles/<id>.json and editable.
 */
public record PersonaProfile(
        @JsonPropertyDescription("0 = flees from everything, 10 = charges into any fight") int bravery,
        @JsonPropertyDescription("0 = very lazy, does the bare minimum; 10 = tireless, goes above and beyond") int diligence,
        @JsonPropertyDescription("0 = hoards everything, 10 = gives freely") int generosity,
        @JsonPropertyDescription("0 = stays put, 10 = constantly exploring") int curiosity,
        @JsonPropertyDescription("0 = independent, ignores the player who brought them; 10 = devoted, sticks close") int loyalty,
        @JsonPropertyDescription("0 = almost silent, 10 = chats constantly") int sociability,
        @JsonPropertyDescription("0 = sloppy and quick, 10 = meticulous perfectionist") int perfectionism,
        @JsonPropertyDescription("Minecraft activities this character would choose on their own, most favorite first, e.g. mining, building, farming, exploring, fighting monsters, fishing, decorating") List<String> favoriteActivities,
        @JsonPropertyDescription("Things this character dislikes or refuses to do") List<String> dislikes,
        @JsonPropertyDescription("One or two sentences describing how this character builds: style, size, level of detail") String buildStyle,
        @JsonPropertyDescription("4 to 8 Minecraft block ids (like spruce_planks, stone_bricks) this character prefers building with") List<String> buildPalette,
        @JsonPropertyDescription("Two or three sentences on how this character behaves and works in Minecraft, written as instructions for someone controlling their body") String behaviorSummary) {

    public static PersonaProfile neutral() {
        return new PersonaProfile(5, 5, 5, 5, 5, 5, 5,
                List.of("building", "mining"), List.of(),
                "Practical, medium-sized builds with some detail.",
                List.of("oak_planks", "oak_log", "cobblestone", "glass_pane"),
                "Behaves like an ordinary, reasonable player.");
    }

    public PersonaProfile clamped() {
        return new PersonaProfile(c(bravery), c(diligence), c(generosity), c(curiosity), c(loyalty), c(sociability), c(perfectionism),
                favoriteActivities == null ? List.of() : favoriteActivities,
                dislikes == null ? List.of() : dislikes,
                buildStyle == null ? "" : buildStyle,
                buildPalette == null ? List.of() : buildPalette,
                behaviorSummary == null ? "" : behaviorSummary);
    }

    private static int c(int v) {
        return Math.max(0, Math.min(10, v));
    }

    /** Health fraction below which the companion retreats from a fight. */
    public float retreatHealthFraction() {
        return Math.max(0.1f, 0.6f - bravery * 0.05f);
    }

    /** Whether the companion picks fights with nearby monsters on its own. */
    public boolean huntsMonsters() {
        return bravery >= 7;
    }

    /** Whether the companion steps in when a friend is attacked. */
    public boolean defendsFriends() {
        return bravery >= 4 || loyalty >= 7;
    }

    /** Whether the companion follows its owner around when it has nothing else to do. */
    public boolean followsOwnerWhenIdle() {
        return loyalty >= 6;
    }

    /** Movement speed multiplier: lazy characters amble, diligent ones hurry. */
    public double workPace() {
        return 0.85 + diligence * 0.03;
    }

    public String describe() {
        return "Bravery " + bravery + "/10, diligence " + diligence + "/10, generosity " + generosity
                + "/10, curiosity " + curiosity + "/10, loyalty " + loyalty + "/10, sociability " + sociability
                + "/10, perfectionism " + perfectionism + "/10.\n"
                + "Favorite activities: " + String.join(", ", favoriteActivities) + ".\n"
                + "Dislikes: " + (dislikes.isEmpty() ? "nothing in particular" : String.join(", ", dislikes)) + ".\n"
                + "Building style: " + buildStyle + " Preferred blocks: " + String.join(", ", buildPalette) + ".\n"
                + "How they act: " + behaviorSummary;
    }
}
