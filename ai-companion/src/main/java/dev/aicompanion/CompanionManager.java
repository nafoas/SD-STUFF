package dev.aicompanion;

import dev.aicompanion.ai.AicordClient;
import dev.aicompanion.ai.CompanionBrain;
import dev.aicompanion.entity.CompanionEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Keeps track of every companion's brain and routes game events (chat, ticks) to them. */
public final class CompanionManager {
    private CompanionManager() {}

    private static final Map<String, CompanionBrain> BRAINS = new ConcurrentHashMap<>();
    private static volatile List<AicordClient.Character> characters = List.of();
    @Nullable private static MinecraftServer server;
    private static int tickCounter;
    /** Players currently near each companion (by character id), to greet only on arrival. */
    private static final Map<String, java.util.Set<String>> NEARBY = new ConcurrentHashMap<>();

    public static void setServer(@Nullable MinecraftServer s) {
        server = s;
    }

    @Nullable
    public static CompanionBrain brain(String characterId) {
        if (characterId == null || characterId.isEmpty()) return null;
        return BRAINS.get(characterId);
    }

    public static CompanionBrain brainFor(String characterId, String name) {
        return BRAINS.computeIfAbsent(characterId, id -> new CompanionBrain(id, name));
    }

    public static Collection<CompanionBrain> brains() {
        return BRAINS.values();
    }

    @Nullable
    public static CompanionBrain byName(String name) {
        for (CompanionBrain b : BRAINS.values()) {
            if (b.name().equalsIgnoreCase(name.trim())) return b;
        }
        return null;
    }

    // ------------------------------------------------------------------ entity lifecycle

    public static void onEntityLoaded(CompanionEntity e) {
        if (e.getCharacterId().isEmpty()) return;
        CompanionBrain brain = brainFor(e.getCharacterId(), e.getCharacterName());
        CompanionEntity existing = brain.entity();
        if (existing != null && existing != e) {
            // Only one body per character.
            e.discard();
            return;
        }
        brain.attach(e);
    }

    public static void onEntityUnloaded(CompanionEntity e) {
        CompanionBrain brain = brain(e.getCharacterId());
        if (brain != null) brain.detach(e);
    }

    // ------------------------------------------------------------------ AICord characters

