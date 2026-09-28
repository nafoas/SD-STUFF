package dev.aicompanion.ai;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import dev.aicompanion.AiCompanionMod;
import dev.aicompanion.entity.CompanionEntity;
import dev.aicompanion.game.Ids;
import dev.aicompanion.game.Recipes;
import dev.aicompanion.game.build.Architect;
import dev.aicompanion.game.build.BuildingModel;
import dev.aicompanion.game.build.BuildingScanner;
import dev.aicompanion.game.tasks.BuildTask;
import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Designs new buildings and extensions: asks Claude for design changes that fit the wish, the character and what's
 * already there, turns them into blocks by building rules, checks the result (reachable, not cutting into anyone's
 * build) and gives the designer one round to fix what's wrong, looking at a floor-by-floor map of the result.
 * The design is kept as a draft until build_plan builds it.
 */
public final class Designer {
    private Designer() {}

    public record PaletteChoice(
            @JsonPropertyDescription("wall, pillar, floor, ceiling, roof, window, light, door, fence or foundation") String role,
            @JsonPropertyDescription("A Minecraft block id. For roof: a material that has _stairs and _slab variants, e.g. spruce, dark_oak, stone_brick, cobblestone") String block) {}

    public record Change(
            @JsonPropertyDescription("room (a new room beside an existing one, or the first room of a new building), floor (a room on top of an existing room, with stairs up), balcony, or feature (furniture in an existing room)") String op,
            @JsonPropertyDescription("A short name for a new room so later changes in this list can attach to it, e.g. 'pantry'") String ref,
            @JsonPropertyDescription("What the new room is for: bedroom, storage, kitchen, workshop, living room, library, hall, greenhouse, ... (room and floor)") String purpose,
            @JsonPropertyDescription("The existing room (id like r2, or a ref from earlier in this list) it attaches to / goes above / goes on. Empty only for the first room of a new building") String attachTo,
            @JsonPropertyDescription("north, south, east or west: which side of attach_to the new room or balcony goes on") String side,
            @JsonPropertyDescription("Outer width in blocks including walls, along the wall it attaches to (4-15). Interior is 2 less") int width,
            @JsonPropertyDescription("Outer depth in blocks including walls, going away from it (4-15); for a balcony, how far it sticks out (2-4)") int depth,
            @JsonPropertyDescription("Inside height (2-6, usually 3)") int height,
            @JsonPropertyDescription("How a new room joins attach_to: door, opening (no door) or hallway") String connect,
            @JsonPropertyDescription("For connect=hallway: how many blocks long the hallway is (1-12)") int hallwayLength,
            @JsonPropertyDescription("Furniture and blocks to put in, e.g. bed, chest, chest, furnace, crafting_table, bookshelf, barrel, smoker, anvil, lantern. Empty for the usual things for its purpose") List<String> features) {}

    public record DesignPlan(
            @JsonPropertyDescription("A name for the building (keep the existing name when extending)") String name,
            @JsonPropertyDescription("gable or flat (new buildings only)") String roof,
            @JsonPropertyDescription("Materials, new buildings only (extensions match what's there); leave out roles you don't care about") List<PaletteChoice> palette,
            @JsonPropertyDescription("The design changes, in order") List<Change> changes,
            @JsonPropertyDescription("One or two sentences, in the character's spirit, on what this adds and why it fits") String summary) {}

    /** The result of realizing a plan: the updated model and everything known about building it. */
    private record Realized(BuildingModel model, Map<BlockPos, BlockState> placements, List<String> problems, String maps) {}

    // ------------------------------------------------------------------ plan_build

