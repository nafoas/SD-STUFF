package dev.aicompanion.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What a companion remembers across sessions: feelings about players, named places, what's in which chest,
 * resources it has seen, what it built, and a running activity log. Saved to config/ai-companion/memory/<id>.json.
 * Touched by the server, mind and body threads, so collections are concurrent (see {@link #makeThreadSafe()}).
 */
public class CompanionMemory {
    /** Height of a place heard about in chat with only x and z. */
    public static final int UNKNOWN_Y = -9999;

    public static class Location {
        public String dimension;
        public int x, y, z;
        /** home, farm, mine, storage, path, other */
        public String type = "other";
        public String note = "";
        /** When it last went there (0 = never). */
        public long visited;

        public Location() {}

        public Location(String dimension, int x, int y, int z) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    public static class JournalEntry {
        public long time;
        /** task_start, task_end, combat, damage, items, gift, chat, said, decision, discovery, death, need, note */
        public String kind;
        public String text;
        /** Major entries (a big task ending, dying) trigger a character check-in. */
        public boolean major;

        public JournalEntry() {}

        public JournalEntry(long time, String kind, String text, boolean major) {
            this.time = time;
            this.kind = kind;
            this.text = text;
            this.major = major;
        }
    }

    public static class ChestRecord {
        public String dimension;
        public int x, y, z;
        /** "self", "natural", or the name of the player who placed it */
        public String owner = "";
        public String label = "";
        public Map<String, Integer> contents = new LinkedHashMap<>();
        public long seen;
    }

    public static class Sighting {
        public String dimension;
        public int x, y, z;
        public long seen;
    }

    /** Something the character agreed to do for a player. */
    public static class Promise {
        public String player;
        public String what;
        public long made;
        /** open, done, failed, dropped */
        public String status = "open";
        public String result = "";
        /** The goal that tracks this promise. */
        public String goalId;
    }

    public static class Structure {
        public String label;
        public String purpose = "";
        public String dimension;
        public int minX, minY, minZ, maxX, maxY, maxZ;
        public long built;
    }

    /** How the character feels about each player, from -10 (hates) to 10 (adores). Keyed by lowercase name. */
    public Map<String, Integer> opinions = new LinkedHashMap<>();
    public Map<String, Location> places = new LinkedHashMap<>();
    /** Short notes on recent events, oldest first (fed to the character with every message). */
    public List<String> events = new ArrayList<>();
    /** Recent conversation with the character AI (compact form, without the situation reports). */
    public List<AicordClient.ChatMessage> conversation = new ArrayList<>();
    /** Everything the body did and experienced, oldest first. */
    public List<JournalEntry> journal = new ArrayList<>();
    /** Chests it has looked into, keyed "dimension|x,y,z". */
    public Map<String, ChestRecord> chests = new LinkedHashMap<>();
    /** Resources and creatures it has seen, by name (iron_ore, oak_log, sheep, village...). */
    public Map<String, List<Sighting>> sightings = new LinkedHashMap<>();
    public List<Structure> structures = new ArrayList<>();
    public List<Promise> promises = new ArrayList<>();
    public List<Goals.Goal> goals = new ArrayList<>();
    /** The character's own words on what it's planning and what matters to it right now (updated at check-ins). */
    public String plans = "";

    /** What kind of day the character decided today is. Free time is planned in whole days, not minutes. */
    public static class DayPlan {
        /** Minecraft day number this plan is for (-1 = none yet). */
        public long day = -1;
        /** work, goals, free or mixed */
        public String kind = "mixed";
        public String note = "";

        public String describe() {
            String what = switch (kind) {
                case "work" -> "a work day (practical jobs, needs, keeping things running)";
                case "goals" -> "a day for pushing on your bigger goals";
                case "free" -> "a free day (wandering, visiting, looking around, relaxing; only urgent things get done)";
                default -> "a mixed day (some work, some time to yourself)";
            };
            return what + (note.isBlank() ? "" : ". " + note);
        }
    }

    public DayPlan dayPlan = new DayPlan();

    /** Who brought it into the world (to respawn it next to them and keep ownership). */
    public String ownerUuid = "";
    public String ownerName = "";

    public static class Death {
        public String dimension;
        public int x, y, z;
        public long time;
        public String cause = "";
        public List<String> items = new ArrayList<>();
    }

    @org.jetbrains.annotations.Nullable public Death lastDeath;

    /** A farm it made or tends: the tilled area and what grows there. */
    public static class Farm {
        public String name;
        public String dimension;
        public int minX, minZ, maxX, maxZ, y;
        public String crop = "wheat";

        public int size() {
            return (maxX - minX + 1) * (maxZ - minZ + 1);
        }
    }

    /** Its mine: a staircase down from the entrance, then a tunnel with side branches at the working level. */
    public static class Mine {
        public String name;
        public String dimension;
        public int entranceX, entranceY, entranceZ;
        /** north/south/east/west */
        public String direction;
        /** Where the staircase has reached (the next step continues from here). */
        public int stairX, stairY, stairZ;
        /** The tunnel at the working level: where it continues. */
        public int headX, headY, headZ;
        public boolean tunnelStarted;
        public int tunnelLength;
        public int branches;
        public int stepsSinceTorch;
    }

    /** A path it laid between two places (and walks along). */
    public static class Trail {
        public String from, to;
        public String dimension;
        public List<int[]> points = new ArrayList<>();
    }

    /** Buildings it knows room by room (its own, designed or scanned), and designs not built yet by building name. */
    public List<dev.aicompanion.game.build.BuildingModel> buildings = new ArrayList<>();
    public Map<String, dev.aicompanion.game.build.BuildingModel> drafts = new LinkedHashMap<>();
    /** When it last went to see each player (lowercase name), for free-time visits. */
    public Map<String, Long> lastVisited = new LinkedHashMap<>();
    /** Times it asked someone for help, and whether anyone did. */
    public List<HelpRequest> helpRequests = new ArrayList<>();
    /** How much each player has helped it (lowercase name -> times), remembered with gratitude. */
    public Map<String, Integer> helpedBy = new LinkedHashMap<>();

    public static class HelpRequest {
        public String goalId;
        public String goal;
        public String problem;
        /** What would help, in the action layer's words (items, a place, a skill). */
        public String need;
        public long asked;
        /** open, helped, gave_up */
        public String status = "open";
        public String helper = "";
    }

    /** Biomes it has been to. */
    public List<String> biomesSeen = new ArrayList<>();

    /** Returns true the first time it's in this biome. */
    public boolean noteBiome(String biome) {
        if (biomesSeen.contains(biome)) return false;
        boolean first = biomesSeen.isEmpty(); // where it lives isn't a discovery
        biomesSeen.add(biome);
        return !first;
    }

    /** Remembers a generated structure (village, temple...) as a place. Returns true if it's new. */
    public boolean noteStructure(String kind, String dimension, net.minecraft.util.math.BlockPos center) {
        for (Location l : places.values()) {
            if (l.type.equals("poi") && l.note.startsWith("structure:" + kind) && dimension.equals(l.dimension)
                    && Math.abs(l.x - center.getX()) < 96 && Math.abs(l.z - center.getZ()) < 96) return false;
        }
        String name = kind.replace('_', ' ');
        for (int i = 2; places.containsKey(name); i++) name = kind.replace('_', ' ') + " " + i;
        Location loc = new Location(dimension, center.getX(), UNKNOWN_Y, center.getZ());
        loc.type = "poi";
        loc.note = "structure:" + kind + " found while out and about";
        places.put(name, loc);
        return true;
    }

    public List<HelpRequest> openHelpRequests() {
        List<HelpRequest> out = new ArrayList<>();
        for (HelpRequest h : helpRequests) if (h.status.equals("open")) out.add(h);
        return out;
    }

    public void thankFor(String player, String how) {
        helpedBy.merge(player.toLowerCase(), 1, Integer::sum);
        adjustOpinion(player, 1);
    }

    public List<Farm> farms = new ArrayList<>();
    public List<Mine> mines = new ArrayList<>();
    public List<Trail> trails = new ArrayList<>();

    @org.jetbrains.annotations.Nullable
    public Location home() {
        for (Location l : places.values()) if (l.type.equals("home")) return l;
        return null;
    }
    /** Players (lowercase) who said the companion may take things from their chests. */
    public Set<String> chestPermissions = ConcurrentHashMap.newKeySet();

    private static final int MAX_EVENTS = 30;
    private static final int MAX_CONVERSATION = 24;
    private static final int MAX_JOURNAL = 300;
    private static final int MAX_SIGHTINGS_PER_KIND = 6;

    public void makeThreadSafe() {
        opinions = new ConcurrentHashMap<>(opinions == null ? Map.of() : opinions);
        places = new ConcurrentHashMap<>(places == null ? Map.of() : places);
        events = new CopyOnWriteArrayList<>(events == null ? List.of() : events);
        conversation = new CopyOnWriteArrayList<>(conversation == null ? List.of() : conversation);
        journal = new CopyOnWriteArrayList<>(journal == null ? List.of() : journal);
        chests = new ConcurrentHashMap<>(chests == null ? Map.of() : chests);
        Map<String, List<Sighting>> s = new ConcurrentHashMap<>();
        if (sightings != null) sightings.forEach((k, v) -> s.put(k, new CopyOnWriteArrayList<>(v)));
        sightings = s;
        structures = new CopyOnWriteArrayList<>(structures == null ? List.of() : structures);
        promises = new CopyOnWriteArrayList<>(promises == null ? List.of() : promises);
        goals = new CopyOnWriteArrayList<>(goals == null ? List.of() : goals);
        if (plans == null) plans = "";
        if (dayPlan == null) dayPlan = new DayPlan();
        farms = new CopyOnWriteArrayList<>(farms == null ? List.of() : farms);
        buildings = new CopyOnWriteArrayList<>(buildings == null ? List.of() : buildings);
        lastVisited = new ConcurrentHashMap<>(lastVisited == null ? Map.of() : lastVisited);
        helpRequests = new CopyOnWriteArrayList<>(helpRequests == null ? List.of() : helpRequests);
        helpedBy = new ConcurrentHashMap<>(helpedBy == null ? Map.of() : helpedBy);
        biomesSeen = new CopyOnWriteArrayList<>(biomesSeen == null ? List.of() : biomesSeen);
        drafts = new ConcurrentHashMap<>(drafts == null ? Map.of() : drafts);
        mines = new CopyOnWriteArrayList<>(mines == null ? List.of() : mines);
        trails = new CopyOnWriteArrayList<>(trails == null ? List.of() : trails);
        Set<String> perms = ConcurrentHashMap.newKeySet();
        if (chestPermissions != null) perms.addAll(chestPermissions);
        chestPermissions = perms;
        for (Location l : places.values()) {
            if (l.type == null) l.type = "other";
            if (l.note == null) l.note = "";
        }
    }

    // ------------------------------------------------------------------ opinions

    public int opinionOf(String player) {
        return opinions.getOrDefault(player.toLowerCase(), 0);
    }

    public int adjustOpinion(String player, int delta) {
        int value = Math.max(-10, Math.min(10, opinionOf(player) + delta));
        opinions.put(player.toLowerCase(), value);
        return value;
    }

    public static String describeOpinion(int value) {
        if (value <= -7) return "hate";
        if (value <= -3) return "dislike";
        if (value < 3) return "neutral";
        if (value < 7) return "like";
        return "adore";
    }

    // ------------------------------------------------------------------ events, conversation, journal

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

    public void log(String kind, String text, boolean major) {
        journal.add(new JournalEntry(System.currentTimeMillis(), kind, text, major));
        while (journal.size() > MAX_JOURNAL) journal.remove(0);
    }

    public List<JournalEntry> journalSince(long time) {
        List<JournalEntry> out = new ArrayList<>();
        for (JournalEntry e : journal) if (e.time > time) out.add(e);
        return out;
    }

    public List<JournalEntry> recentJournal(int n) {
        List<JournalEntry> all = List.copyOf(journal);
        return all.subList(Math.max(0, all.size() - n), all.size());
    }

    // ------------------------------------------------------------------ chests

    public static String key(String dimension, int x, int y, int z) {
        return dimension + "|" + x + "," + y + "," + z;
    }

    public void recordChest(ChestRecord record) {
        chests.put(key(record.dimension, record.x, record.y, record.z), record);
    }

    public void forgetChest(String dimension, int x, int y, int z) {
        chests.remove(key(dimension, x, y, z));
    }

    /** Chests believed to contain an item, most recently seen first. */
    public List<ChestRecord> chestsWith(String item) {
        List<ChestRecord> out = new ArrayList<>();
        for (ChestRecord r : chests.values()) if (r.contents.getOrDefault(item, 0) > 0) out.add(r);
        out.sort(Comparator.comparingLong((ChestRecord r) -> r.seen).reversed());
        return out;
    }

    // ------------------------------------------------------------------ sightings and structures

    public void sighted(String kind, String dimension, int x, int y, int z, long now) {
        List<Sighting> list = sightings.computeIfAbsent(kind, k -> new CopyOnWriteArrayList<>());
        for (Sighting s : list) {
            if (s.dimension.equals(dimension) && Math.abs(s.x - x) + Math.abs(s.y - y) + Math.abs(s.z - z) < 12) {
                s.seen = now;
                return;
            }
        }
        Sighting s = new Sighting();
        s.dimension = dimension;
        s.x = x;
        s.y = y;
        s.z = z;
        s.seen = now;
        list.add(s);
        if (list.size() > MAX_SIGHTINGS_PER_KIND) {
            list.stream().min(Comparator.comparingLong(v -> v.seen)).ifPresent(list::remove);
        }
    }

    public void forgetSighting(String kind, String dimension, int x, int y, int z) {
        List<Sighting> list = sightings.get(kind);
        if (list != null) list.removeIf(s -> s.dimension.equals(dimension) && Math.abs(s.x - x) + Math.abs(s.y - y) + Math.abs(s.z - z) < 12);
    }

    public Promise promise(String player, String what) {
        Promise p = new Promise();
        p.player = player;
        p.what = what;
        p.made = System.currentTimeMillis();
        promises.add(p);
        while (promises.size() > 20) promises.remove(0);
        return p;
    }

    public List<Promise> openPromises() {
        List<Promise> out = new ArrayList<>();
        for (Promise p : promises) if (p.status.equals("open")) out.add(p);
        return out;
    }

    @org.jetbrains.annotations.Nullable
    public dev.aicompanion.game.build.BuildingModel building(String name) {
        String n = name == null ? "" : name.trim().toLowerCase();
        if (!n.isBlank()) {
            for (var b : buildings) if (b.name.equalsIgnoreCase(n)) return b;
            for (var b : buildings) if (b.name.toLowerCase().contains(n)) return b;
        }
        if (n.isBlank() || n.contains("home") || n.contains("house")) {
            for (var b : buildings) if (b.name.toLowerCase().contains("home") || b.name.toLowerCase().contains("house")) return b;
            Location h = home();
            if (h != null) for (var b : buildings) if (b.contains(h.dimension, h.x, h.y, h.z, 2)) return b;
            if (n.isBlank()) return buildings.isEmpty() ? null : buildings.get(0);
        }
        return null;
    }

    public void addStructure(Structure s) {
        structures.add(s);
        while (structures.size() > 60) structures.remove(0);
    }
}
