package dev.aicompanion.game.tasks;

import dev.aicompanion.entity.CompanionEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.gen.structure.Structure;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Time off, spent like a player would: strolling around, going to see a place, checking on someone, wandering off
 * to look at new land, or just resting at home. No AI calls while it runs; arrivals and discoveries are reported
 * to the character (who may say something), and a short account goes in the journal.
 */
public class LeisureTask extends Task {
    public enum Mode { WANDER, VISIT, CHECK_ON, EXPLORE, REST }

    private final Mode mode;
    private final String label;
    private final int durationTicks;
    @Nullable private final BlockPos anchor;
    @Nullable private final UUID playerId;
    @Nullable private final Direction direction;
    private final int distance;

    private BlockPos waypoint;
    private int pauseTicks;
    private int failures;
    private boolean arrived;
    private int legs;
    private final List<String> notes = new ArrayList<>();
    private String lastBiome = "";
    @Nullable private BlockPos exploreEnd;

    private LeisureTask(Mode mode, String label, int seconds, @Nullable BlockPos anchor, @Nullable UUID playerId, @Nullable Direction direction, int distance) {
        this.mode = mode;
        this.label = label;
        this.durationTicks = Math.max(20, seconds * 20);
        this.anchor = anchor;
        this.playerId = playerId;
        this.direction = direction;
        this.distance = distance;
    }

    /** Stroll around a spot (home, or wherever it is), stopping now and then to look around. */
    public static LeisureTask wander(BlockPos around, String where, int seconds) {
        return new LeisureTask(Mode.WANDER, where, seconds, around, null, null, 0);
    }

    /** Go to a place and look around there for a while. */
    public static LeisureTask visit(BlockPos place, String name, int seconds) {
        return new LeisureTask(Mode.VISIT, name, seconds, place, null, null, 0);
    }

    /** Go and see how a player is doing, and hang around near them for a bit. */
    public static LeisureTask checkOn(ServerPlayerEntity player, int seconds) {
        return new LeisureTask(Mode.CHECK_ON, player.getName().getString(), seconds, null, player.getUuid(), null, 0);
    }

    /** Head off in a direction to see what's out there. */
    public static LeisureTask explore(Direction direction, int distance, int seconds) {
        return new LeisureTask(Mode.EXPLORE, direction.asString(), seconds, null, null, direction, distance);
    }

    /** Stay put somewhere comfortable (home) and take it easy. */
    public static LeisureTask rest(BlockPos spot, String where, int seconds) {
        return new LeisureTask(Mode.REST, where, seconds, spot, null, null, 0);
    }

    public Mode mode() {
        return mode;
    }

    /** Who or what it's about: the player, the place, the direction, or where it's strolling/resting. */
    public String label() {
        return label;
    }

    @Override
    public String describe() {
        return switch (mode) {
            case WANDER -> "strolling around " + label;
            case VISIT -> "visiting " + label;
            case CHECK_ON -> "checking on " + label;
            case EXPLORE -> "exploring to the " + label;
            case REST -> "taking it easy at " + label;
        };
    }

    @Override
    protected boolean placesBlocksToGetAbout() {
        return false; // a stroll is no reason to build pillars and bridges
    }

    @Override
    public boolean isMajor() {
        return false; // time off isn't a big job; the check-in digest still mentions it
    }

    @Override
    protected Result step(CompanionEntity c) {
        if (ticks % 40 == 0) lookForNewThings(c);
        if (ticks > durationTicks) return finish(c, null);
        return switch (mode) {
            case WANDER -> strollAround(c, anchor, 12);
            case VISIT -> visitStep(c);
            case CHECK_ON -> checkOnStep(c);
            case EXPLORE -> exploreStep(c);
            case REST -> restStep(c);
        };
    }

