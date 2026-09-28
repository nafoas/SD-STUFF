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
        if (brain.memory().ownerUuid.isEmpty() && e.getOwnerUuid() != null) {
            brain.memory().ownerUuid = e.getOwnerUuid().toString();
            brain.memory().ownerName = e.getOwnerName();
        }
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
                brain.memory().ownerUuid = player.getUuidAsString();
                brain.memory().ownerName = player.getName().getString();
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

    /** Brings a dead companion back: at its home if it has one, else next to its owner, else at world spawn. */
    public static void respawn(CompanionBrain brain) {
        MinecraftServer srv = server;
        if (srv == null || brain.entity() != null) return;
        var memory = brain.memory();
        ServerWorld world = srv.getOverworld();
        net.minecraft.util.math.BlockPos pos = null;
        String where = "spawn";
        for (var place : memory.places.values()) {
            if (!place.type.equals("home")) continue;
            ServerWorld w = worldFor(srv, place.dimension);
            if (w != null && place.y != dev.aicompanion.ai.CompanionMemory.UNKNOWN_Y) {
                world = w;
                pos = new net.minecraft.util.math.BlockPos(place.x, place.y, place.z);
                where = "home";
                break;
            }
        }
        ServerPlayerEntity owner = memory.ownerUuid.isEmpty() ? null : srv.getPlayerManager().getPlayer(java.util.UUID.fromString(memory.ownerUuid));
        if (pos == null && owner != null) {
            world = owner.getServerWorld();
            pos = owner.getBlockPos();
            where = owner.getName().getString();
        }
        if (pos == null) pos = world.getSpawnPos();
        world.getChunk(pos); // make sure it's loaded
        pos = world.getTopPosition(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, pos).getY() > pos.getY() + 3 && !world.getBlockState(pos).isAir()
                ? world.getTopPosition(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, pos) : pos;
        CompanionEntity e = AiCompanionMod.COMPANION.create(world);
        if (e == null) return;
        e.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        e.setCharacter(brain.id(), brain.name());
        if (owner != null) e.setOwner(owner);
        else if (!memory.ownerUuid.isEmpty()) e.setOwner(java.util.UUID.fromString(memory.ownerUuid), memory.ownerName);
        e.initialize(world, world.getLocalDifficulty(pos), SpawnReason.TRIGGERED, null, null);
        world.spawnEntity(e);
        brain.attach(e);
        broadcast(Text.literal(brain.name() + " is back (respawned at " + where + ").").formatted(Formatting.GRAY));
        brain.onRespawned();
    }

    @Nullable
    private static ServerWorld worldFor(MinecraftServer srv, String dimension) {
        for (ServerWorld w : srv.getWorlds()) if (w.getRegistryKey().getValue().toString().equals(dimension)) return w;
        return null;
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

    // ------------------------------------------------------------------ chat log (everything said on the server)

    private record ChatLine(long time, String text) {}

    private static final java.util.Deque<ChatLine> CHAT_LOG = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private static final Pattern COORDS3 = Pattern.compile("(-?\\d{1,7})\\s*[, ]\\s*(-?\\d{1,3})\\s*[, ]\\s*(-?\\d{1,7})");
    private static final Pattern COORDS2 = Pattern.compile("(?i)(?:\\bat\\b|coords?|@|x)\\s*:?\\s*(-?\\d{1,7})\\s*[, ]\\s*(?:z\\s*:?\\s*)?(-?\\d{1,7})\\b");
    private static final Pattern PLACE_NAME = Pattern.compile("(?i)(?:\\b(my|our|the|a|his|her|their)\\s+)?([a-z][a-z' ]{1,30}?)\\s+(?:is\\s+|are\\s+)?(?:at|@|:|coords)\\s*:?\\s*$");
    private static final java.util.Set<String> INTEREST_WORDS = java.util.Set.of("base", "house", "home", "build", "built", "village", "diamond",
            "diamonds", "castle", "farm", "mine", "cave", "tower", "help", "anyone", "someone", "everyone", "who wants", "come see", "check out", "look at");

    private static void recordChat(String text) {
        CHAT_LOG.addLast(new ChatLine(System.currentTimeMillis(), text));
        while (CHAT_LOG.size() > 120) CHAT_LOG.pollFirst();
    }

    /** The last lines of server chat (players, companions, joins, deaths) within maxAgeMs, oldest first. */
    public static List<String> recentChat(int lines, long maxAgeMs) {
        long cutoff = System.currentTimeMillis() - maxAgeMs;
        List<ChatLine> all = new ArrayList<>(CHAT_LOG);
        List<String> out = new ArrayList<>();
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("HH:mm");
        for (ChatLine l : all.subList(Math.max(0, all.size() - lines), all.size())) {
            if (l.time() >= cutoff) out.add("[" + fmt.format(new java.util.Date(l.time())) + "] " + l.text());
        }
        return out;
    }

    private static int chatLinesSince(long ms) {
        long cutoff = System.currentTimeMillis() - ms;
        int n = 0;
        for (ChatLine l : CHAT_LOG) if (l.time() >= cutoff) n++;
        return n;
    }

    /** System messages: joins, leaves, deaths, advancements, and companions' own lines. */
    public static void onGameMessage(Text message) {
        String text = message.getString();
        if (text.isBlank() || text.startsWith("[AI Companion]")) return;
        recordChat(text);
        java.util.regex.Matcher joined = Pattern.compile("^(\\S+) joined the game$").matcher(text);
        if (joined.find() && ModConfig.get().overhearChat) {
            for (CompanionBrain brain : BRAINS.values()) {
                if (brain.entity() != null && brain.profile().sociability() >= 5) brain.onOverheard(joined.group(1) + " just joined the game.");
            }
        }
    }

    /** Coordinates mentioned in chat become points of interest the companion can visit later. */
    private static void notePointOfInterest(CompanionBrain brain, String speaker, String message) {
        java.util.regex.Matcher m3 = COORDS3.matcher(message);
        java.util.regex.Matcher m2 = COORDS2.matcher(message);
        int x, y, z;
        int start;
        if (m3.find()) {
            x = Integer.parseInt(m3.group(1));
            y = Integer.parseInt(m3.group(2));
            z = Integer.parseInt(m3.group(3));
            start = m3.start();
        } else if (m2.find()) {
            x = Integer.parseInt(m2.group(1));
            y = dev.aicompanion.ai.CompanionMemory.UNKNOWN_Y;
            z = Integer.parseInt(m2.group(2));
            start = m2.start();
        } else {
            return;
        }
        if (Math.abs(x) < 3 && Math.abs(z) < 3) return; // "2 3 4" in normal speech is rarely a location
        String before = message.substring(0, start);
        java.util.regex.Matcher nm = PLACE_NAME.matcher(before);
        String name;
        if (nm.find()) {
            String owner = nm.group(1) == null ? "" : nm.group(1).toLowerCase();
            String noun = nm.group(2).trim().toLowerCase();
            name = (owner.equals("my") || owner.equals("our") ? speaker.toLowerCase() + "'s " : "") + noun;
        } else {
            name = "spot " + speaker.toLowerCase() + " mentioned";
        }
        CompanionEntity e = brain.entity();
        String dim = e == null ? "minecraft:overworld" : e.getWorld().getRegistryKey().getValue().toString();
        var existing = brain.memory().places.get(name);
        if (existing != null && !existing.type.equals("poi")) name = name + " (from chat)";
        var loc = new dev.aicompanion.ai.CompanionMemory.Location(dim, x, y, z);
        loc.type = "poi";
        loc.note = speaker + " said: " + message;
        brain.memory().places.put(name, loc);
        brain.log("discovery", "Heard about '" + name + "' at " + x + ", " + (y == dev.aicompanion.ai.CompanionMemory.UNKNOWN_Y ? "?" : y) + ", " + z + " from " + speaker, false);
    }

    /** Decides whether chat not addressed to a companion is interesting enough for it to consider joining in. */
    private static void considerOverhearing(CompanionBrain brain, CompanionEntity e, ServerPlayerEntity sender, String message) {
        String lower = message.toLowerCase();
        int score = 0;
        if (COORDS3.matcher(message).find() || COORDS2.matcher(message).find()) score += 2;
        for (String place : brain.memory().places.keySet()) if (lower.contains(place)) { score += 2; break; }
        int interests = 0;
        for (String w : INTEREST_WORDS) if (lower.contains(w)) interests++;
        for (String activity : brain.profile().favoriteActivities()) {
            for (String w : activity.toLowerCase().split("\\W+")) if (w.length() > 3 && lower.contains(w)) interests++;
        }
        score += Math.min(3, interests);
        if (lower.contains("?")) score++;
        if (chatLinesSince(60_000) >= 4) score++; // a lively conversation
        if (e.getWorld() == sender.getWorld() && e.squaredDistanceTo(sender) < 24 * 24) score++;
        int sociability = brain.profile().sociability();
        if (sociability >= 7) score++;
        if (sociability <= 3) score--;
        if (score >= 3) brain.onOverheard("The latest message was from " + sender.getName().getString() + ".");
    }

    /** A player said something in chat. Called on the server thread. */
    public static void onPlayerChat(ServerPlayerEntity sender, String message) {
        String player = sender.getName().getString();
        recordChat("<" + player + "> " + message);
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
        for (CompanionBrain brain : BRAINS.values()) {
            CompanionEntity e = brain.entity();
            if (e == null) continue;
            if (hearing > 0 && (e.getWorld() != sender.getWorld() || e.squaredDistanceTo(sender) > hearing * hearing)) continue;
            notePointOfInterest(brain, player, message);
            if (targets.contains(brain)) brain.onChat(player, message, 0);
            else if (ModConfig.get().overhearChat) considerOverhearing(brain, e, sender, message);
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
            for (CompanionBrain brain : BRAINS.values()) {
                if (brain.entity() == null && brain.respawnAt() > 0 && now >= brain.respawnAt()) respawn(brain);
            }
            for (CompanionBrain brain : BRAINS.values()) {
                CompanionEntity e = brain.entity();
                if (e == null || !e.isAlive()) continue;
                var p = brain.profile();
                brain.maybeMorning(e.getWorld().getTimeOfDay() / 24000L);
                brain.maybeCheckIn(now);
                if (tickCounter % 100 == 0) brain.checkGoals();
                brain.maybePursue(now);
                boolean playersAround = false;
                for (ServerPlayerEntity pl : s.getPlayerManager().getPlayerList()) {
                    if (pl.getWorld() == e.getWorld() && pl.squaredDistanceTo(e) < 48 * 48) playersAround = true;
                }
                if (p.sociability() >= 8 && !s.getPlayerManager().getPlayerList().isEmpty()) playersAround = true;
                brain.maybeChatter(now, playersAround);
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