    /** Runs on the body thread (Claude calls are slow); world access hops to the server thread. */
    public static String design(CompanionBrain brain, String wish, @Nullable String buildingName, @Nullable BlockPos near) throws Actions.ActionError {
        CompanionEntity c = brain.entity();
        if (c == null) throw new Actions.ActionError("The body isn't in the world.");
        CompanionMemory mem = brain.memory();

        // What's there now.
        BuildingModel existing = onServer(c, () -> findOrScan(brain, c, buildingName));
        boolean isNew = existing == null;
        if (isNew && buildingName != null && !buildingName.isBlank() && !looksNew(buildingName) && mem.buildings.isEmpty()) {
            // They meant an extension, but there's nothing on record: say so rather than inventing a building.
            brain.debug("No building called " + buildingName + " known; designing a new one.");
        }
        String current = isNew ? "" : onServer(c, () -> existing.describe() + "\n" + Architect.floorMaps(c.serverWorld(), existing, Map.of()));

        String prompt = prompt(brain, wish, existing, current);
        DesignPlan plan;
        try {
            plan = ClaudeActionLayer.structured(prompt, DesignPlan.class);
        } catch (Exception e) {
            AiCompanionMod.LOGGER.warn("[{}] design call failed", brain.name(), e);
            throw new Actions.ActionError("Couldn't design it: " + e.getMessage());
        }
        if (plan == null || plan.changes() == null || plan.changes().isEmpty()) throw new Actions.ActionError("The design came back empty.");

        BlockPos nearPos = near;
        Realized r = onServer(c, () -> realize(brain, c, existing, plan, nearPos));
        if (r.problems().stream().anyMatch(p -> !p.startsWith("note:")) || added(r) == 0) {
            brain.debug("Design problems, asking for a fix: " + r.problems());
            String fix = prompt + "\n\nYour first attempt:\n" + describePlan(plan)
                    + "\nIt had these problems:\n- " + String.join("\n- ", r.problems())
                    + "\n\nThis is how it came out:\n" + r.maps()
                    + "\nReturn the corrected design (the full list of changes again, replacing the first attempt). "
                    + "Fix the problems by moving rooms to a free side, making them smaller, or dropping what doesn't fit.";
            try {
                DesignPlan fixed = ClaudeActionLayer.structured(fix, DesignPlan.class);
                if (fixed != null && fixed.changes() != null && !fixed.changes().isEmpty()) {
                    Realized second = onServer(c, () -> realize(brain, c, existing, fixed, nearPos));
                    if (better(second, r)) r = second;
                }
            } catch (Exception e) {
                AiCompanionMod.LOGGER.warn("[{}] design fix call failed", brain.name(), e);
            }
        }
        Realized done = r;
        if (done.model().rooms.stream().noneMatch(x -> !x.built) && done.model().balconies.stream().noneMatch(b -> !b.built)
                && done.model().links.stream().noneMatch(l -> !l.built)) {
            throw new Actions.ActionError("Nothing could be added: " + String.join("; ", done.problems()));
        }
        mem.drafts.put(done.model().name.toLowerCase(Locale.ROOT), done.model());
        brain.saveLater();
        brain.log("note", "Designed " + (isNew ? "a new building, " : "an extension to ") + done.model().name + ": "
                + (plan.summary() == null ? "" : plan.summary()), false);
        return onServer(c, () -> report(c, done, plan.summary()));
    }

    private static int added(Realized r) {
        return (int) (r.model().rooms.stream().filter(x -> !x.built).count() + r.model().balconies.stream().filter(b -> !b.built).count()
                + r.model().links.stream().filter(l -> !l.built).count());
    }

    /** The attempt that adds something beats one that adds nothing; then the one with fewer real problems. */
    private static boolean better(Realized a, Realized b) {
        boolean aAdds = added(a) > 0, bAdds = added(b) > 0;
        if (aAdds != bAdds) return aAdds;
        long pa = a.problems().stream().filter(p -> !p.startsWith("note:")).count(), pb = b.problems().stream().filter(p -> !p.startsWith("note:")).count();
        return pa <= pb;
    }

    private static boolean looksNew(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("new") || n.isBlank();
    }

