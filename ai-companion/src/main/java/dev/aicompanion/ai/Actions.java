package dev.aicompanion.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.aicompanion.ModConfig;
import dev.aicompanion.entity.CompanionEntity;
import dev.aicompanion.game.Ids;
import dev.aicompanion.game.Perception;
import dev.aicompanion.game.Recipes;
import dev.aicompanion.game.tasks.AttackTask;
import dev.aicompanion.game.tasks.BuildTask;
import dev.aicompanion.game.tasks.ChestTask;
import dev.aicompanion.game.tasks.CollectBlocksTask;
import dev.aicompanion.game.tasks.CraftTask;
import dev.aicompanion.game.tasks.DigTask;
import dev.aicompanion.game.tasks.FollowTask;
import dev.aicompanion.game.tasks.GiveTask;
import dev.aicompanion.game.tasks.GoToTask;
import dev.aicompanion.game.tasks.SmeltTask;
import dev.aicompanion.game.tasks.StayTask;
import dev.aicompanion.game.tasks.Task;
import dev.aicompanion.game.tasks.WaitTask;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** The game actions the action layer (Claude) can use, and how each one runs. */
public final class Actions {
    private Actions() {}

    public record Spec(String name, String description, Map<String, Object> properties, List<String> required) {}

    private static final int MAX_BUILD_BLOCKS = 2000;

