package dev.aicompanion.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.aicompanion.AiCompanionMod;
import dev.aicompanion.CompanionManager;
import dev.aicompanion.ModConfig;
import dev.aicompanion.entity.CompanionEntity;
import dev.aicompanion.game.Perception;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.Nullable;

import java.io.Reader;
import java.io.Writer;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One character's mind in the game. Everything that happens to the companion becomes a stimulus; the AICord
 * character decides how to respond (what to say and what to do), and the action layer carries that decision out.
 *
 * Two threads per companion: the "mind" talks to AICord, the "body" runs action plans with Claude. A new decision
 * cancels the plan that is running, so the character can change its mind mid-task.
 */
public class CompanionBrain {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern INTENT = Pattern.compile("(?im)^[\\s*_>\\-]*intent[\\s*_]*:[\\s*_]*(.*)$");
    private static final Pattern NO_ACTION = Pattern.compile("(?i)^(none|nothing|no action|n/?a|stay silent|-)\\W*$");
    private static final Pattern CONTINUE = Pattern.compile("(?i)^(continue|keep (doing|going)|carry on|keep at it).{0,60}$");

    /** Why the character is being asked to respond. */
    public record Stimulus(String kind, String event, @Nullable String speaker, int depth) {}

    private final String characterId;
    private final String name;
    private volatile PersonaProfile profile;
    private volatile boolean hasProfile;
    private CompanionMemory memory;
    private WeakReference<CompanionEntity> entity = new WeakReference<>(null);

    private final ExecutorService mind;
    private final ExecutorService body;
    private final AtomicInteger planGeneration = new AtomicInteger();
    private final AtomicBoolean planRunning = new AtomicBoolean();
    private final AtomicInteger queuedStimuli = new AtomicInteger();
    private volatile boolean dirty;
    private volatile long lastActivity = System.currentTimeMillis();
    private final Map<String, Long> lastTalked = new ConcurrentHashMap<>();
    private final Map<String, Long> rateLimits = new ConcurrentHashMap<>();
    private volatile boolean debug;

