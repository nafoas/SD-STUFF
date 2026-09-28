package dev.aicompanion.world;

import dev.aicompanion.entity.CompanionEntity;
import dev.aicompanion.mixin.LootableContainerAccessor;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.CropBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.SaplingBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Decides which blocks a companion may break, so it never wrecks anyone's home or build but can still deal with
 * player-placed junk (a dirt wall someone trapped it with, a stray pillar, trees grown from planted saplings).
 * Naturally generated structures (villages, temples...) are fair game unless a player has moved in.
 */
public final class BreakPolicy {
    private BreakPolicy() {}

    public enum Purpose {
        /** Gathering resources, digging, clearing a build site. */
        GATHER,
        /** Harvesting a mature crop (must be replanted). */
        HARVEST,
        /** Trapped with no other way out: may break one ordinary block (and should put it back). */
        ESCAPE,
        /** Getting about: clearing the way (its own builds are left alone, only its own junk and terrain go). */
        MOVE
    }

    /** Null means allowed; otherwise a short reason it isn't. */
    @Nullable
    public static String check(CompanionEntity c, BlockPos pos, Purpose purpose) {
        ServerWorld world = c.serverWorld();
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return null;
        if (state.getHardness(world, pos) < 0) return "unbreakable";

        String self = BlockOwnership.companionOwner(c.getCharacterId());
        String owner = BlockOwnership.get(world).owner(pos);
        if (self.equals(owner)) {
            // Its own block. Fine to take, except that walking somewhere is no reason to dig through its own house.
            if (purpose == Purpose.MOVE) {
                BuildAwareness.Kind kind = BuildAwareness.classify(world, pos).kind();
                if (kind == BuildAwareness.Kind.BUILD || kind == BuildAwareness.Kind.HOME) return "it's part of your own build";
            }
            return null;
        }

        if (state.getBlock() instanceof CropBlock crop) {
            return crop.isMature(state) ? null : "the crop isn't ripe yet";
        }
        if (state.getBlock() instanceof SaplingBlock && owner != null) return "someone planted that sapling";

        BuildAwareness.Verdict build = BuildAwareness.classify(world, pos);
        if (build.kind() == BuildAwareness.Kind.NONE && owner == null && BuildAwareness.inGeneratedStructure(world, pos)) {
            return null; // naturally generated structure nobody has moved into
        }
        if (isValuable(state, world, pos)) return "it's someone's " + describe(state);
        // Trapped with no way out: one ordinary block may go (the pathfinder puts it back afterwards).
        if (purpose == Purpose.ESCAPE) return null;
        if (build.protectedFrom(self)) {
            String whose = build.owner() == null ? "someone's" : displayOwner(build.owner()) + "'s";
            return build.kind() == BuildAwareness.Kind.HOME ? "it's part of " + whose + " home" : "it's part of " + whose + " build";
        }
        return null; // natural terrain, or a few stray blocks (a pillar, a wall someone trapped it with)
    }

    private static String displayOwner(String owner) {
        return owner.startsWith(BlockOwnership.COMPANION_PREFIX) ? "another companion" : owner;
    }

    public static boolean allowed(CompanionEntity c, BlockPos pos, Purpose purpose) {
        return check(c, pos, purpose) == null;
    }

    /** Containers, beds, doors and workstations are never broken unless the companion placed them. */
    public static boolean isValuable(BlockState state, ServerWorld world, BlockPos pos) {
        if (state.getBlock() instanceof BedBlock || state.getBlock() instanceof DoorBlock) return true;
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof Inventory) return true;
        String id = Registries.BLOCK.getId(state.getBlock()).getPath();
        return id.contains("crafting_table") || id.contains("anvil") || id.contains("enchanting") || id.contains("beacon")
                || id.contains("brewing") || id.contains("lectern") || id.contains("respawn_anchor") || id.contains("spawner")
                || id.endsWith("_bed") || id.contains("jukebox") || id.contains("bell");
    }

    // ------------------------------------------------------------------ containers

    /**
     * Whether the companion may take items out of a container. Putting things in is always fine.
     * Allowed: its own containers, unlooted natural loot chests, and containers of players who said it may use them
     * (or anyone's when the "mean" mischief level is enabled).
     */
    @Nullable
    public static String checkTake(CompanionEntity c, BlockPos pos, boolean playerGavePermission, boolean mischiefAllowsStealing) {
        ServerWorld world = c.serverWorld();
        String owner = BlockOwnership.get(world).owner(pos);
        if (BlockOwnership.companionOwner(c.getCharacterId()).equals(owner)) return null;
        BlockEntity be = world.getBlockEntity(pos);
        if (owner == null && be instanceof LootableContainerBlockEntity loot && ((LootableContainerAccessor) loot).aiCompanion$getLootTableId() != null) {
            return null; // unlooted dungeon / village chest
        }
        if (playerGavePermission || mischiefAllowsStealing) return null;
        return owner == null ? "it isn't yours" : "it belongs to " + owner;
    }

    private static String describe(BlockState state) {
        return state.getBlock().getName().getString().toLowerCase();
    }
}