    public static List<Spec> specs() {
        List<Spec> specs = new ArrayList<>();
        specs.add(new Spec("get_status", "Look around again: position, health, inventory, current activity, nearby players, creatures and notable blocks.", Map.of(), List.of()));
        specs.add(new Spec("follow_player", "Keep following a player around until another action replaces it.",
                Map.of("player", str("Player name"), "distance", num("How close to stay, in blocks (default 3)")), List.of("player")));
        specs.add(new Spec("go_to", "Walk somewhere: to coordinates, a remembered place, or a player. Give x/y/z, or place, or player.",
                Map.of("x", num("X"), "y", num("Y"), "z", num("Z"), "place", str("Name of a remembered place"), "player", str("Player name")), List.of()));
        specs.add(new Spec("stay_here", "Stay in the current spot (guarding it) until another action replaces it.", Map.of(), List.of()));
        specs.add(new Spec("collect_blocks", "Find, walk to and mine blocks that are exposed (surface, caves, tunnels) within " + ModConfig.get().searchRadius
                + " blocks. Automatically uses the best tool it carries. Mining stone gives cobblestone; iron_ore gives raw_iron. "
                + "Accepts block ids (oak_log, stone, iron_ore - matches deepslate variants too) or groups: logs, planks, leaves, sand, dirt, flowers.",
                Map.of("block", str("Block to mine"), "count", num("How many blocks")), List.of("block", "count")));
        specs.add(new Spec("dig", "Dig a 1x2 tunnel forward, or a staircase down or up, collecting everything broken. Use this to reach ores underground. Stops before lava or water.",
                Map.of("direction", enm("Which way to dig", "north", "south", "east", "west", "forward"),
                        "length", num("How many blocks (steps) to dig, max 64"),
                        "mode", enm("forward = level tunnel, down = staircase down (one block lower per step), up = staircase up", "forward", "down", "up")),
                List.of("direction", "length", "mode")));
        specs.add(new Spec("craft", "Craft an item. Works out the full recipe chain automatically (logs -> planks -> sticks -> tool) and uses or puts down a crafting table when needed. Reports exactly what is missing if materials are short.",
                Map.of("item", str("Item id, e.g. stone_pickaxe, torch, oak_door"), "count", num("How many")), List.of("item", "count")));
        specs.add(new Spec("smelt", "Smelt items in a furnace (uses a nearby one, or places one; needs fuel like coal or wood). Takes 10 seconds per item.",
                Map.of("item", str("Input item, e.g. raw_iron, sand, beef"), "count", num("How many")), List.of("item", "count")));
        specs.add(new Spec("place_block", "Place one block from the inventory at exact coordinates.",
                Map.of("block", str("Block id, optionally with state, e.g. torch or oak_stairs[facing=east]"), "x", num("X"), "y", num("Y"), "z", num("Z")),
                List.of("block", "x", "y", "z")));
        specs.add(new Spec("build", "Build a structure from boxes and single blocks, relative to an origin. Checks materials first and reports what is missing. "
                + "Boxes are filled cuboids (hollow=true gives walls/floor/ceiling only). Later entries overwrite earlier ones, so add doors/windows after walls; use block 'air' to carve openings. "
                + "dx = east(+)/west(-), dy = up, dz = south(+)/north(-). dy=0 is ground level where the companion stands.",
                Map.of("label", str("What this is, e.g. 'small spruce cabin'"),
                        "purpose", enm("What it's for (it's remembered as a place)", "home", "farm", "mine", "storage", "path", "decoration", "other"),
                        "origin_x", num("World X of the origin (default: 3 blocks in front of you)"),
                        "origin_y", num("World Y of the origin"),
                        "origin_z", num("World Z of the origin"),
                        "boxes", arr("Cuboids", Map.of("type", "object", "properties", Map.of(
                                "from", arr("[dx, dy, dz]", Map.of("type", "integer")),
                                "to", arr("[dx, dy, dz] (inclusive)", Map.of("type", "integer")),
                                "block", str("Block id with optional state"),
                                "hollow", Map.of("type", "boolean")), "required", List.of("from", "to", "block"))),
                        "blocks", arr("Single blocks", Map.of("type", "object", "properties", Map.of(
                                "dx", Map.of("type", "integer"), "dy", Map.of("type", "integer"), "dz", Map.of("type", "integer"),
                                "block", str("Block id with optional state")), "required", List.of("dx", "dy", "dz", "block")))),
                List.of("label")));
        specs.add(new Spec("give_items", "Walk to a player and hand them items from the inventory.",
                Map.of("player", str("Player name"), "item", str("Item id"), "count", num("How many")), List.of("player", "item", "count")));
        specs.add(new Spec("attack", "Hunt and fight creatures nearby. Target is a mob type (zombie, cow, skeleton), 'monsters' for any hostile mob, or a player name.",
                Map.of("target", str("What to attack"), "count", num("How many to defeat (default 1)")), List.of("target")));
        specs.add(new Spec("chest", "Store items in, or take items from, a chest or barrel. Without coordinates: deposits go into the nearest chest of your own; "
                + "withdrawals use the chest memory says holds the item, else the nearest one you may take from. You may only take from your own chests, "
                + "unlooted natural ones, or those of players who said you could. Every chest you use is remembered with its contents.",
                Map.of("action", enm("deposit or withdraw", "deposit", "withdraw"), "item", str("Item id (omit for everything)"), "count", num("How many (omit for all)"),
                        "x", num("Chest X (optional)"), "y", num("Chest Y"), "z", num("Chest Z"), "label", str("Optional label for this chest, e.g. 'ores', 'food', 'tools'")),
                List.of("action")));
        specs.add(new Spec("recall", "Look things up in memory without walking anywhere: which chests hold an item, known places, resources you've seen and where, and things you built. "
                + "Give an item/resource name to search for it, or omit to get an overview.",
                Map.of("query", str("Item, resource or place to look for (optional)")), List.of()));
        specs.add(new Spec("inspect_build", "Take a good look at a build: walks over if needed and reports its size, materials and what's in it, "
                + "so the character can have an opinion. Target a player (their build around them) or coordinates.",
                Map.of("player", str("Look at the build around this player"), "x", num("X"), "y", num("Y"), "z", num("Z")), List.of()));
        specs.add(new Spec("allow_chest_access", "Record that a player said you may (or may no longer) take things from their chests. Only when they clearly said so.",
                Map.of("player", str("Player name"), "allowed", Map.of("type", "boolean")), List.of("player", "allowed")));
        specs.add(new Spec("equip", "Hold an item from the inventory in the main hand.", Map.of("item", str("Item id")), List.of("item")));
        specs.add(new Spec("wait", "Do nothing for a while.", Map.of("seconds", num("How long, max 300")), List.of("seconds")));
        specs.add(new Spec("remember_place", "Remember the current position under a name to go back later, with what kind of place it is.",
                Map.of("name", str("Name for this place, e.g. home, iron mine, wheat farm"),
                        "type", enm("Kind of place", "home", "farm", "mine", "storage", "path", "other"),
                        "note", str("Anything worth remembering about it (optional)")), List.of("name")));
        specs.add(new Spec("adjust_opinion", "Record that the character's feelings about a player changed, when the character's reply clearly shows it.",
                Map.of("player", str("Player name"), "change", num("-3 to 3"), "reason", str("Why")), List.of("player", "change")));
        specs.add(new Spec("stop", "Stop the current activity and stand still.", Map.of(), List.of()));
        return specs;
    }