    @Nullable
    private static BuildingModel findOrScan(CompanionBrain brain, CompanionEntity c, @Nullable String name) {
        CompanionMemory mem = brain.memory();
        // A design not built yet gets extended as a design.
        String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        boolean wantsNew = !n.isBlank() && looksNew(n);
        if (!wantsNew) {
            for (var e : mem.drafts.entrySet()) if (!n.isBlank() && e.getKey().contains(n)) return e.getValue().copy();
            boolean homeish = n.isBlank() || n.contains("home") || n.contains("house");
            if (homeish && mem.buildings.isEmpty() && !mem.drafts.isEmpty()) return mem.drafts.values().iterator().next().copy();
        }
        BuildingModel b = mem.building(name);
        if (b != null) {
            BuildingModel draft = mem.drafts.get(b.name.toLowerCase(Locale.ROOT));
            return (draft != null ? draft : b).copy();
        }
        if (name != null && !name.isBlank() && looksNew(name)) return null;
        // No model of it yet: if it's home (or a named place) we know, read it from the world.
        CompanionMemory.Location loc = null;
        if (name != null && !name.isBlank()) loc = mem.places.get(name.toLowerCase(Locale.ROOT));
        if (loc == null && (name == null || name.isBlank() || name.toLowerCase(Locale.ROOT).contains("home") || name.toLowerCase(Locale.ROOT).contains("house"))) loc = mem.home();
        if (loc == null || !loc.dimension.equals(c.getWorld().getRegistryKey().getValue().toString())) return null;
        BuildingModel scanned = BuildingScanner.scan(c.serverWorld(), new BlockPos(loc.x, loc.y, loc.z), name == null || name.isBlank() ? "home" : name);
        if (scanned == null) return null;
        mem.buildings.add(scanned);
        brain.log("note", "Took stock of " + scanned.name + ": " + scanned.rooms.size() + " rooms", false);
        return scanned.copy();
    }

    private static String prompt(CompanionBrain brain, String wish, @Nullable BuildingModel existing, String current) {
        PersonaProfile p = brain.profile();
        return "You are the architect for " + brain.name() + ", a character living in a Minecraft 1.20.1 world. Design what they want, the way they'd want it.\n\n"
                + "What they want: " + wish + "\n\n"
                + "About " + brain.name() + ": " + p.behaviorSummary() + "\nBuild style: " + p.buildStyle()
                + "\nFavourite blocks: " + String.join(", ", p.buildPalette() == null ? List.of() : p.buildPalette())
                + "\nPerfectionism " + p.perfectionism() + "/10, diligence " + p.diligence() + "/10.\n\n"
                + (existing == null
                ? "There's no building yet: design a new one. The first change must be a room with no attach_to; it's placed on good ground nearby, "
                + "its front door facing the way people come from. Attach the rest to it.\n\n"
                : "The existing building (" + existing.name + "):\n" + current + "\nAdd to it; don't redesign what's there. "
                + "New rooms attach to an existing room's outside wall (one that isn't shared with another room on that floor).\n\n")
                + """
                How designs work:
                - A room is a rectangle of walls; width and depth count the walls, so a 7x6 room is 5x4 inside. Rooms that touch share a wall.
                - room: goes on one side (north/south/east/west) of attach_to, centred on that wall, joined by a door, an opening or a hallway. \
                Hallways are 1 wide; use one to reach a room that shouldn't be right against the other, or to go around something.
                - floor: a room on top of attach_to with the same footprint, and a staircase up inside the lower room. \
                The lower room needs one inside length of at least its height + 3 (a height-3 room: 6 inside, so 8 including walls) for the stairs.
                - balcony: a railed platform outside a room on an outside wall, with a door onto it. Best on an upper floor.
                - feature: add furniture to an existing room.
                - Windows, lights, a roof, a foundation and the usual furniture for each room's purpose are added automatically. \
                Features override the usual furniture.
                Sizes: bedroom 6x6 to 8x8, storage 5x6 and up (it gets chests along the walls), kitchen or workshop 6x7, \
                living room or hall 8x8 to 11x9, hallways 2 to 5 long. Keep new buildings to 1 to 4 rooms; extensions to what the wish needs.
                Make it theirs: follow the wish and the character's taste and energy (a lazy character adds one small room; a perfectionist adds a hallway \
                and a balcony), vary sizes and sides so the building grows naturally instead of being a box, and use the land around it sensibly. \
                No templates: this should look like nobody else's house.
                """;
    }

