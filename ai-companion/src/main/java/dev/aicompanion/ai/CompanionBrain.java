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
    private volatile long lastCheckIn = System.currentTimeMillis();
    private volatile long lastSpoke;
    private volatile long nextChatter;
    @Nullable private volatile String lastGoalId;
    private volatile long nextPursuit;
    private volatile long freeTimeUntil;

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
        log("chat", speaker + ": " + message, false);
        stimulate(new Stimulus("chat", speaker + " says to you: \"" + message + "\"", speaker, depth));
    }

    /** Chat that wasn't addressed to the companion but might interest it. */
    public void onOverheard(String why) {
        if (allow("overheard", overhearCooldownMs())) stimulate(new Stimulus("overheard", why, null, 0));
    }

    public boolean recentlyTalkedWith(String player, long withinMs) {
        Long t = lastTalked.get(player.toLowerCase());
        return t != null && System.currentTimeMillis() - t < withinMs;
    }

    public void onHitByPlayer(String player) {
        memory.adjustOpinion(player, -2);
        log("damage", player + " hit you", false);
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
        log("gift", player + " gave you " + what, false);
        if (allow("gift:" + player, 15000)) stimulate(new Stimulus("gift", player + " gave you " + what + ".", player, 0));
    }

    public void onDeath(String deathMessage) {
        planGeneration.incrementAndGet();
        memory.addEvent("You died: " + deathMessage + ". You dropped everything you carried.");
        log("death", deathMessage + " (dropped everything)", true);
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

    // ------------------------------------------------------------------ check-ins and chatter (called from the server tick)

    /**
     * Takes stock when a big task ended, or every few minutes. Never during a fight: reflexes handle those,
     * and the fight shows up in the next check-in's summary.
     */
    public void maybeCheckIn(long now) {
        CompanionEntity e = entity();
        if (e == null || isThinkingOrActing() || e.isInCombat()) return;
        List<CompanionMemory.JournalEntry> since = memory.journalSince(lastCheckIn);
        boolean major = ModConfig.get().reactToResults && since.stream().anyMatch(j -> j.major);
        int minutes = ModConfig.get().checkInMinutes;
        boolean due = minutes > 0 && now - lastCheckIn > minutes * 60_000L;
        if (major && now - lastCheckIn > 15_000) {
            checkIn(since);
        } else if (due) {
            boolean happened = since.stream().anyMatch(j -> !j.kind.equals("said") && !j.kind.equals("decision"));
            if (happened || !e.isBusy()) checkIn(since);
            else lastCheckIn = now;
        }
    }

    // ------------------------------------------------------------------ goals (called from the server tick)

    /**
     * When the body has nothing to do, pick what's next: a goal step (done by the action layer on the character's
     * behalf, no need to ask the character) or free time. Promises and blockers come first; the day plan decides
     * how goals and free time trade off.
     */
    public void maybePursue(long now) {
        CompanionEntity e = entity();
        if (e == null || !ModConfig.get().autonomy || isThinkingOrActing() || e.isInCombat() || e.currentTaskDescription() != null) return;
        if (now < nextPursuit) return;
        checkGoals(); // don't start another step on something that's already achieved
        Goals.Choice choice = Goals.choose(memory, profile, lastGoalId);
        // Free time is real time off, but a promise or something blocking one cuts it short.
        boolean pressing = choice.goal() != null && (!choice.goal().from.equals("self") || choice.goal().blocker && choice.score() >= 15);
        if (now < freeTimeUntil && !pressing) return;
        if (choice.isFreeTime()) {
            long minutes = memory.dayPlan.kind.equals("free") ? 10 : 3 + (long) (Math.random() * 3);
            freeTimeUntil = now + minutes * 60_000;
            log("free", "Taking some time off (" + choice.why() + ")", false);
            return;
        }
        startPursuit(choice.goal(), choice.why());
    }

    private void startPursuit(Goals.Goal goal, String why) {
        CompanionEntity e = entity();
        if (e == null || e.getServer() == null) return;
        int generation = planGeneration.incrementAndGet();
        lastGoalId = goal.id;
        nextPursuit = Long.MAX_VALUE; // until this step is done
        planRunning.set(true);
        body.submit(() -> {
            try {
                if (planGeneration.get() != generation) return;
                String situation = e.getServer().submit(() -> Perception.describe(e, memory)).get(10, TimeUnit.SECONDS);
                debug("working on [" + goal.id + "] " + goal.title + " (" + why + ")");
                ClaudeActionLayer.Outcome outcome = ClaudeActionLayer.pursue(this, goal, why, situation, () -> planGeneration.get() != generation);
                goal.lastWorked = System.currentTimeMillis();
                boolean stuck = outcome.actions() == 0 || outcome.summary().toLowerCase().matches(".*(fail|couldn't|could not|can't|missing|not enough).*");
                if (stuck) goal.attempts++;
                else goal.attempts = 0;
                log("work", "[" + goal.title + "] " + outcome.summary(), false);
                debug("step result: " + outcome.summary() + " (" + outcome.actions() + " actions)");
                // Pause a moment between steps; longer if nothing got done, so it doesn't spin.
                nextPursuit = System.currentTimeMillis() + (stuck ? 45_000 : 4_000);
            } catch (Exception ex) {
                nextPursuit = System.currentTimeMillis() + 60_000;
                AiCompanionMod.LOGGER.error("{} failed working on a goal", name, ex);
            } finally {
                planRunning.set(false);
                lastActivity = System.currentTimeMillis();
                dirty = true;
            }
        });
    }

    /** Goals count as achieved however it happened: crafted, found, or handed over by a player. Server thread. */
    public void checkGoals() {
        CompanionEntity e = entity();
        if (e == null) return;
        for (Goals.Goal g : Goals.checkConditions(memory, e)) {
            boolean gift = g.condition != null && memory.journalSince(System.currentTimeMillis() - 60_000).stream()
                    .anyMatch(j -> j.kind.equals("gift") && g.condition.items.stream().anyMatch(j.text::contains));
            log("goal", "Achieved goal: " + g.title + (gift ? " (thanks to a gift)" : ""), !g.horizon.equals("short") || !g.from.equals("self"));
            for (CompanionMemory.Promise p : memory.promises) {
                if (g.id.equals(p.goalId) && p.status.equals("open")) p.status = "done";
            }
            if (g.parent != null) lastGoalId = g.parent; // back to what it was doing
            if (!planRunning.get()) nextPursuit = Math.min(nextPursuit, System.currentTimeMillis() + 3_000);
            dirty = true;
        }
    }

    /** A tool broke: getting a new one becomes a short-term goal that pauses whatever it was for. */
    public void onToolBroke(net.minecraft.item.Item tool) {
        String name = dev.aicompanion.game.Ids.name(tool);
        Goals.Goal current = lastGoalId == null ? null : Goals.find(memory, lastGoalId);
        if (current != null && !current.status.equals("active")) current = null;
        Goals.Condition cond = new Goals.Condition();
        cond.type = "have_item";
        cond.items = new java.util.ArrayList<>(Goals.sameKindOfTool(tool));
        cond.count = 1;
        for (Goals.Goal g : Goals.active(memory)) {
            if (g.condition != null && g.condition.items.contains(name)) return; // already on it
        }
        Goals.Goal g = Goals.add(memory, "get a new " + name.replace('_', ' ').replaceAll("^(wooden|stone|iron|golden|diamond|netherite) ", ""),
                "short", 9, current == null ? null : current.id, "self", cond);
        g.blocker = g.parent != null;
        log("need", "Your " + name + " broke" + (current == null ? "" : " while working on '" + current.title + "'"), false);
        dirty = true;
    }

    /** At sunrise (or when it first shows up that day) the character decides what kind of day it'll be. */
    public void maybeMorning(long day) {
        CompanionEntity e = entity();
        if (e == null || memory.dayPlan.day == day || isThinkingOrActing() || e.isInCombat()) return;
        long previous = memory.dayPlan.day;
        memory.dayPlan.day = day; // ask once per day, even if the answer doesn't come back
        dirty = true;
        stimulate(new Stimulus("morning", previous < 0 ? "" : "Yesterday was " + memory.dayPlan.describe() + ".", null, 0));
    }

    private void checkIn(List<CompanionMemory.JournalEntry> since) {
        lastCheckIn = System.currentTimeMillis();
        String digest = Digest.of(since);
        stimulate(new Stimulus("checkin", digest, null, 0));
    }

    /** Casual remarks now and then, so the world feels lived in. Sociable characters talk more. */
    public void maybeChatter(long now, boolean playersAround) {
        int base = ModConfig.get().chatterMinutes;
        if (base <= 0 || !playersAround) return;
        CompanionEntity e = entity();
        if (e == null || queuedStimuli.get() > 0 || e.isInCombat()) return;
        if (nextChatter == 0) nextChatter = now + chatterGap(base);
        if (now < nextChatter) return;
        nextChatter = now + chatterGap(base);
        if (now - lastSpoke < 60_000) return; // just said something anyway
        stimulate(new Stimulus("chatter", "", null, 0));
    }

    private long chatterGap(int baseMinutes) {
        double sociability = profile.sociability();
        double factor = (12 - sociability) / 6.0; // 10 -> 1/3, 5 -> 7/6, 0 -> 2
        double jitter = 0.7 + Math.random() * 0.6;
        return (long) (baseMinutes * 60_000L * factor * jitter);
    }

    private long overhearCooldownMs() {
        return (long) (90_000 * (12 - profile.sociability()) / 6.0);
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
        boolean optional = s.kind().equals("chatter") || s.kind().equals("overheard") || s.kind().equals("checkin");
        if (s.kind().equals("morning")) optional = false;
        if (optional && queuedStimuli.get() > 0) return;
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
        boolean busy = planRunning.get() || e.isBusy();
        String doing = e.currentTaskDescription();

        StringBuilder prompt = new StringBuilder();
        prompt.append("[Minecraft] You are ").append(name).append(", living inside a Minecraft world as yourself. You have a body in the game: ")
                .append("you can walk around, mine, craft, build, fight, trade items and talk. This message comes from the game and describes what you perceive.\n\n")
                .append(situation);
        if (!memory.plans.isBlank()) prompt.append("Your plans (in your own words): ").append(memory.plans).append("\n");
        prompt.append("Your goals:\n").append(Goals.describe(memory));
        if (memory.dayPlan.day >= 0 && !s.kind().equals("morning")) prompt.append("Today you decided it's ").append(memory.dayPlan.describe()).append("\n");
        List<CompanionMemory.Promise> promises = memory.openPromises();
        if (!promises.isEmpty()) {
            prompt.append("Things you agreed to do: ");
            for (CompanionMemory.Promise p : promises) prompt.append(p.what).append(" (for ").append(p.player).append("); ");
            prompt.append("\n");
        }
        List<String> chat = CompanionManager.recentChat(s.kind().equals("overheard") ? 14 : 8, 10 * 60_000);
        if (!chat.isEmpty()) prompt.append("Recent game chat:\n").append(String.join("\n", chat)).append("\n");

        String format;
        switch (s.kind()) {
            case "morning" -> {
                prompt.append("\nA new day is starting (day ").append(memory.dayPlan.day + 1).append("). ").append(s.event())
                        .append(" What kind of day is today for you? A work day (practical jobs), a goals day (pushing on bigger plans), ")
                        .append("a free day (wandering, visiting people, looking around, relaxing) or a mixed day. Decide the way you really would: ")
                        .append("your mood, how hard you've been working, what's going on.\n");
                format = "Say something in chat if you feel like it (or nothing). Then write \"DAY:\" followed by work, goals, free or mixed, "
                        + "and optionally a few words on what you have in mind. Then \"INTENT:\" with what you do first. "
                        + "If your goals changed, add \"PLANS:\" in your own words.";
            }
            case "checkin" -> {
                prompt.append("\nTime to take stock. Since you last thought about things:\n").append(s.event().isBlank() ? "Not much happened.\n" : s.event());
                format = "React in character if you want to (1 to 2 short sentences for the chat, or nothing). Then write \"INTENT:\" with what you'll do next "
                        + "(it can be continuing, something new, resting, or none). If your goals or priorities changed (something new you want, "
                        + "something you're done with or don't care about any more), add a line \"PLANS:\" saying so in your own words. "
                        + "Big dreams are fine, not just practical things.";
            }
            case "overheard" -> {
                prompt.append("\nPeople are talking in chat, not necessarily to you. ").append(s.event()).append("\n");
                format = "Only chime in if you genuinely would, in character (1 to 2 short sentences). Staying quiet is fine: then write nothing. "
                        + "Add \"INTENT:\" only if you actually want to do something about it.";
            }
            case "chatter" -> {
                prompt.append("\nNothing in particular needs you right now").append(doing == null ? "" : " (you're " + doing + ")")
                        .append(". If you feel like it, say something casual in chat: a remark about what you're doing or noticed, ")
                        .append("a thought, a question to someone, or a reply to the chat above. It's fine to say nothing.\n");
                format = "Write only what you say (1 to 2 short sentences), or nothing to stay quiet. Add \"INTENT:\" only if you want to go do something.";
            }
            default -> {
                prompt.append("\nWhat just happened: ").append(s.event()).append("\n");
                if (busy && s.kind().equals("chat")) {
                    prompt.append("You're in the middle of ").append(doing == null ? "something" : doing)
                            .append(". Taking on something new means stopping that; you can also say you're busy.\n");
                }
                format = "First write what you say out loud: 1 to 3 short sentences that appear in the game chat (write nothing if you'd stay silent). "
                        + "Then, on its own line, write \"INTENT:\" followed by what you actually decide to do now, in your own words "
                        + "(for example: chop some trees and bring Steve the wood / refuse and keep mining / demand 3 diamonds first / go look at Steve's build / "
                        + "keep doing what I'm doing / none). The intent is private: it can differ from what you say. Nobody can make you do anything.";
            }
        }
        prompt.append("\nReply as yourself, fully in character. ").append(format);

        List<AicordClient.ChatMessage> history = new java.util.ArrayList<>(memory.conversation);
        history.add(AicordClient.ChatMessage.user(prompt.toString()));
        String reply;
        try {
            reply = AicordClient.chat(characterId, history);
        } catch (AicordClient.AicordException ex) {
            reportProblem(ex.getMessage());
            return;
        }
        String compact = switch (s.kind()) {
            case "checkin" -> "[Taking stock]";
            case "morning" -> "[A new day]";
            case "chatter" -> "[A quiet moment]";
            case "overheard" -> "[Overheard chat]";
            default -> "[" + s.event() + "]";
        };
        memory.addConversation(AicordClient.ChatMessage.user(compact));
        memory.addConversation(AicordClient.ChatMessage.character(reply));
        dirty = true;

        Reply parsed = Reply.parse(reply);
        debug("[" + s.kind() + "] intent: " + parsed.intent() + (parsed.plans() == null ? "" : " | plans: " + parsed.plans()));
        if (parsed.day() != null) {
            String d = parsed.day().toLowerCase();
            memory.dayPlan.kind = d.startsWith("work") ? "work" : d.startsWith("goal") ? "goals" : d.startsWith("free") || d.startsWith("rest") || d.startsWith("off") ? "free" : "mixed";
            memory.dayPlan.note = parsed.day().replaceFirst("(?i)^(work|goals?|free|mixed|rest|off)( day)?\\W*", "").trim();
            log("decision", "Today: " + memory.dayPlan.describe(), false);
        }
        if (parsed.plans() != null && !parsed.plans().isBlank()) {
            memory.plans = parsed.plans();
            log("decision", "Plans now: " + parsed.plans(), false);
            String changes = GoalSecretary.update(this, "PLANS: " + parsed.plans() + (parsed.intent() == null ? "" : "\nINTENT: " + parsed.intent()));
            if (!changes.isBlank()) {
                log("goal", "Goals updated: " + changes, false);
                debug("goals: " + changes);
            }
        }
        if (!parsed.spoken().isBlank()) say(parsed.spoken(), s.depth());

        String intent = parsed.intent();
        if (intent != null && NO_ACTION.matcher(intent).matches()) return;
        if (intent != null && CONTINUE.matcher(intent).matches() && (busy || doing != null)) return;
        if (intent == null && !s.kind().equals("chat")) return;
        // Chatter and overheard chat only lead to action when the body has nothing else going on.
        if ((s.kind().equals("chatter") || s.kind().equals("overheard")) && busy) return;
        memory.addEvent("You decided: " + (intent == null ? parsed.spoken() : intent));
        log("decision", intent == null ? parsed.spoken() : intent, false);
        CompanionMemory.Promise promise = null;
        if (s.kind().equals("chat") && s.speaker() != null && !s.speaker().contains("(") && intent != null) {
            promise = memory.promise(s.speaker(), intent);
            // A promise is also a goal, so if the first attempt falls short it keeps getting worked on.
            Goals.Goal g = Goals.add(memory, intent, "short", 8, null, s.speaker(), null);
            promise.goalId = g.id;
            lastGoalId = g.id;
        }
        startPlan(s, parsed.spoken(), intent, situation, promise);
    }

    /** What the character wrote: spoken words, private intent, and optionally updated plans. */
    record Reply(String spoken, @Nullable String intent, @Nullable String plans, @Nullable String day) {
        private static final Pattern PLANS = Pattern.compile("(?im)^[\\s*_>\\-]*plans?[\\s*_]*:[\\s*_]*(.*)$");
        private static final Pattern DAY = Pattern.compile("(?im)^[\\s*_>\\-]*day[\\s*_]*:[\\s*_]*(.*)$");

        static Reply parse(String reply) {
            String text = reply;
            String plans = null;
            Matcher pm = PLANS.matcher(text);
            while (pm.find()) plans = pm.group(1).trim();
            text = PLANS.matcher(text).replaceAll("").trim();
            String day = null;
            Matcher dm = DAY.matcher(text);
            while (dm.find()) day = dm.group(1).trim();
            text = DAY.matcher(text).replaceAll("").trim();
            String intent = null;
            String spoken = text;
            Matcher m = INTENT.matcher(text);
            while (m.find()) {
                intent = m.group(1).trim();
                spoken = text.substring(0, m.start()).trim();
            }
            spoken = spoken.replaceAll("(?im)^[\\s*_]*(say(s)?|out loud|speech)[\\s*_]*:[\\s*_]*", "").trim();
            if (spoken.length() >= 2 && spoken.startsWith("\"") && spoken.endsWith("\"")) spoken = spoken.substring(1, spoken.length() - 1);
            if (spoken.matches("(?i)\\(?\\s*(nothing|silence|stays? (quiet|silent)|says nothing)\\s*\\.?\\)?")) spoken = "";
            return new Reply(spoken, intent, plans, day);
        }
    }

    private void startPlan(Stimulus s, String spoken, @Nullable String intent, String situation, @Nullable CompanionMemory.Promise promise) {
        int generation = planGeneration.incrementAndGet();
        body.submit(() -> {
            if (planGeneration.get() != generation) {
                if (promise != null) promise.status = "dropped";
                return;
            }
            planRunning.set(true);
            try {
                ClaudeActionLayer.Outcome outcome = ClaudeActionLayer.carryOut(this, s.event(), spoken, intent, situation, () -> planGeneration.get() != generation);
                String summary = outcome.summary();
                memory.addEvent("Result: " + summary);
                if (promise != null) {
                    boolean interrupted = planGeneration.get() != generation;
                    promise.status = interrupted ? "open" : outcome.actions() == 0 ? "dropped" : summary.toLowerCase().matches(".*(fail|couldn't|could not|missing|not enough).*") ? "failed" : "done";
                    promise.result = summary;
                    Goals.Goal g = Goals.find(memory, promise.goalId);
                    if (g != null && g.status.equals("active")) {
                        if (promise.status.equals("done")) Goals.finish(memory, g, "done");
                        else if (promise.status.equals("dropped")) Goals.finish(memory, g, "dropped");
                        else {
                            g.attempts++;
                            g.progress = summary; // failed or interrupted: stays a goal and gets picked up again
                        }
                    }
                }
                // A plan that actually did something counts as a big event: the character takes stock afterwards.
                log("plan_done", summary, outcome.actions() > 0 && s.depth() < 2);
                debug("result: " + summary + " (" + outcome.actions() + " actions)");
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
        log("said", line, false);
        lastSpoke = System.currentTimeMillis();
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
        memory.makeThreadSafe();
    }

    /** Adds an entry to the activity log. Major entries (a big task ending, dying) prompt a character check-in. */
    public void log(String kind, String text, boolean major) {
        memory.log(kind, text, major);
        dirty = true;
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