    public CompanionBrain(String characterId, String name) {
        this.characterId = characterId;
        this.name = name;
        String thread = "companion-" + name.replaceAll("[^A-Za-z0-9]", "");
        this.mind = Executors.newSingleThreadExecutor(r -> daemon(r, thread + "-mind"));
        this.body = Executors.newSingleThreadExecutor(r -> daemon(r, thread + "-body"));
        load();
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    public String id() {
        return characterId;
    }

    public String name() {
        return name;
    }

    public PersonaProfile profile() {
        return profile;
    }

    public boolean hasProfile() {
        return hasProfile;
    }

    public CompanionMemory memory() {
        return memory;
    }

    @Nullable
    public CompanionEntity entity() {
        CompanionEntity e = entity.get();
        return e != null && !e.isRemoved() ? e : null;
    }

    public void attach(CompanionEntity e) {
        entity = new WeakReference<>(e);
    }

    public void detach(CompanionEntity e) {
        if (entity.get() == e) entity = new WeakReference<>(null);
    }

    public boolean isThinkingOrActing() {
        return planRunning.get() || queuedStimuli.get() > 0;
    }

    public long lastActivity() {
        return lastActivity;
    }

    public void toggleDebug() {
        debug = !debug;
    }

    // ------------------------------------------------------------------ stimuli from the game

    public void onChat(String speaker, String message, int depth) {
        lastTalked.put(speaker.toLowerCase(), System.currentTimeMillis());
        stimulate(new Stimulus("chat", speaker + " says to you: \"" + message + "\"", speaker, depth));
    }

    public boolean recentlyTalkedWith(String player, long withinMs) {
        Long t = lastTalked.get(player.toLowerCase());
        return t != null && System.currentTimeMillis() - t < withinMs;
    }

    public void onHitByPlayer(String player) {
        memory.adjustOpinion(player, -2);
        dirty = true;
        if (allow("hit:" + player, 8000)) {
            CompanionEntity e = entity();
            String health = e == null ? "" : " (your health: " + Math.round(e.getHealth()) + "/20)";
            stimulate(new Stimulus("hurt", player + " just hit you!" + health, player, 0));
        }
    }

    public void onGift(String player, String what) {
        if (allow("giftopinion:" + player, 30000)) memory.adjustOpinion(player, 1);
        dirty = true;
        memory.addEvent(player + " gave you " + what);
        if (allow("gift:" + player, 15000)) stimulate(new Stimulus("gift", player + " gave you " + what + ".", player, 0));
    }

    public void onDeath(String deathMessage) {
        planGeneration.incrementAndGet();
        memory.addEvent("You died: " + deathMessage + ". You dropped everything you carried.");
        dirty = true;
        CompanionManager.broadcast(Text.literal(deathMessage).formatted(Formatting.GRAY));
    }

    /** Something happened to the body worth telling the mind about (e.g. a follow ended). */
    public void onBodyEvent(String event, boolean react) {
        memory.addEvent(event);
        if (react) stimulate(new Stimulus("event", event, null, 0));
    }

    public void onPlayerApproach(String player) {
        if (allow("greet:" + player, 10 * 60 * 1000)) stimulate(new Stimulus("greet", player + " just came over to you.", player, 0));
    }

    public void onSpawned(String owner, boolean returning) {
        String event = returning
                ? owner + " brought you back into the world. Everything you carried before is gone."
                : "You just arrived in this Minecraft world, brought here by " + owner + ".";
        stimulate(new Stimulus("spawn", event, owner, 0));
    }

    public void onIdle() {
        CompanionEntity e = entity();
        String doing = e == null ? null : e.currentTaskDescription();
        stimulate(new Stimulus("idle", "Nobody needs anything from you right now" + (doing == null ? "" : " (you're " + doing + ")")
                + ". What do you feel like doing?", null, 0));
    }

    private boolean allow(String key, long intervalMs) {
        long now = System.currentTimeMillis();
        Long last = rateLimits.get(key);
        if (last != null && now - last < intervalMs) return false;
        rateLimits.put(key, now);
        return true;
    }

    // ------------------------------------------------------------------ thinking

    private void stimulate(Stimulus s) {
        if (s.kind().equals("idle") && queuedStimuli.get() > 0) return;
        if (queuedStimuli.get() >= 4) return; // don't pile up a backlog of stale events
        queuedStimuli.incrementAndGet();
        lastActivity = System.currentTimeMillis();
        mind.submit(() -> {
            try {
                think(s);
            } catch (Exception ex) {
                AiCompanionMod.LOGGER.error("{} failed to think", name, ex);
            } finally {
                queuedStimuli.decrementAndGet();
                lastActivity = System.currentTimeMillis();
            }
        });
    }

    private void think(Stimulus s) throws Exception {
        CompanionEntity e = entity();
        if (e == null) return;
        MinecraftServer server = e.getServer();
        if (server == null) return;
        String situation = server.submit(() -> Perception.describe(e, memory)).get(10, TimeUnit.SECONDS);

        StringBuilder prompt = new StringBuilder();
        prompt.append("[Minecraft] You are ").append(name).append(", living inside a Minecraft world as yourself. You have a body in the game: ")
                .append("you can walk around, mine, craft, build, fight, trade items and talk. This message comes from the game and describes what you perceive.\n\n")
                .append(situation);
        List<String> events = List.copyOf(memory.events);
        if (!events.isEmpty()) {
            prompt.append("Things that happened recently: ").append(String.join("; ", events.subList(Math.max(0, events.size() - 6), events.size()))).append(".\n");
        }
        prompt.append("\nWhat just happened: ").append(s.event()).append("\n\n")
                .append("Reply as yourself, fully in character. First write what you say out loud: 1 to 3 short sentences that appear in the game chat ")
                .append("(write nothing if you'd stay silent). Then, on the last line, write \"INTENT:\" followed by what you actually decide to do now, in your own words ")
                .append("(for example: chop some trees and bring Steve the wood / refuse and keep mining / demand 3 diamonds first / go explore that cave / keep doing what I'm doing / none). ")
                .append("Nobody can make you do anything; decide what you genuinely would.");

        List<AicordClient.ChatMessage> history = new java.util.ArrayList<>(memory.conversation);
        history.add(AicordClient.ChatMessage.user(prompt.toString()));
        String reply;
        try {
            reply = AicordClient.chat(characterId, history);
        } catch (AicordClient.AicordException ex) {
            reportProblem(ex.getMessage());
            return;
        }
        memory.addConversation(AicordClient.ChatMessage.user("[" + s.event() + "]"));
        memory.addConversation(AicordClient.ChatMessage.character(reply));
        dirty = true;

        String intent = null;
        String spoken = reply;
        Matcher m = INTENT.matcher(reply);
        while (m.find()) {
            intent = m.group(1).trim();
            spoken = reply.substring(0, m.start()).trim();
        }
        spoken = spoken.replaceAll("(?im)^[\\s*_]*(say(s)?|out loud|speech)[\\s*_]*:[\\s*_]*", "").trim();
        if (spoken.length() >= 2 && spoken.startsWith("\"") && spoken.endsWith("\"")) spoken = spoken.substring(1, spoken.length() - 1);
        debug("[" + s.kind() + "] intent: " + intent);

        if (!spoken.isBlank()) say(spoken, s.depth());

        if (intent != null && NO_ACTION.matcher(intent).matches()) return;
        if (intent != null && CONTINUE.matcher(intent).matches() && (planRunning.get() || e.currentTaskDescription() != null)) return;
        if (intent == null && !s.kind().equals("chat")) return;
        memory.addEvent("You decided: " + (intent == null ? spoken : intent));
        startPlan(s, spoken, intent, situation);
    }

    private void startPlan(Stimulus s, String spoken, @Nullable String intent, String situation) {
        int generation = planGeneration.incrementAndGet();
        body.submit(() -> {
            if (planGeneration.get() != generation) return;
            planRunning.set(true);
            try {
                ClaudeActionLayer.Outcome outcome = ClaudeActionLayer.carryOut(this, s.event(), spoken, intent, situation, () -> planGeneration.get() != generation);
                String summary = outcome.summary();
                memory.addEvent("Result: " + summary);
                dirty = true;
                debug("result: " + summary + " (" + outcome.actions() + " actions)");
                // Let the character react to what its body did (only if it actually did something, and not endlessly).
                if (planGeneration.get() == generation && ModConfig.get().reactToResults && s.depth() < 1 && outcome.actions() > 0) {
                    stimulate(new Stimulus("outcome", "Your body finished acting on what you decided (\"" + (intent == null ? spoken : intent)
                            + "\"). What happened: " + summary + " React briefly if you want to, and say what you do next (INTENT: none if you're done).", s.speaker(), s.depth() + 1));
                }
            } finally {
                planRunning.set(false);
                lastActivity = System.currentTimeMillis();
            }
        });
    }

    private void say(String text, int depth) {
        String clean = text.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (clean.length() > 400) clean = clean.substring(0, 397) + "...";
        String line = clean;
        CompanionManager.broadcast(Text.literal("<" + name + "> ").formatted(Formatting.AQUA).append(Text.literal(line).formatted(Formatting.WHITE)));
        CompanionManager.companionSpoke(this, line, depth);
    }

    /** Tells the owner (only) about a configuration or API problem. */
    public void reportProblem(String problem) {
        CompanionEntity e = entity();
        if (e == null || e.getServer() == null) {
            AiCompanionMod.LOGGER.warn("[{}] {}", name, problem);
            return;
        }
        e.getServer().execute(() -> {
            ServerPlayerEntity owner = e.getOwnerPlayer();
            Text msg = Text.literal("[AI Companion] " + name + ": " + problem).formatted(Formatting.RED);
            if (owner != null && allow("problem:" + problem, 60000)) owner.sendMessage(msg);
        });
        AiCompanionMod.LOGGER.warn("[{}] {}", name, problem);
    }

    public void debug(String message) {
        AiCompanionMod.LOGGER.info("[{}] {}", name, message);
        if (!debug) return;
        CompanionEntity e = entity();
        if (e != null && e.getServer() != null) {
            e.getServer().execute(() -> {
                ServerPlayerEntity owner = e.getOwnerPlayer();
                if (owner != null) owner.sendMessage(Text.literal("[" + name + " debug] " + message).formatted(Formatting.DARK_GRAY));
            });
        }
    }

    // ------------------------------------------------------------------ personality interview

    /** Asks the character to describe itself and turns the answer into a behavior profile. */
    public void interview(@Nullable Runnable afterwards) {
        queuedStimuli.incrementAndGet();
        mind.submit(() -> {
            try {
                String question = "[Minecraft setup - out of character question] You're about to be given a body in a Minecraft world, where you'll live "
                        + "and act on your own. Describe yourself honestly, as your character, so the game can make your body behave like you:\n"
                        + "1. How brave or cautious are you in a fight?\n2. How hardworking or lazy?\n3. How generous with your things?\n"
                        + "4. How curious and adventurous?\n5. How loyal to the person who brought you?\n6. How talkative?\n7. How neat or perfectionist?\n"
                        + "8. What would you most like to do in Minecraft?\n9. What would you hate doing?\n10. Describe your dream house: style, size and materials.\n"
                        + "Answer in character, briefly.";
                String answer = AicordClient.chat(characterId, List.of(AicordClient.ChatMessage.user(question)));
                PersonaProfile extracted = ClaudeActionLayer.extractProfile(name, answer);
                profile = extracted;
                hasProfile = true;
                Files.writeString(profilePath(), GSON.toJson(extracted));
                AiCompanionMod.LOGGER.info("Profile for {}: {}", name, extracted.describe());
                debug("profile: " + extracted.describe());
            } catch (Exception ex) {
                reportProblem("Couldn't build a personality profile (" + ex.getMessage() + "); using neutral traits for now.");
            } finally {
                queuedStimuli.decrementAndGet();
                if (afterwards != null) afterwards.run();
            }
        });
    }

    // ------------------------------------------------------------------ persistence

    private Path profilePath() throws java.io.IOException {
        Path dir = ModConfig.dataDir().resolve("profiles");
        Files.createDirectories(dir);
        return dir.resolve(characterId + ".json");
    }

    private Path memoryPath() throws java.io.IOException {
        Path dir = ModConfig.dataDir().resolve("memory");
        Files.createDirectories(dir);
        return dir.resolve(characterId + ".json");
    }

    private void load() {
        profile = PersonaProfile.neutral();
        try {
            Path p = profilePath();
            if (Files.exists(p)) {
                try (Reader r = Files.newBufferedReader(p)) {
                    PersonaProfile loaded = GSON.fromJson(r, PersonaProfile.class);
                    if (loaded != null) {
                        profile = loaded.clamped();
                        hasProfile = true;
                    }
                }
            }
        } catch (Exception e) {
            AiCompanionMod.LOGGER.error("Could not load profile for {}", name, e);
        }
        memory = new CompanionMemory();
        try {
            Path p = memoryPath();
            if (Files.exists(p)) {
                try (Reader r = Files.newBufferedReader(p)) {
                    CompanionMemory loaded = GSON.fromJson(r, CompanionMemory.class);
                    if (loaded != null) memory = loaded;
                }
            }
        } catch (Exception e) {
            AiCompanionMod.LOGGER.error("Could not load memory for {}", name, e);
        }
        // Thread-safe collections: memory is touched by the server, mind and body threads.
        memory.opinions = new ConcurrentHashMap<>(memory.opinions == null ? Map.of() : memory.opinions);
        memory.places = new ConcurrentHashMap<>(memory.places == null ? Map.of() : memory.places);
        memory.events = new CopyOnWriteArrayList<>(memory.events == null ? List.of() : memory.events);
        memory.conversation = new CopyOnWriteArrayList<>(memory.conversation == null ? List.of() : memory.conversation);
    }

    public void saveLater() {
        dirty = true;
    }

    public void saveIfDirty() {
        if (!dirty) return;
        dirty = false;
        try (Writer w = Files.newBufferedWriter(memoryPath())) {
            GSON.toJson(memory, w);
        } catch (Exception e) {
            AiCompanionMod.LOGGER.error("Could not save memory for {}", name, e);
        }
    }

    public void shutdown() {
        planGeneration.incrementAndGet();
        saveIfDirty();
        mind.shutdownNow();
        body.shutdownNow();
    }
}