    private static String describePlan(DesignPlan plan) {
        StringBuilder sb = new StringBuilder();
        for (Change ch : plan.changes()) {
            sb.append("- ").append(ch.op()).append(" ").append(ch.ref() == null ? "" : ch.ref()).append(" (").append(ch.purpose())
                    .append(") on the ").append(ch.side()).append(" of ").append(ch.attachTo()).append(", ").append(ch.width()).append("x").append(ch.depth())
                    .append(", ").append(ch.connect()).append("\n");
        }
        return sb.toString();
    }

    private static List<Architect.Op> ops(DesignPlan plan) {
        List<Architect.Op> ops = new ArrayList<>();
        for (Change ch : plan.changes()) {
            ops.add(new Architect.Op(ch.op(), ch.ref(), ch.purpose(), ch.attachTo(), ch.side(), ch.width(), ch.depth(), ch.height(),
                    ch.connect(), ch.hallwayLength(), ch.features() == null ? List.of() : ch.features()));
        }
        // At most six new rooms at a time.
        int rooms = 0;
        List<Architect.Op> capped = new ArrayList<>();
        for (Architect.Op op : ops) {
            boolean room = "room".equalsIgnoreCase(op.op()) || "floor".equalsIgnoreCase(op.op());
            if (room && ++rooms > 6) continue;
            capped.add(op);
        }
        return capped;
    }

    /** Server thread: applies the plan to a copy of the building, compiles it and checks it. */
    private static Realized realize(CompanionBrain brain, CompanionEntity c, @Nullable BuildingModel existing, DesignPlan plan, @Nullable BlockPos near) {
        List<String> problems = new ArrayList<>();
        BuildingModel m;
        if (existing != null) {
            m = existing.copy();
            int[] ctr = m.center();
            problems.addAll(Architect.apply(m, ops(plan), BlockPos.ORIGIN, Architect.sideToward(new BlockPos(ctr[0], ctr[1], ctr[2]), c.getBlockPos()), c.serverWorld()));
        } else {
            BuildingModel dry = new BuildingModel();
            Architect.apply(dry, ops(plan), new BlockPos(0, 64, 0), Direction.SOUTH, c.serverWorld());
            if (dry.rooms.isEmpty()) {
                problems.add("the first change must be a room with no attach_to");
                m = dry;
            } else {
                int[] b = dry.bounds();
                CompanionMemory.Location home = brain.memory().home();
                BlockPos around = near != null ? near : home != null && home.dimension.equals(c.getWorld().getRegistryKey().getValue().toString())
                        ? new BlockPos(home.x, home.y, home.z).add(0, 0, 0) : c.getBlockPos();
                // Not on top of home itself: look around it.
                BlockPos site = Architect.findSite(c, around, b[2] - b[0] + 1, b[3] - b[1] + 1, brain.memory().buildings);
                if (site == null) {
                    problems.add("no flat, free ground nearby for something " + (b[2] - b[0] + 1) + "x" + (b[3] - b[1] + 1) + "; make it smaller");
                    site = c.getBlockPos().down();
                }
                int cx = (b[0] + b[2]) / 2, cz = (b[1] + b[3]) / 2;
                // The floor goes on top of the ground (the ground is its foundation), like most players build.
                BlockPos first = new BlockPos(site.getX() - cx, site.getY() + 1, site.getZ() - cz);
                Direction entrance = Architect.sideToward(site, c.getBlockPos().equals(site) ? around : c.getBlockPos());
                m = new BuildingModel();
                m.name = plan.name() == null || plan.name().isBlank() ? "home" : plan.name();
                m.dimension = c.getWorld().getRegistryKey().getValue().toString();
                if ("flat".equalsIgnoreCase(plan.roof())) m.roof = "flat";
                Map<String, String> requested = new LinkedHashMap<>();
                if (plan.palette() != null) for (PaletteChoice pc : plan.palette()) if (pc.role() != null) requested.put(pc.role().toLowerCase(Locale.ROOT), pc.block());
                Architect.choosePalette(m, brain.profile(), requested);
                problems.addAll(Architect.apply(m, ops(plan), first, entrance, c.serverWorld()));
                // Avoid clashing names with its other buildings.
                String base = m.name;
                for (int i = 2; brain.memory().building(m.name) != null && brain.memory().building(m.name).name.equalsIgnoreCase(m.name); i++) m.name = base + " " + i;
            }
        }
        Map<BlockPos, BlockState> placements = Architect.compile(c.serverWorld(), m);
        problems.addAll(Architect.validate(c, m, placements));
        String maps = Architect.floorMaps(c.serverWorld(), m, placements);
        return new Realized(m, placements, problems, maps);
    }