    private Result visitStep(CompanionEntity c) {
        if (!arrived) {
            BlockPos target = surface(c.serverWorld(), anchor);
            Move m = approach(c, Vec3d.ofBottomCenter(target), 3.0);
            if (m == Move.FAILED) return finish(c, "couldn't find a way to " + label);
            if (m == Move.ARRIVED) {
                arrived = true;
                var brain = c.brain();
                if (brain != null) brain.onLeisureArrived("the place you call '" + label + "'", null, target);
            }
            return null;
        }
        return strollAround(c, anchor, 8);
    }

    private Result checkOnStep(CompanionEntity c) {
        ServerPlayerEntity p = playerId == null || c.getServer() == null ? null : c.getServer().getPlayerManager().getPlayer(playerId);
        if (p == null || p.getWorld() != c.getWorld()) return finish(c, label + " wasn't around");
        double d = c.distanceTo(p);
        if (!arrived) {
            Move m = approach(c, p.getPos(), 4.0);
            if (m == Move.FAILED) return finish(c, "couldn't get to " + label);
            if (m == Move.ARRIVED) {
                arrived = true;
                var brain = c.brain();
                if (brain != null) brain.onLeisureArrived(label, p, p.getBlockPos());
            }
            return null;
        }
        // Hang around nearby without crowding them; follow loosely if they move off.
        c.getLookControl().lookAt(p, 30, 30);
        if (d > 7) approach(c, p.getPos(), 4.0);
        else c.getNavigation().stop();
        return null;
    }

    private Result exploreStep(CompanionEntity c) {
        if (legs * 16 >= distance) {
            // Out as far as it meant to go: have a look around here for the rest of the time.
            if (exploreEnd == null) {
                exploreEnd = c.getBlockPos();
                waypoint = null;
            }
            return strollAround(c, exploreEnd, 10);
        }
        if (waypoint == null) {
            BlockPos from = c.getBlockPos();
            waypoint = surface(c.serverWorld(), from.offset(direction, 16).offset(direction.rotateYClockwise(), (int) (Math.random() * 9) - 4));
        }
        Move m = approach(c, Vec3d.ofBottomCenter(waypoint), 2.5);
        if (m == Move.ARRIVED) {
            legs++;
            waypoint = null;
            resetMovement();
        } else if (m == Move.FAILED) {
            if (++failures > 3) return finish(c, "the way " + label + " was blocked");
            waypoint = null;
            resetMovement();
        }
        return null;
    }

    private Result restStep(CompanionEntity c) {
        if (c.getBlockPos().getSquaredDistance(anchor) > 9 && !arrived) {
            Move m = approach(c, Vec3d.ofBottomCenter(anchor), 2.0);
            if (m == Move.FAILED) arrived = true; // rest wherever it is, then
            if (m == Move.ARRIVED) arrived = true;
            return null;
        }
        arrived = true;
        c.getNavigation().stop();
        if (ticks % 100 == 0) lookSomewhere(c);
        return null;
    }

    /** Walk to a random spot near `around`, pause and look about, repeat. */
    private Result strollAround(CompanionEntity c, @Nullable BlockPos around, int radius) {
        BlockPos center = around == null ? c.getBlockPos() : around;
        if (pauseTicks > 0) {
            pauseTicks--;
            if (pauseTicks % 50 == 0) lookSomewhere(c);
            return null;
        }
        if (waypoint == null) {
            double a = Math.random() * Math.PI * 2, r = 3 + Math.random() * (radius - 3);
            waypoint = surface(c.serverWorld(), center.add((int) (Math.cos(a) * r), 0, (int) (Math.sin(a) * r)));
        }
        Move m = approach(c, Vec3d.ofBottomCenter(waypoint), 1.5);
        if (m != Move.MOVING) {
            if (m == Move.FAILED && ++failures > 8) return finish(c, null);
            waypoint = null;
            resetMovement();
            pauseTicks = 60 + (int) (Math.random() * 200);
        }
        return null;
    }

