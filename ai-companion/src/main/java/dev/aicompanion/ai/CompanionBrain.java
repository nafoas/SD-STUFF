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
    private volatile long respawnAt;

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
        String help = helpFromGift(player, what);
        if (allow("gift:" + player, 15000) || !help.isEmpty()) stimulate(new Stimulus("gift", player + " gave you " + what + "." + help, player, 0));
    }

    public void onDeath(String deathMessage, String dimension, net.minecraft.util.math.BlockPos pos, List<String> lost) {
        planGeneration.incrementAndGet();
        CompanionMemory.Death d = new CompanionMemory.Death();
        d.dimension = dimension;
        d.x = pos.getX();
        d.y = pos.getY();
        d.z = pos.getZ();
        d.time = System.currentTimeMillis();
        d.cause = deathMessage;
        d.items = new java.util.ArrayList<>(lost);
        memory.lastDeath = d;
        memory.addEvent("You died: " + deathMessage + ". You dropped everything you carried.");
        log("death", deathMessage + " at " + pos.toShortString() + (lost.isEmpty() ? "" : ", dropped " + String.join(", ", lost)), true);
        CompanionManager.broadcast(Text.literal(deathMessage).formatted(Formatting.GRAY));
        int delay = ModConfig.get().autoRespawnSeconds;
        respawnAt = delay > 0 ? System.currentTimeMillis() + delay * 1000L : 0;
    }

    public long respawnAt() {
        return respawnAt;
    }

    /** Back in the world after dying: getting its things back becomes a goal (how urgent depends on how brave it is). */
    public void onRespawned() {
        respawnAt = 0;
        CompanionMemory.Death d = memory.lastDeath;
        if (d == null || d.items.isEmpty()) return;
        long left = 5 * 60_000 - (System.currentTimeMillis() - d.time);
        if (left <= 0) return;
        Goals.Condition cond = new Goals.Condition();
        cond.type = "recover";
        cond.target = d.dimension + "|" + d.x + "|" + d.y + "|" + d.z + "|" + d.time;
        int importance = profile.bravery() >= 4 ? 9 : 5;
        Goals.Goal g = Goals.add(memory, "get my things back from where I died (" + d.x + ", " + d.y + ", " + d.z + ") before they vanish", "short",
                importance, null, "self", cond);
        g.progress = "Dropped " + String.join(", ", d.items) + ". Items vanish about 5 minutes after dying (" + (left / 60_000 + 1) + " min left).";
        lastGoalId = g.id;
        nextPursuit = 0;
        freeTimeUntil = 0;
        log("need", "Back after dying; your things are still at " + d.x + ", " + d.y + ", " + d.z + " (" + d.cause + ")", false);
    }

    /** Something happened to the body worth telling the mind about (e.g. a follow ended). */
    public void onBodyEvent(String event, boolean react) {
        memory.addEvent(event);
        if (react) stimulate(new Stimulus("event", event, null, 0));
    }

    public void onPlayerApproach(String player) {
        // It walked up to them itself (a visit): that's its greeting, not them coming over.
        Long visited = memory.lastVisited.get(player.toLowerCase());
        if (visited != null && System.currentTimeMillis() - visited < 2 * 60_000) return;
        CompanionEntity e = entity();
        if (e != null && e.currentTask() instanceof dev.aicompanion.game.tasks.LeisureTask lt && lt.mode() == dev.aicompanion.game.tasks.LeisureTask.Mode.CHECK_ON) return;
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
        if (e == null || !ModConfig.get().autonomy || isThinkingOrActing() || e.isInCombat()) return;
        boolean onBreak = e.currentTask() instanceof dev.aicompanion.game.tasks.LeisureTask;
        if (e.currentTaskDescription() != null && !onBreak) return;
        if (now < nextPursuit) return;
        maybeAskForHelp(now);
        checkGoals(); // don't start another step on something that's already achieved
        Goals.Choice choice = Goals.choose(memory, profile, lastGoalId);
        // Free time is real time off, but a promise or something blocking one cuts it short.
        boolean pressing = choice.goal() != null && (!choice.goal().from.equals("self") || choice.goal().blocker && choice.score() >= 15
                || choice.goal().condition != null && "recover".equals(choice.goal().condition.type)); // things vanish if it waits
        if (now < freeTimeUntil && !pressing) {
            if (!onBreak) startLeisure(e); // one outing ended; the break goes on
            return;
        }
        if (onBreak && !pressing && now < freeTimeUntil + 60_000) return; // let the current outing wind down
        if (choice.isFreeTime()) {
            // Free days are mostly free: long stretches of time off. Otherwise a short break.
            long minutes = memory.dayPlan.kind.equals("free") ? 20 + (long) (Math.random() * 20) : 4 + (long) (Math.random() * 4);
            freeTimeUntil = now + minutes * 60_000;
            log("free", "Taking some time off (" + choice.why() + ")", false);
            if (!onBreak) startLeisure(e);
            return;
        }
        if (onBreak) e.cancelTask("back to work");
        freeTimeUntil = 0;
        startPursuit(choice.goal(), choice.why());
    }

    /** Starts the next thing to do on a break: a stroll, a visit, checking on someone, exploring, resting. */
    private void startLeisure(CompanionEntity e) {
        if (!allow("leisure", 10_000)) return;
        Leisure.Pick pick = Leisure.choose(this, e);
        if (pick == null) return;
        debug("free time: " + pick.why());
        java.util.concurrent.CompletableFuture<String> done = new java.util.concurrent.CompletableFuture<>();
        dev.aicompanion.game.tasks.LeisureTask task = pick.task();
        done.thenAccept(result -> {
            if (!result.startsWith("Failed")) return;
            // Couldn't get there: don't keep trying straight away.
            long now = System.currentTimeMillis();
            if (task.mode() == dev.aicompanion.game.tasks.LeisureTask.Mode.CHECK_ON) memory.lastVisited.put(task.label().toLowerCase(), now - 5 * 60_000);
            if (task.mode() == dev.aicompanion.game.tasks.LeisureTask.Mode.VISIT) {
                CompanionMemory.Location l = memory.places.get(task.label());
                if (l != null) l.visited = now - 23 * 60 * 60_000L;
            }
        });
        e.startTask(task, done);
    }

    /** Arrived somewhere on a free-time outing (a player it came to see, or a place). The character may say something. */
    public void onLeisureArrived(String what, @Nullable ServerPlayerEntity player, net.minecraft.util.math.BlockPos pos) {
        CompanionEntity e = entity();
        if (e == null) return;
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        if (player != null) {
            String who = player.getName().getString();
            memory.lastVisited.put(who.toLowerCase(), now);
            sb.append("On your free time you went over to see how ").append(who).append(" is doing. ");
            sb.append(who).append(" has ").append(Math.round(player.getHealth())).append("/20 health and ").append(player.getHungerManager().getFoodLevel()).append("/20 food");
            if (!player.getMainHandStack().isEmpty()) sb.append(", and is holding ").append(dev.aicompanion.game.Ids.name(player.getMainHandStack().getItem()));
            sb.append(". ");
            String build = dev.aicompanion.world.BuildAwareness.describe(e.serverWorld(), player.getBlockPos());
            if (!build.startsWith("There's no build")) sb.append("Around them: ").append(build).append(" ");
            if (!e.getWorld().isDay() && e.getWorld().isSkyVisible(player.getBlockPos())) sb.append("It's night and they're out in the open. ");
            log("free", "Went to see " + who, false);
        } else {
            for (var loc : memory.places.entrySet()) {
                if (Math.abs(loc.getValue().x - pos.getX()) < 12 && Math.abs(loc.getValue().z - pos.getZ()) < 12) loc.getValue().visited = now;
            }
            sb.append("On your free time you went to ").append(what).append(". ");
            String build = dev.aicompanion.world.BuildAwareness.describe(e.serverWorld(), pos);
            sb.append(build.startsWith("There's no build") ? "It's open country, nothing built here." : "You see: " + build);
            log("free", "Went to see " + what, false);
        }
        dirty = true;
        // Only worth saying something if someone's around to hear it, and not too often.
        boolean audience = player != null || !e.getWorld().getEntitiesByClass(ServerPlayerEntity.class, e.getBoundingBox().expand(24), pl -> true).isEmpty();
        if (audience && allow("visit", player != null ? 5 * 60_000 : 8 * 60_000)) stimulate(new Stimulus("visit", sb.toString().trim(), player == null ? null : player.getName().getString(), 0));
        else memory.addEvent(sb.toString().trim());
    }

    // ------------------------------------------------------------------ asking for help, and noticing help

    /**
     * A goal the body has genuinely got stuck on, twice or more, with no obvious way forward: the character is told,
     * and decides whether to ask someone (in its own words) or try something else. Rare by design: at most one
     * question every 20 minutes, never twice for the same goal within an hour, and only with players around.
     */
    private void maybeAskForHelp(long now) {
        CompanionEntity e = entity();
        if (e == null || e.getServer() == null || e.getServer().getPlayerManager().getCurrentPlayerCount() == 0) return;
        for (Goals.Goal g : Goals.active(memory)) {
            if (g.stuck.isBlank() || g.attempts < 2 || now - g.stuckSince > 30 * 60_000) continue;
            if (g.helpAsked > 0 && now - g.helpAsked < 60 * 60_000) continue;
            if (!allow("askhelp", 20 * 60_000)) return;
            g.helpAsked = now; // asked once, whatever the character decides
            dirty = true;
            String event = "You've been trying to \"" + Goals.chain(memory, g) + "\" and you're stuck: " + g.stuck + ".";
            stimulate(new Stimulus("stuck", event, g.id, 0));
            return;
        }
    }

    /** Someone gave it things: if that's what it asked for (or finished a goal), that's help, and it's noticed. */
    private String helpFromGift(String player, String what) {
        String lower = what.toLowerCase().replace(' ', '_');
        for (CompanionMemory.HelpRequest h : memory.openHelpRequests()) {
            String need = h.need.toLowerCase().replace(' ', '_');
            boolean matches = false;
            for (String word : lower.split("[^a-z_]+")) if (word.length() > 2 && need.contains(word.replaceAll("^_+|_+$", ""))) matches = true;
            if (!matches) continue;
            h.status = "helped";
            h.helper = player;
            Goals.Goal g = Goals.find(memory, h.goalId);
            if (g != null) {
                // Help arrived: back to it straight away.
                g.helpAsked = 0;
                g.stuck = "";
                g.attempts = 0;
                nextPursuit = 0;
            }
            memory.thankFor(player, "gave " + what);
            log("help", player + " helped: gave you " + what + ", which you'd asked for (" + h.goal + ")", true);
            return " That's what you asked for help with (" + h.goal + ").";
        }
        return "";
    }

    /** A player built part of something it's building (or added to its building). Tallied, then noted once. */
    private final Map<String, int[]> builtByPlayer = new ConcurrentHashMap<>();
    private final Map<String, int[]> brokenByPlayer = new ConcurrentHashMap<>();

    public void onPlayerChangedBuilding(String player, String building, boolean placed, boolean inProgress) {
        if (building.toLowerCase().startsWith(name.toLowerCase() + "'s ")) building = building.substring(name.length() + 3); // "your cottage", not "your Ada's cottage"
        String key = player + "|" + building + "|" + inProgress;
        (placed ? builtByPlayer : brokenByPlayer).computeIfAbsent(key, k -> new int[2])[0]++;
        (placed ? builtByPlayer : brokenByPlayer).get(key)[1] = (int) (System.currentTimeMillis() / 1000);
    }

    /** Called every few seconds: reports player building help/damage once they've stopped for a bit. */
    public void flushBuildingChanges() {
        int nowS = (int) (System.currentTimeMillis() / 1000);
        for (var it = builtByPlayer.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            if (nowS - en.getValue()[1] < 30) continue;
            it.remove();
            String[] k = en.getKey().split("\\|");
            int n = en.getValue()[0];
            boolean inProgress = Boolean.parseBoolean(k[2]);
            if (inProgress) {
                memory.thankFor(k[0], "helped build");
                log("help", k[0] + " helped build your " + k[1] + " (" + n + " block" + (n == 1 ? "" : "s") + ")", n >= 8);
            } else {
                log("note", k[0] + " added " + n + " block" + (n == 1 ? "" : "s") + " to your " + k[1], false);
            }
            dirty = true;
        }
        for (var it = brokenByPlayer.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            if (nowS - en.getValue()[1] < 30) continue;
            it.remove();
            String[] k = en.getKey().split("\\|");
            int n = en.getValue()[0];
            if (n >= 3) memory.adjustOpinion(k[0], -1);
            log("damage", k[0] + " broke " + n + " block" + (n == 1 ? "" : "s") + " of your " + k[1], n >= 5);
            dirty = true;
        }
    }

    /** A player killed something that was attacking it. */
    public void onRescued(String player, String mob) {
        CompanionEntity e = entity();
        if (!allow("rescued:" + player, 60_000)) return;
        memory.thankFor(player, "saved you from a " + mob);
        boolean close = e != null && e.getHealth() < 10;
        log("help", player + " killed the " + mob + " that was after you" + (close ? " (you were in trouble)" : ""), close);
        if (allow("rescuedtalk:" + player, 5 * 60_000)) {
            stimulate(new Stimulus("helped", player + " just killed the " + mob + " that was attacking you" + (close ? ", and you were badly hurt." : "."), player, 0));
        }
        dirty = true;
    }

    /** Came across something new while out and about (a new biome, a village, a temple...). */
    public void onDiscovery(String what, net.minecraft.util.math.BlockPos pos, boolean big) {
        log("discovery", "Found " + what + " at " + pos.getX() + ", " + pos.getZ(), big);
        memory.addEvent("You found " + what + " at " + pos.getX() + ", " + pos.getZ() + ".");
        dirty = true;
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
                boolean stuck = outcome.achievedNothing() || outcome.summary().toLowerCase().matches(".*(fail|couldn't|could not|can't|missing|not enough).*");
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
            for (CompanionMemory.HelpRequest h : memory.openHelpRequests()) {
                if (!g.id.equals(h.goalId)) continue;
                h.status = gift ? "helped" : "gave_up";
                if (gift) {
                    String giver = memory.journalSince(System.currentTimeMillis() - 60_000).stream().filter(j -> j.kind.equals("gift"))
                            .map(j -> j.text.split(" gave you ")[0]).reduce((a, b) -> b).orElse("");
                    if (!giver.isEmpty()) {
                        h.helper = giver;
                        memory.thankFor(giver, "helped with " + g.title);
                    }
                }
            }
            if (g.parent != null) lastGoalId = g.parent; // back to what it was doing
            if (!planRunning.get()) nextPursuit = Math.min(nextPursuit, System.currentTimeMillis() + 3_000);
            dirty = true;
        }
    }

    // ------------------------------------------------------------------ needs (server thread, every few seconds)

    /**
     * Looks at the body's needs (hunger, a pickaxe, torches, bag space, night) and keeps one goal per need at an
     * importance that matches how pressing it is. The goal closes when the need goes away, however that happened.
     */
    public void checkNeeds() {
        CompanionEntity e = entity();
        if (e == null) return;
        PersonaProfile p = profile;
        java.util.Map<String, Integer> urgency = new java.util.HashMap<>();
        java.util.Map<String, String> titles = new java.util.HashMap<>();

        int food = e.getFood();
        if (!e.hasFood() && food <= 12) {
            urgency.put("food", food <= 4 ? 10 : food <= 8 ? 8 : 6);
            titles.put("food", food <= 6 ? "find something to eat (starving)" : "get food (getting hungry, nothing to eat)");
        }
        int pickaxes = 0;
        float bestPickLeft = 0;
        for (int i = 0; i < e.getInventory().size(); i++) {
            net.minecraft.item.ItemStack s = e.getInventory().getStack(i);
            if (s.getItem() instanceof net.minecraft.item.PickaxeItem) {
                pickaxes++;
                bestPickLeft = Math.max(bestPickLeft, 1f - (float) s.getDamage() / s.getMaxDamage());
            }
        }
        if (e.getMainHandStack().getItem() instanceof net.minecraft.item.PickaxeItem) {
            pickaxes++;
            bestPickLeft = Math.max(bestPickLeft, 1f - (float) e.getMainHandStack().getDamage() / e.getMainHandStack().getMaxDamage());
        }
        if (pickaxes == 1 && bestPickLeft < 0.1f) {
            urgency.put("pickaxe", 5);
            titles.put("pickaxe", "make a spare pickaxe (this one's nearly worn out)");
        } else if (pickaxes == 0 && !memory.mines.isEmpty()) {
            urgency.put("pickaxe", 6);
            titles.put("pickaxe", "make a pickaxe (has none)");
        }
        if (e.count(net.minecraft.item.Items.TORCH) < 4 && !memory.mines.isEmpty()) {
            urgency.put("torches", 4);
            titles.put("torches", "make torches (running low)");
        }
        int free = 0;
        for (int i = 0; i < e.getInventory().size(); i++) if (e.getInventory().getStack(i).isEmpty()) free++;
        if (free <= 3) {
            urgency.put("bag", free <= 1 ? 8 : 6);
            titles.put("bag", "put things away in storage (bag nearly full)");
        }
        if (e.getWorld().isNight() && !e.isSleeping()) {
            if (dev.aicompanion.game.tasks.SleepTask.findOwnBed(e) != null) {
                urgency.put("sleep", p.bravery() <= 4 ? 8 : 6);
                titles.put("sleep", "go to bed (it's night)");
            } else if (p.bravery() <= 4 && memory.home() != null) {
                urgency.put("shelter", 7);
                titles.put("shelter", "get home and indoors for the night");
            }
        }

        // Close needs that went away; add or re-weight the ones that are there.
        for (Goals.Goal g : Goals.active(memory)) {
            if (g.need != null && !urgency.containsKey(g.need)) {
                Goals.finish(memory, g, "done");
                log("need", "Taken care of: " + g.title, false);
            }
        }
        for (var entry : urgency.entrySet()) {
            Goals.Goal existing = null;
            for (Goals.Goal g : Goals.active(memory)) if (entry.getKey().equals(g.need)) existing = g;
            if (existing != null) {
                existing.importance = entry.getValue();
                existing.title = titles.get(entry.getKey());
            } else {
                Goals.Goal g = Goals.add(memory, titles.get(entry.getKey()), "short", entry.getValue(), null, "need", null);
                g.need = entry.getKey();
                log("need", "Need: " + g.title, false);
                if (entry.getValue() >= 8 && !planRunning.get()) {
                    nextPursuit = 0;
                    freeTimeUntil = 0; // pressing needs don't wait for the end of a break
                }
            }
        }
        dirty = true;
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
        // Something big got finished: ask what's next, the way you'd ask a friend.
        List<String> accomplished = new java.util.ArrayList<>();
        for (CompanionMemory.JournalEntry j : since) {
            if (j.kind.equals("built")) accomplished.add(j.text.replaceFirst("^Finished ", ""));
            else if (j.kind.equals("goal") && j.text.startsWith("Achieved goal: ")) accomplished.add(j.text.substring("Achieved goal: ".length()));
        }
        if (!accomplished.isEmpty()) {
            digest += "\nYou finished: " + String.join("; ", accomplished) + ". Now that that's done, what do you want next? "
                    + "It can be practical, or just something you'd enjoy (a bigger house, a new room, a tower, a garden, a trip somewhere).";
        }
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
                        + "and optionally a few words on what you have in mind (for a free day: who you'd like to see, where you'd like to go, "
                        + "or just taking it easy). Then \"INTENT:\" with what you do first. "
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
            case "visit" -> {
                prompt.append("\n").append(s.event()).append("\n");
                format = "This is your own time. If you feel like it, say something (1 to 2 short sentences): a greeting, a question, a remark on what you see, "
                        + "or nothing at all. Add \"INTENT:\" only if you actually want to do something about it (help them with something, go home, stay a while...).";
            }
            case "stuck" -> {
                Goals.Goal g = Goals.find(memory, s.speaker());
                prompt.append("\n").append(s.event()).append("\n");
                if (g != null && !g.stuckNeed.isBlank()) prompt.append("What would help, as far as you can tell: ").append(g.stuckNeed).append("\n");
                format = "Decide what to do about it, in character. You can ask someone in chat for help (say who and exactly what you need), try something "
                        + "different, or give up on it. Only ask if you really can't manage alone. Write what you say in chat (or nothing), "
                        + "then \"INTENT:\" with what you'll do.";
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
            case "stuck" -> "[Stuck: " + s.event() + "]";
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
            // A new day plan replaces yesterday's: a long break from a free day doesn't carry over into a work day.
            if (!memory.dayPlan.kind.equals("free")) {
                freeTimeUntil = 0;
                nextPursuit = 0;
                CompanionEntity body = entity();
                if (body != null && body.getServer() != null) body.getServer().execute(() -> {
                    if (body.currentTask() instanceof dev.aicompanion.game.tasks.LeisureTask) body.cancelTask("a new day's plans");
                });
            }
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
        if (s.kind().equals("stuck")) afterStuck(s, parsed);

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

    private static final Pattern GIVE_UP = Pattern.compile("(?i).*\\b(give up|giving up|drop (it|that)|forget (about )?it|abandon|not worth it)\\b.*");

    /** The character said something about being stuck: asking for help is recorded; giving up drops the goal. */
    private void afterStuck(Stimulus s, Reply parsed) {
        Goals.Goal g = Goals.find(memory, s.speaker());
        if (g == null) return;
        if (!parsed.spoken().isBlank()) {
            CompanionMemory.HelpRequest h = new CompanionMemory.HelpRequest();
            h.goalId = g.id;
            h.goal = g.title;
            h.problem = g.stuck;
            h.need = g.stuckNeed.isBlank() ? g.stuck : g.stuckNeed;
            h.asked = System.currentTimeMillis();
            memory.helpRequests.add(h);
            while (memory.helpRequests.size() > 20) memory.helpRequests.remove(0);
            log("help", "Asked for help with " + g.title + ": " + parsed.spoken(), false);
        }
        if (parsed.intent() != null && GIVE_UP.matcher(parsed.intent()).matches()) {
            Goals.finish(memory, g, "dropped");
            log("goal", "Gave up on: " + g.title, false);
        }
        dirty = true;
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