    private static String report(CompanionEntity c, Realized r, @Nullable String summary) {
        BuildingModel m = r.model();
        List<BuildTask.Placement> list = placements(c, m);
        Map<Item, Integer> need = Architect.materials(c.serverWorld(), r.placements());
        Map<Item, Integer> missing = BuildTask.missingMaterials(c, list);
        StringBuilder sb = new StringBuilder("Designed " + m.name + " (" + list.size() + " block changes). " + (summary == null ? "" : summary) + "\n");
        sb.append(m.describe());
        sb.append("Materials: ");
        need.forEach((item, n) -> sb.append(n).append(" ").append(Ids.name(item)).append(", "));
        sb.setLength(sb.length() - 2);
        sb.append(".\n");
        if (c.fillerCount() < 8) sb.append("Bring 8 to 16 dirt or cobblestone too, to stand on while doing the roof.\n");
        long removals = r.placements().entrySet().stream().filter(e -> e.getValue().isAir() && !c.getWorld().getBlockState(e.getKey()).isAir()).count();
        boolean hasAxe = c.getInventory().containsAny(st -> st.getItem() instanceof net.minecraft.item.AxeItem);
        if (removals > 20 && !hasAxe) sb.append("About ").append(removals).append(" blocks come out first (like an old roof): an axe makes that much quicker.\n");
        if (missing.isEmpty()) sb.append("You have everything; call build_plan to build it.");
        else sb.append("Still missing: ").append(Recipes.describeMissing(c.serverWorld(), missing))
                .append(". Gather or craft them (fetch from storage first), then call build_plan. build_plan with skip_missing builds what it can now.");
        if (!r.problems().isEmpty()) sb.append("\nUnresolved: ").append(String.join("; ", r.problems()));
        return sb.toString();
    }

    // ------------------------------------------------------------------ build_plan

    /** The draft's blocks as build steps (air only where something is in the way). */
    public static List<BuildTask.Placement> placements(CompanionEntity c, BuildingModel draft) {
        Map<BlockPos, BlockState> compiled = Architect.compile(c.serverWorld(), draft);
        List<BuildTask.Placement> list = new ArrayList<>();
        compiled.forEach((pos, state) -> {
            BlockState now = c.getWorld().getBlockState(pos);
            if (state.isAir() ? now.isAir() : BuildTask.matches(now, state)) return;
            list.add(new BuildTask.Placement(pos, state));
        });
        return list;
    }

