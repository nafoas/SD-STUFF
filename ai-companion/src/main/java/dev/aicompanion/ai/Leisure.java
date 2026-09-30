package dev.aicompanion.ai;

import dev.aicompanion.entity.CompanionEntity;
import dev.aicompanion.game.tasks.LeisureTask;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What to do with time off, picked the way the character would: a curious one explores and goes to see places it
 * heard about, a sociable or loyal one checks on people it likes, a lazy one rests at home, and anyone strolls
 * around its base. What the character said about its day ("free day, going to see Steve") comes first.
 * Runs on the server thread; costs no AI calls.
 */
public final class Leisure {
    private Leisure() {}

    public record Pick(LeisureTask task, String why) {}

    private record Option(double weight, String why, java.util.function.Supplier<LeisureTask> make) {}

    @Nullable
    public static Pick choose(CompanionBrain brain, CompanionEntity c) {
        CompanionMemory m = brain.memory();
        PersonaProfile p = brain.profile();
        String dim = c.getWorld().getRegistryKey().getValue().toString();
        String note = (m.dayPlan.note == null ? "" : m.dayPlan.note).toLowerCase(Locale.ROOT);
        boolean freeDay = "free".equals(m.dayPlan.kind);
        boolean night = !c.getWorld().isDay();
        boolean timid = p.bravery() < 6;
        long now = System.currentTimeMillis();
        int linger = (freeDay ? 180 : 100) + (int) (Math.random() * 90);

        CompanionMemory.Location home = m.home();
        BlockPos homePos = home != null && dim.equals(home.dimension) ? new BlockPos(home.x, home.y, home.z) : null;
        List<Option> options = new ArrayList<>();

        // Rest: lazy characters, and anyone at night.
        double rest = 1.5 + (10 - p.diligence()) * 0.3 + (night ? 4 : 0) + (note.matches(".*\\b(rest|relax|nap|chill|lazy|sleep in)\\b.*") ? 6 : 0);
        BlockPos restAt = homePos != null ? homePos : c.getBlockPos();
        options.add(new Option(rest, homePos != null ? "resting at home" : "resting", () -> LeisureTask.rest(restAt, homePos != null ? "home" : "where it is", linger)));

        // Stroll around the base.
        double stroll = 3 + (note.matches(".*\\b(wander|walk|stroll|look around)\\b.*") ? 5 : 0);
        BlockPos strollAt = homePos != null && c.getBlockPos().getSquaredDistance(homePos) < 80 * 80 ? homePos : c.getBlockPos();
        options.add(new Option(stroll, "a stroll around " + (strollAt == homePos ? "home" : "here"),
                () -> LeisureTask.wander(strollAt, strollAt == homePos ? "home" : "here", linger)));

        // Check on people it likes (loyal characters: especially the one who brought it).
        if (c.getServer() != null) {
            for (ServerPlayerEntity pl : c.getServer().getPlayerManager().getPlayerList()) {
                if (pl.getWorld() != c.getWorld() || pl.isSpectator()) continue;
                String who = pl.getName().getString();
                double dist = Math.sqrt(pl.squaredDistanceTo(c));
                if (dist > 300) continue;
                int opinion = m.opinionOf(who);
                if (opinion <= -3) continue; // doesn't want to see them
                long since = now - m.lastVisited.getOrDefault(who.toLowerCase(Locale.ROOT), 0L);
                if (since < 15 * 60_000) continue;
                boolean owner = who.equalsIgnoreCase(c.getOwnerName());
                double w = 1 + p.sociability() * 0.3 + Math.max(0, opinion) * 0.4 + (owner ? p.loyalty() * 0.4 : 0);
                if (dist < 8) w *= 0.3; // already right there
                if (night && timid && dist > 24) w *= 0.2;
                if (note.contains(who.toLowerCase(Locale.ROOT))) w += 12;
                int seconds = (int) (dist / 3.5) + linger;
                options.add(new Option(w, "checking on " + who, () -> LeisureTask.checkOn(pl, seconds)));
            }
        }

        // Places it heard about, other people's bases, structures it found and hasn't looked at.
        for (Map.Entry<String, CompanionMemory.Location> e : m.places.entrySet()) {
            CompanionMemory.Location l = e.getValue();
            if (!dim.equals(l.dimension)) continue;
            String name = e.getKey();
            boolean interesting = l.type.equals("poi") || name.contains("'s ") && !l.type.equals("home");
            if (!interesting) continue;
            double dist = Math.sqrt(c.getBlockPos().getSquaredDistance(l.x, l.y == CompanionMemory.UNKNOWN_Y ? c.getY() : l.y, l.z));
            if (dist > 400 || dist < 6) continue;
            long since = now - l.visited;
            if (since < 24 * 60 * 60_000L && l.visited > 0) continue; // seen it lately
            double w = 1 + p.curiosity() * 0.45 + (l.visited == 0 ? 2 : 0) - dist / 150;
            if (night && timid) w *= 0.15;
            if (note.contains(name.toLowerCase(Locale.ROOT)) || note.contains("visit") && l.visited == 0) w += 8;
            if (w <= 0) continue;
            int seconds = (int) (dist / 3.5) + linger;
            BlockPos target = new BlockPos(l.x, l.y, l.z);
            options.add(new Option(w, "going to see " + name, () -> LeisureTask.visit(target, name, seconds)));
        }

        // Explore: curious (and not-too-timid-at-night) characters.
        double explore = p.curiosity() * 0.5 - 1 + (note.matches(".*\\b(explor\\w*|adventure|travel|see the world)\\b.*") ? 8 : 0);
        if (night && timid) explore *= 0.1;
        if (explore > 0) {
            Direction dir = Direction.Type.HORIZONTAL.random(c.getRandom());
            int distance = 32 + (int) (Math.random() * 48);
            options.add(new Option(explore, "exploring to the " + dir.asString(), () -> LeisureTask.explore(dir, distance, (int) (distance / 3.0) + linger)));
        }

        double total = 0;
        for (Option o : options) total += Math.max(0, o.weight());
        if (total <= 0) return null;
        double r = Math.random() * total;
        for (Option o : options) {
            r -= Math.max(0, o.weight());
            if (r <= 0) return new Pick(o.make().get(), o.why());
        }
        Option last = options.get(options.size() - 1);
        return new Pick(last.make().get(), last.why());
    }
}