    private static void lookSomewhere(CompanionEntity c) {
        List<LivingEntity> near = c.getWorld().getEntitiesByClass(LivingEntity.class, c.getBoundingBox().expand(10), e -> e != c && e.isAlive());
        if (!near.isEmpty() && Math.random() < 0.6) {
            c.getLookControl().lookAt(near.get((int) (Math.random() * near.size())), 30, 30);
        } else {
            double a = Math.random() * Math.PI * 2;
            c.getLookControl().lookAt(c.getX() + Math.cos(a) * 8, c.getEyeY() + Math.random() * 2 - 1, c.getZ() + Math.sin(a) * 8);
        }
    }

    /** New biomes and generated structures (villages, temples...) are worth remembering and mentioning. */
    private void lookForNewThings(CompanionEntity c) {
        var brain = c.brain();
        if (brain == null) return;
        ServerWorld world = c.serverWorld();
        BlockPos pos = c.getBlockPos();
        String biome = world.getBiome(pos).getKey().map(k -> k.getValue().getPath()).orElse("");
        if (!biome.isEmpty() && !biome.equals(lastBiome)) {
            lastBiome = biome;
            if (brain.memory().noteBiome(biome)) {
                String text = "a " + biome.replace('_', ' ') + " biome";
                notes.add("found " + text);
                brain.onDiscovery(text, pos, false);
            }
        }
        var accessor = world.getStructureAccessor();
        for (BlockPos probe : new BlockPos[]{pos, pos.add(24, 0, 0), pos.add(-24, 0, 0), pos.add(0, 0, 24), pos.add(0, 0, -24)}) {
            if (!world.isChunkLoaded(probe.getX() >> 4, probe.getZ() >> 4) || !accessor.hasStructureReferences(probe)) continue;
            for (Structure structure : accessor.getStructureReferences(probe).keySet()) {
                var start = accessor.getStructureAt(probe, structure);
                if (start == null || !start.hasChildren()) continue;
                Identifier id = world.getRegistryManager().get(RegistryKeys.STRUCTURE).getId(structure);
                if (id == null) continue;
                BlockPos center = start.getBoundingBox().getCenter();
                if (brain.memory().noteStructure(id.getPath(), world.getRegistryKey().getValue().toString(), center)) {
                    String text = "a " + id.getPath().replace('_', ' ');
                    notes.add("found " + text + " at " + center.getX() + ", " + center.getZ());
                    brain.onDiscovery(text, center, true);
                }
            }
        }
    }

    private Result finish(CompanionEntity c, @Nullable String problem) {
        StringBuilder sb = new StringBuilder(switch (mode) {
            case WANDER -> "Strolled around " + label;
            case VISIT -> arrived ? "Went to " + label + " and looked around" : "Set off for " + label;
            case CHECK_ON -> arrived ? "Went to see how " + label + " was doing" : "Went looking for " + label;
            case EXPLORE -> "Went exploring to the " + label + " (" + legs * 16 + " blocks out)";
            case REST -> "Took it easy at " + label;
        });
        if (!notes.isEmpty()) sb.append("; ").append(String.join("; ", notes));
        if (problem != null) sb.append(" (").append(problem).append(")");
        sb.append(".");
        return arrived || mode == Mode.WANDER || mode == Mode.EXPLORE && legs > 0 || problem == null ? ok(sb.toString()) : fail(sb.toString());
    }

    /** The standing spot at the surface above or below a column. */
    private static BlockPos surface(ServerWorld world, BlockPos p) {
        int top = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
        if (p.getY() == dev.aicompanion.ai.CompanionMemory.UNKNOWN_Y || Math.abs(top - p.getY()) > 12) return new BlockPos(p.getX(), top, p.getZ());
        // Inside a building or a cave: stay at its own level if there's standing room there.
        for (int dy = 0; dy <= 3; dy++) {
            for (int s : new int[]{dy, -dy}) {
                BlockPos q = p.up(s);
                if (world.getBlockState(q).getCollisionShape(world, q).isEmpty() && world.getBlockState(q.up()).getCollisionShape(world, q.up()).isEmpty()
                        && !world.getBlockState(q.down()).getCollisionShape(world, q.down()).isEmpty()) return q;
            }
        }
        return new BlockPos(p.getX(), top, p.getZ());
    }
}