    /** Server thread: builds a draft. Marks it built once (nearly) everything is in place. */
    public static void build(CompanionBrain brain, CompanionEntity c, @Nullable String name, boolean skipMissing, java.util.concurrent.CompletableFuture<String> done) throws Actions.ActionError {
        CompanionMemory mem = brain.memory();
        BuildingModel draft = null;
        String key = null;
        if (name != null && !name.isBlank()) {
            key = name.toLowerCase(Locale.ROOT).trim();
            draft = mem.drafts.get(key);
            if (draft == null) for (var e : mem.drafts.entrySet()) if (e.getKey().contains(key)) { key = e.getKey(); draft = e.getValue(); }
        } else if (!mem.drafts.isEmpty()) {
            var e = mem.drafts.entrySet().iterator().next();
            key = e.getKey();
            draft = e.getValue();
        }
        if (draft == null) throw new Actions.ActionError("No design waiting to be built" + (name == null ? "" : " for " + name) + ". Use plan_build first.");
        if (c.getWorld().getRegistryKey().getValue().toString().equals(draft.dimension) == false && draft.dimension != null) {
            throw new Actions.ActionError("That building is in another dimension.");
        }
        List<BuildTask.Placement> list = placements(c, draft);
        if (list.isEmpty()) {
            finishDraft(brain, key, draft);
            done.complete("It's all built already.");
            return;
        }
        Map<Item, Integer> missing = BuildTask.missingMaterials(c, list);
        if (!missing.isEmpty() && !skipMissing) {
            throw new Actions.ActionError("Missing materials: " + Recipes.describeMissing(c.serverWorld(), missing)
                    + ". Gather or craft them first, or use skip_missing to build what you can now.");
        }
        String draftKey = key;
        BuildingModel d = draft;
        if (d.buildStarted == 0) {
            d.buildStarted = System.currentTimeMillis();
            brain.saveLater();
        }
        c.startTask(new BuildTask(d.name, d.name.toLowerCase(Locale.ROOT).contains("home") || d.name.toLowerCase(Locale.ROOT).contains("house") ? "home" : "other", list)
                .skipMissing(skipMissing)
                .phases(pl -> Architect.phase(d, pl.pos(), pl.state()))
                .timeLimitSeconds(8 * 60)
                .cleanupSince(d.buildStarted)
                .afterwards(body -> {
                    List<BuildTask.Placement> left = placements(body, d);
                    int wanted = Math.max(1, list.size());
                    if (left.size() <= 3) {
                        finishDraft(brain, draftKey, d);
                        return "The design for " + d.name + " is complete.";
                    }
                    return left.size() + " blocks of the design are still to do; build_plan again (after getting materials) to finish.";
                }), done);
    }

    private static void finishDraft(CompanionBrain brain, String key, BuildingModel draft) {
        CompanionMemory mem = brain.memory();
        draft.markBuilt();
        mem.drafts.remove(key);
        mem.buildings.removeIf(b -> b.name.equalsIgnoreCase(draft.name));
        mem.buildings.add(draft);
        if (draft.name.toLowerCase(Locale.ROOT).contains("home") || draft.name.toLowerCase(Locale.ROOT).contains("house") || mem.home() == null) {
            // Home is where the bed is, or the building's middle.
            int[] ctr = draft.center();
            for (BuildingModel.Room r : draft.rooms) if (r.purpose.contains("bed")) { ctr = new int[]{(r.x0 + r.x1) / 2, r.y + 1, (r.z0 + r.z1) / 2}; break; }
            if (mem.home() == null) {
                CompanionMemory.Location loc = new CompanionMemory.Location(draft.dimension, ctr[0], ctr[1], ctr[2]);
                loc.type = "home";
                loc.note = draft.name;
                mem.places.put("home", loc);
            }
        }
        brain.log("built", "Finished " + draft.name + ": " + draft.rooms.size() + " rooms", true);
        brain.saveLater();
    }

    // ------------------------------------------------------------------ scan_building

    /** Server thread: reads a building near a spot into rooms and remembers it. */
    public static String scan(CompanionBrain brain, CompanionEntity c, BlockPos at, String name) throws Actions.ActionError {
        BuildingModel m = BuildingScanner.scan(c.serverWorld(), at, name);
        if (m == null) throw new Actions.ActionError("Couldn't find an enclosed building there (stand inside it or right by its door).");
        brain.memory().buildings.removeIf(b -> b.name.equalsIgnoreCase(name));
        brain.memory().buildings.add(m);
        brain.saveLater();
        StringBuilder links = new StringBuilder();
        for (BuildingModel.Link l : m.links) links.append(l.type).append(" ").append(l.a).append("-").append(l.b).append("; ");
        return "Took stock of " + m.name + ":\n" + m.describe() + (links.isEmpty() ? "" : "Connections: " + links + "\n")
                + Architect.floorMaps(c.serverWorld(), m, Map.of());
    }

    // ------------------------------------------------------------------ helpers

    private static <T> T onServer(CompanionEntity c, Supplier<T> work) throws Actions.ActionError {
        MinecraftServer server = c.getServer();
        if (server == null) throw new Actions.ActionError("Server not available.");
        try {
            return server.submit(work).get(20, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            AiCompanionMod.LOGGER.warn("Design step failed", e.getCause());
            throw new Actions.ActionError("Design step failed: " + e.getCause());
        } catch (Exception e) {
            throw new Actions.ActionError("The server didn't respond: " + e);
        }
    }
}