    private static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> num(String description) {
        return Map.of("type", "integer", "description", description);
    }

    private static Map<String, Object> enm(String description, String... values) {
        return Map.of("type", "string", "description", description, "enum", List.of(values));
    }

    private static Map<String, Object> arr(String description, Map<String, Object> items) {
        return Map.of("type", "array", "description", description, "items", items);
    }

    // ------------------------------------------------------------------ running actions

    /** A failure to report back to the model as a tool error. */
    public static class ActionError extends Exception {
        public ActionError(String message) {
            super(message);
        }
    }

    /**
     * Runs an action for the companion and waits for it to finish. Called from the brain thread; all world
     * access happens on the server thread. Returns the result text for the model.
     */
    public static String run(CompanionBrain brain, String name, JsonObject input, BooleanSupplier cancelled) throws ActionError {
        CompanionEntity c = brain.entity();
        if (c == null || !c.isAlive()) throw new ActionError("The companion's body isn't in the world right now.");
        MinecraftServer server = c.getServer();
        if (server == null) throw new ActionError("Server not available.");

        CompletableFuture<CompletableFuture<String>> started = new CompletableFuture<>();
        server.execute(() -> {
            try {
                started.complete(begin(brain, c, name, input));
            } catch (ActionError e) {
                started.completeExceptionally(e);
            } catch (Exception e) {
                started.completeExceptionally(new ActionError("Error: " + e));
            }
        });
        try {
            CompletableFuture<String> result = started.get(10, TimeUnit.SECONDS);
            long deadline = System.currentTimeMillis() + 10 * 60 * 1000;
            while (true) {
                if (cancelled.getAsBoolean()) {
                    server.execute(() -> c.cancelTask("the character changed their mind"));
                    return "Cancelled.";
                }
                try {
                    return result.get(500, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    if (System.currentTimeMillis() > deadline) {
                        server.execute(() -> c.cancelTask("took too long"));
                        return "Took too long and was stopped.";
                    }
                }
            }
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof ActionError ae) throw ae;
            throw new ActionError(String.valueOf(e.getCause()));
        } catch (TimeoutException e) {
            throw new ActionError("The server didn't respond.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ActionError("Interrupted.");
        }
    }

    /** Runs on the server thread: validates input and starts the action. Returns a future with the outcome. */
    private static CompletableFuture<String> begin(CompanionBrain brain, CompanionEntity c, String name, JsonObject in) throws ActionError {
        CompletableFuture<String> done = new CompletableFuture<>();
        switch (name) {
            case "get_status" -> done.complete(Perception.describe(c, brain.memory()));
            case "stop" -> {
                c.cancelTask("told to stop");
                c.getNavigation().stop();
                done.complete("Stopped.");
            }
            case "remember_place" -> {
                String place = string(in, "name");
                BlockPos p = c.getBlockPos();
                CompanionMemory.Location loc = new CompanionMemory.Location(c.getWorld().getRegistryKey().getValue().toString(), p.getX(), p.getY(), p.getZ());
                if (in.has("type")) loc.type = in.get("type").getAsString();
                if (in.has("note")) loc.note = in.get("note").getAsString();
                brain.memory().places.put(place.toLowerCase(), loc);
                brain.log("note", "Remembered " + place + " (" + loc.type + ") at " + p.toShortString(), false);
                done.complete("Remembered this spot as '" + place + "' (" + loc.type + ").");
            }
            case "adjust_opinion" -> {
                String player = string(in, "player");
                int change = Math.max(-3, Math.min(3, integer(in, "change", 0)));
                int now = brain.memory().adjustOpinion(player, change);
                brain.memory().addEvent("Feelings about " + player + " changed by " + change + (in.has("reason") ? " (" + in.get("reason").getAsString() + ")" : ""));
                brain.saveLater();
                done.complete("Opinion of " + player + " is now " + now + " (" + CompanionMemory.describeOpinion(now) + ").");
            }
            case "equip" -> {
                Item item = item(in, "item");
                for (int i = 0; i < c.getInventory().size(); i++) {
                    if (c.getInventory().getStack(i).isOf(item)) {
                        c.hold(c.getInventory().getStack(i));
                        done.complete("Now holding " + Ids.name(item) + ".");
                        return done;
                    }
                }
                if (c.getMainHandStack().isOf(item)) done.complete("Already holding it.");
                else throw new ActionError("Doesn't have any " + Ids.name(item) + ".");
            }
            case "follow_player" -> {
                ServerPlayerEntity p = player(c, string(in, "player"));
                c.startTask(new FollowTask(p.getUuid(), p.getName().getString(), Math.max(2, integer(in, "distance", 3))), done);
            }
            case "go_to" -> {
                Vec3d target;
                String label;
                if (in.has("player")) {
                    ServerPlayerEntity p = player(c, string(in, "player"));
                    target = p.getPos();
                    label = p.getName().getString();
                } else if (in.has("place")) {
                    String place = string(in, "place").toLowerCase();
                    CompanionMemory.Location loc = brain.memory().places.get(place);
                    if (loc == null) throw new ActionError("No remembered place called '" + place + "'. Known: " + String.join(", ", brain.memory().places.keySet()));
                    if (!loc.dimension.equals(c.getWorld().getRegistryKey().getValue().toString())) throw new ActionError(place + " is in another dimension.");
                    int y = loc.y == CompanionMemory.UNKNOWN_Y ? c.getWorld().getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, loc.x, loc.z) : loc.y;
                    target = new Vec3d(loc.x + 0.5, y, loc.z + 0.5);
                    label = place;
                } else if (in.has("x") && in.has("z")) {
                    int x = integer(in, "x", 0), z = integer(in, "z", 0);
                    int y = in.has("y") ? integer(in, "y", 64) : c.getWorld().getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                    target = new Vec3d(x + 0.5, y, z + 0.5);
                    label = x + ", " + y + ", " + z;
                } else {
                    throw new ActionError("Give x/y/z, place, or player.");
                }
                c.startTask(new GoToTask(target, label, 2.0), done);
            }
            case "stay_here" -> c.startTask(new StayTask(c.getBlockPos()), done);
            case "wait" -> c.startTask(new WaitTask(integer(in, "seconds", 10)), done);
            case "collect_blocks" -> {
                String block = string(in, "block");
                Predicate<BlockState> matcher = Ids.blockMatcher(block).orElseThrow(() -> new ActionError("Unknown block '" + block + "'."));
                c.startTask(new CollectBlocksTask(Ids.normalize(block), matcher, integer(in, "count", 1), ModConfig.get().searchRadius), done);
            }
            case "dig" -> {
                String dir = string(in, "direction").toLowerCase();
                Direction direction = dir.equals("forward") ? c.getHorizontalFacing() : Direction.byName(dir);
                if (direction == null || direction.getAxis().isVertical()) throw new ActionError("Direction must be north, south, east, west or forward.");
                DigTask.Mode mode = switch (in.has("mode") ? in.get("mode").getAsString() : "forward") {
                    case "down" -> DigTask.Mode.DOWN;
                    case "up" -> DigTask.Mode.UP;
                    default -> DigTask.Mode.FORWARD;
                };
                c.startTask(new DigTask(direction, integer(in, "length", 8), mode), done);
            }
            case "craft" -> c.startTask(new CraftTask(item(in, "item"), integer(in, "count", 1)), done);
            case "smelt" -> c.startTask(new SmeltTask(item(in, "item"), integer(in, "count", 1)), done);
            case "place_block" -> {
                BlockState state = state(string(in, "block"));
                BlockPos pos = new BlockPos(integer(in, "x", 0), integer(in, "y", 0), integer(in, "z", 0));
                if (c.count(state.getBlock().asItem()) < 1) throw new ActionError("Doesn't have any " + Ids.name(state.getBlock().asItem()) + ".");
                c.startTask(new BuildTask(Ids.name(state.getBlock()), "", List.of(new BuildTask.Placement(pos, state))), done);
            }
            case "build" -> {
                List<BuildTask.Placement> placements = blueprint(c, in);
                Map<Item, Integer> missing = BuildTask.missingMaterials(c, placements);
                if (!missing.isEmpty()) {
                    throw new ActionError("Not enough materials for " + placements.size() + " blocks. Missing: " + Recipes.describeMissing(c.serverWorld(), missing)
                            + ". Gather or craft them first (or build something smaller / with materials you have).");
                }
                c.startTask(new BuildTask(in.has("label") ? in.get("label").getAsString() : "a structure",
                        in.has("purpose") ? in.get("purpose").getAsString() : "other", placements), done);
            }
            case "give_items" -> {
                ServerPlayerEntity p = player(c, string(in, "player"));
                Item item = item(in, "item");
                if (c.count(item) < 1) throw new ActionError("Doesn't have any " + Ids.name(item) + ".");
                c.startTask(new GiveTask(p.getUuid(), p.getName().getString(), item, integer(in, "count", 1)), done);
            }
            case "attack" -> {
                String target = string(in, "target").trim();
                ServerPlayerEntity p = c.getServer().getPlayerManager().getPlayer(target);
                Predicate<LivingEntity> matcher;
                if (p != null) {
                    if (!ModConfig.get().allowPvp) throw new ActionError("Attacking players is disabled on this server.");
                    matcher = e -> e == p;
                } else if (target.equalsIgnoreCase("monsters") || target.equalsIgnoreCase("hostile") || target.equalsIgnoreCase("mobs")) {
                    matcher = e -> e instanceof Monster;
                } else {
                    Identifier id = Ids.id(target).filter(Registries.ENTITY_TYPE::containsId)
                            .or(() -> target.endsWith("s") ? Ids.id(target.substring(0, target.length() - 1)).filter(Registries.ENTITY_TYPE::containsId) : Optional.empty())
                            .orElseThrow(() -> new ActionError("Unknown creature '" + target + "'."));
                    EntityType<?> type = Registries.ENTITY_TYPE.get(id);
                    if (type == EntityType.PLAYER) throw new ActionError("Name a specific player.");
                    matcher = e -> e.getType() == type && !(e instanceof PlayerEntity);
                }
                c.startTask(new AttackTask(target, matcher, integer(in, "count", 1)), done);
            }
            case "chest" -> {
                boolean deposit = !"withdraw".equals(string(in, "action"));
                Item item = in.has("item") && !in.get("item").getAsString().isBlank() ? item(in, "item") : null;
                BlockPos at = in.has("x") && in.has("y") && in.has("z") ? new BlockPos(integer(in, "x", 0), integer(in, "y", 0), integer(in, "z", 0)) : null;
                c.startTask(new ChestTask(deposit, item, integer(in, "count", 0), at, in.has("label") ? in.get("label").getAsString() : ""), done);
            }
            case "inspect_build" -> {
                BlockPos at;
                if (in.has("player")) at = player(c, string(in, "player")).getBlockPos();
                else if (in.has("x") && in.has("z")) at = new BlockPos(integer(in, "x", 0), integer(in, "y", c.getBlockY()), integer(in, "z", 0));
                else at = c.getBlockPos();
                BlockPos target = at;
                c.startTask(new dev.aicompanion.game.tasks.InspectTask(target), done);
            }
            case "recall" -> done.complete(MemoryReport.recall(brain.memory(), c, in.has("query") ? in.get("query").getAsString() : ""));
            case "allow_chest_access" -> {
                String player = string(in, "player");
                boolean allowed = !in.has("allowed") || in.get("allowed").getAsBoolean();
                if (allowed) brain.memory().chestPermissions.add(player.toLowerCase());
                else brain.memory().chestPermissions.remove(player.toLowerCase());
                brain.log("note", player + (allowed ? " said you may use their chests" : " no longer lets you use their chests"), false);
                done.complete("Noted.");
            }
            default -> throw new ActionError("Unknown action " + name);
        }
        return done;
    }