    public static CompletableFuture<List<AicordClient.Character>> refreshCharacters() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                characters = AicordClient.listCharacters();
                return characters;
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        });
    }

    public static List<AicordClient.Character> cachedCharacters() {
        return characters;
    }

    /** Spawns (or summons back) a character next to a player. Reports progress through feedback. */
    public static void spawn(ServerPlayerEntity player, String characterName, Consumer<Text> feedback) {
        refreshCharacters().whenComplete((list, error) -> {
            MinecraftServer srv = player.getServer();
            if (srv == null) return;
            srv.execute(() -> {
                if (error != null) {
                    feedback.accept(Text.literal("Couldn't reach AICord: " + rootMessage(error)).formatted(Formatting.RED));
                    return;
                }
                AicordClient.Character match = list.stream().filter(c -> c.name().equalsIgnoreCase(characterName.trim())).findFirst()
                        .or(() -> list.stream().filter(c -> c.name().toLowerCase().startsWith(characterName.trim().toLowerCase())).findFirst())
                        .orElse(null);
                if (match == null) {
                    feedback.accept(Text.literal("No AICord character called '" + characterName + "'. Try /companion characters.").formatted(Formatting.RED));
                    return;
                }
                CompanionBrain brain = brainFor(match.id(), match.name());
                CompanionEntity body = brain.entity();
                if (body != null) {
                    if (body.getWorld() != player.getWorld()) {
                        feedback.accept(Text.literal(match.name() + " is in another dimension.").formatted(Formatting.YELLOW));
                        return;
                    }
                    body.refreshPositionAndAngles(player.getX(), player.getY(), player.getZ(), player.getYaw(), 0);
                    body.getNavigation().stop();
                    feedback.accept(Text.literal(match.name() + " was summoned to you."));
                    return;
                }
                ServerWorld world = player.getServerWorld();
                CompanionEntity e = AiCompanionMod.COMPANION.create(world);
                if (e == null) return;
                e.refreshPositionAndAngles(player.getX() + 1, player.getY(), player.getZ() + 1, player.getYaw(), 0);
                e.setCharacter(match.id(), match.name());
                e.setOwner(player);
                e.initialize(world, world.getLocalDifficulty(e.getBlockPos()), SpawnReason.COMMAND, null, null);
                world.spawnEntity(e);
                brain.attach(e);
                NEARBY.put(brain.id(), new java.util.HashSet<>(List.of(player.getName().getString())));
                boolean returning = !brain.memory().events.isEmpty();
                String owner = player.getName().getString();
                if (brain.memory().opinions.isEmpty()) brain.memory().adjustOpinion(owner, 3);
                feedback.accept(Text.literal(match.name() + " has joined the world.").formatted(Formatting.GREEN));
                if (!brain.hasProfile()) {
                    feedback.accept(Text.literal("Getting to know " + match.name() + "'s personality...").formatted(Formatting.GRAY));
                    brain.interview(() -> brain.onSpawned(owner, returning));
                } else {
                    brain.onSpawned(owner, returning);
                }
            });
        });
    }

    public static boolean dismiss(String name) {
        CompanionBrain brain = byName(name);
        if (brain == null) return false;
        CompanionEntity e = brain.entity();
        if (e == null) return false;
        e.cancelTask("dismissed");
        e.discard();
        brain.saveIfDirty();
        return true;
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage();
    }

    // ------------------------------------------------------------------ chat

    public static void broadcast(Text text) {
        MinecraftServer s = server;
        if (s == null) return;
        s.execute(() -> s.getPlayerManager().broadcast(text, false));
    }

    /** A player said something in chat. Called on the server thread. */
    public static void onPlayerChat(ServerPlayerEntity sender, String message) {
        String player = sender.getName().getString();
        List<CompanionBrain> named = new ArrayList<>();
        for (CompanionBrain brain : BRAINS.values()) {
            if (brain.entity() != null && mentions(message, brain.name())) named.add(brain);
        }
        List<CompanionBrain> targets = new ArrayList<>(named);
        if (named.isEmpty()) {
            // Continuing a conversation: the nearest companion this player talked to recently.
            CompanionBrain nearest = null;
            double best = Double.MAX_VALUE;
            int radius = ModConfig.get().conversationRadius;
            for (CompanionBrain brain : BRAINS.values()) {
                CompanionEntity e = brain.entity();
                if (e == null || e.getWorld() != sender.getWorld() || !brain.recentlyTalkedWith(player, 60_000)) continue;
                double d = e.squaredDistanceTo(sender);
                if (d <= radius * radius && d < best) {
                    best = d;
                    nearest = brain;
                }
            }
            if (nearest != null) targets.add(nearest);
        }
        int hearing = ModConfig.get().hearingRadius;
        for (CompanionBrain brain : targets) {
            CompanionEntity e = brain.entity();
            if (e == null) continue;
            if (hearing > 0 && (e.getWorld() != sender.getWorld() || e.squaredDistanceTo(sender) > hearing * hearing)) continue;
            brain.onChat(player, message, 0);
        }
    }

    /** A companion said something: other companions it names will hear it (with a limit so they don't chat forever). */
    public static void companionSpoke(CompanionBrain speaker, String line, int depth) {
        if (depth >= 2) return;
        for (CompanionBrain other : BRAINS.values()) {
            if (other == speaker || other.entity() == null) continue;
            if (mentions(line, other.name())) other.onChat(speaker.name() + " (another character like you)", line, depth + 1);
        }
    }

    private static boolean mentions(String message, String name) {
        String first = name.split("\\s+")[0];
        Pattern p = Pattern.compile("(?i)(^|[^\\p{L}\\p{N}])@?(" + Pattern.quote(name) + "|" + Pattern.quote(first) + ")([^\\p{L}\\p{N}]|$)");
        return p.matcher(message).find();
    }

    // ------------------------------------------------------------------ periodic

    public static void tick(MinecraftServer s) {
        tickCounter++;
        if (tickCounter % 40 == 0) {
            long now = System.currentTimeMillis();
            int idleSeconds = ModConfig.get().idleThinkSeconds;
            for (CompanionBrain brain : BRAINS.values()) {
                CompanionEntity e = brain.entity();
                if (e == null || !e.isAlive()) continue;
                var p = brain.profile();
                // Idle check-in: restless characters get bored sooner.
                if (idleSeconds > 0 && !brain.isThinkingOrActing() && !e.isBusy()) {
                    double restlessness = (p.curiosity() + p.diligence() + p.sociability()) / 30.0;
                    long interval = (long) (idleSeconds * 1000 * (1.5 - restlessness));
                    if (now - brain.lastActivity() > interval) brain.onIdle();
                }
                // Sociable characters greet players who walk up to them.
                java.util.Set<String> before = NEARBY.getOrDefault(brain.id(), java.util.Set.of());
                java.util.Set<String> now2 = new java.util.HashSet<>();
                for (ServerPlayerEntity pl : s.getPlayerManager().getPlayerList()) {
                    if (pl.getWorld() == e.getWorld() && !pl.isSpectator() && pl.squaredDistanceTo(e) < (before.contains(pl.getName().getString()) ? 16 * 16 : 8 * 8)) {
                        now2.add(pl.getName().getString());
                    }
                }
                if (p.sociability() >= 6 && !brain.isThinkingOrActing()) {
                    for (String arrived : now2) if (!before.contains(arrived)) brain.onPlayerApproach(arrived);
                }
                NEARBY.put(brain.id(), now2);
            }
        }
        if (tickCounter % 600 == 0) saveAll();
    }

    public static void saveAll() {
        for (CompanionBrain b : BRAINS.values()) b.saveIfDirty();
    }

    public static void shutdown() {
        for (CompanionBrain b : BRAINS.values()) b.shutdown();
        BRAINS.clear();
        server = null;
    }
}
