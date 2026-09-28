package dev.aicompanion;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Server-side settings, stored in config/ai-companion.json. */
public class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** AICord API key (dashboard settings). The characters' personalities come from here. */
    public String aicordApiKey = "";
    public String aicordBaseUrl = "https://aicordapp.com/api/v1";

    /** Claude API key. Claude turns each character's decisions into game actions. */
    public String claudeApiKey = "";
    public String claudeModel = "claude-opus-5";
    /** Leave empty for the normal Claude API. */
    public String claudeBaseUrl = "";
    /** low | medium | high. Lower is faster and cheaper; higher plans more carefully. */
    public String claudeEffort = "low";
    /** Max tool calls Claude may make for a single decision. */
    public int maxActionsPerDecision = 16;

    /** Seconds between "what do you feel like doing?" check-ins when a companion is idle. 0 disables. */
    public int idleThinkSeconds = 150;
    /** After finishing (or failing) a task, let the character react to the outcome in chat. */
    public boolean reactToResults = true;
    /** Players within this many blocks of a companion they recently spoke to keep the conversation going without naming it. */
    public int conversationRadius = 10;
    /** Companions only hear chat from players within this many blocks (0 = anywhere on the server). */
    public int hearingRadius = 0;

    /** Permission level needed for /companion spawn, dismiss and reinterview (0 = everyone, 2 = ops). */
    public int managePermissionLevel = 2;
    /**
     * How far a mean character may go against players. "never" (default): no deception or tricks that hurt anyone.
     * "pranks": harmless tricks and fibs. "mean": may lure mobs toward players, steal from their chests and sabotage
     * small things. Direct attacks still need allowPvp, and homes/builds are never broken at any level.
     */
    public String mischief = "never";
    /** Allow companions to attack players when their character decides to. */
    public boolean allowPvp = false;
    /** Blocks the companion searches for resources in. */
    public int searchRadius = 32;

    /** Optional per-character skins, keyed by character name (case-insensitive). Value: a Minecraft username or a direct PNG URL. */
    public Map<String, String> skins = new LinkedHashMap<>();
    /** Characters listed here use the slim (Alex) arm model. */
    public Map<String, Boolean> slimArms = new LinkedHashMap<>();

    private static ModConfig instance = new ModConfig();

    public static ModConfig get() {
        return instance;
    }

    public static Path dataDir() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("ai-companion");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            AiCompanionMod.LOGGER.error("Could not create {}", dir, e);
        }
        return dir;
    }

    public static synchronized ModConfig load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("ai-companion.json");
        ModConfig config = new ModConfig();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                ModConfig loaded = GSON.fromJson(reader, ModConfig.class);
                if (loaded != null) config = loaded;
            } catch (Exception e) {
                AiCompanionMod.LOGGER.error("Could not read {}, using defaults", file, e);
            }
        }
        if (config.skins == null) config.skins = new LinkedHashMap<>();
        if (config.slimArms == null) config.slimArms = new LinkedHashMap<>();
        // Write back so newly added options appear in the file.
        try (Writer writer = Files.newBufferedWriter(file)) {
            GSON.toJson(config, writer);
        } catch (IOException e) {
            AiCompanionMod.LOGGER.error("Could not write {}", file, e);
        }
        instance = config;
        return config;
    }

    public String mischiefLevel() {
        String m = mischief == null ? "never" : mischief.trim().toLowerCase();
        return m.equals("pranks") || m.equals("mean") ? m : "never";
    }

    public String skinFor(String characterName) {
        for (Map.Entry<String, String> e : skins.entrySet()) {
            if (e.getKey().equalsIgnoreCase(characterName)) return e.getValue();
        }
        return "";
    }

    public boolean slimFor(String characterName) {
        for (Map.Entry<String, Boolean> e : slimArms.entrySet()) {
            if (e.getKey().equalsIgnoreCase(characterName)) return Boolean.TRUE.equals(e.getValue());
        }
        return false;
    }
}