    private static List<BuildTask.Placement> blueprint(CompanionEntity c, JsonObject in) throws ActionError {
        BlockPos origin;
        if (in.has("origin_x") && in.has("origin_z")) {
            origin = new BlockPos(integer(in, "origin_x", 0), in.has("origin_y") ? integer(in, "origin_y", 0) : c.getBlockY(), integer(in, "origin_z", 0));
        } else {
            origin = c.getBlockPos().offset(c.getHorizontalFacing(), 3);
        }
        Map<BlockPos, BlockState> result = new LinkedHashMap<>();
        if (in.has("boxes")) {
            for (JsonElement el : in.getAsJsonArray("boxes")) {
                JsonObject box = el.getAsJsonObject();
                int[] from = triple(box, "from");
                int[] to = triple(box, "to");
                BlockState state = state(box.get("block").getAsString());
                boolean hollow = box.has("hollow") && box.get("hollow").getAsBoolean();
                int x0 = Math.min(from[0], to[0]), x1 = Math.max(from[0], to[0]);
                int y0 = Math.min(from[1], to[1]), y1 = Math.max(from[1], to[1]);
                int z0 = Math.min(from[2], to[2]), z1 = Math.max(from[2], to[2]);
                if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1) > MAX_BUILD_BLOCKS * 4L) throw new ActionError("A box is far too big.");
                for (int x = x0; x <= x1; x++)
                    for (int y = y0; y <= y1; y++)
                        for (int z = z0; z <= z1; z++) {
                            boolean edge = x == x0 || x == x1 || y == y0 || y == y1 || z == z0 || z == z1;
                            if (!hollow || edge) result.put(origin.add(x, y, z), state);
                        }
            }
        }
        if (in.has("blocks")) {
            for (JsonElement el : in.getAsJsonArray("blocks")) {
                JsonObject b = el.getAsJsonObject();
                result.put(origin.add(integer(b, "dx", 0), integer(b, "dy", 0), integer(b, "dz", 0)), state(b.get("block").getAsString()));
            }
        }
        if (result.isEmpty()) throw new ActionError("The blueprint has no blocks.");
        if (result.size() > MAX_BUILD_BLOCKS) throw new ActionError("Too many blocks (" + result.size() + "); max " + MAX_BUILD_BLOCKS + " per build. Split it up.");
        List<BuildTask.Placement> list = new ArrayList<>();
        result.forEach((pos, state) -> {
            if (!(state.isAir() && c.getWorld().getBlockState(pos).isAir())) list.add(new BuildTask.Placement(pos, state));
        });
        return list;
    }

    private static int[] triple(JsonObject o, String key) throws ActionError {
        if (!o.has(key) || !o.get(key).isJsonArray()) throw new ActionError("Box needs '" + key + "' as [dx, dy, dz].");
        JsonArray a = o.getAsJsonArray(key);
        if (a.size() != 3) throw new ActionError("'" + key + "' must have 3 numbers.");
        return new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()};
    }

    private static BlockState state(String text) throws ActionError {
        String t = text.trim();
        if (t.equalsIgnoreCase("air")) return Blocks.AIR.getDefaultState();
        Optional<BlockState> s = Ids.blockState(t);
        if (s.isEmpty()) throw new ActionError("Unknown block '" + text + "'.");
        if (!s.get().isAir() && s.get().getBlock().asItem() == net.minecraft.item.Items.AIR) throw new ActionError(text + " can't be placed from an item.");
        return s.get();
    }

    private static String string(JsonObject in, String key) throws ActionError {
        if (!in.has(key) || in.get(key).isJsonNull()) throw new ActionError("Missing '" + key + "'.");
        return in.get(key).getAsString();
    }

    private static int integer(JsonObject in, String key, int fallback) {
        try {
            return in.has(key) && !in.get(key).isJsonNull() ? (int) Math.round(in.get(key).getAsDouble()) : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static Item item(JsonObject in, String key) throws ActionError {
        String name = string(in, key);
        return Ids.item(name).orElseThrow(() -> new ActionError("Unknown item '" + name + "'."));
    }

    private static ServerPlayerEntity player(CompanionEntity c, String name) throws ActionError {
        ServerPlayerEntity p = c.getServer().getPlayerManager().getPlayer(name.trim());
        if (p == null) throw new ActionError("No player called " + name + " is online.");
        if (p.getWorld() != c.getWorld()) throw new ActionError(name + " is in another dimension.");
        return p;
    }
}
