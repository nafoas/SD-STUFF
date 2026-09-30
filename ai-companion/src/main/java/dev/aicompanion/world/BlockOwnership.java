package dev.aicompanion.world;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers who placed which blocks in a world: players (by name) and companions (by "companion:<id>").
 * Blocks nobody is recorded for count as natural. Saved with the world.
 */
public class BlockOwnership extends PersistentState {
    private static final String ID = "ai_companion_block_owners";
    public static final String COMPANION_PREFIX = "companion:";

    private final Long2IntOpenHashMap owners = new Long2IntOpenHashMap();
    /** Which block was placed, so a record is ignored once something else is there (explosions, /fill, pistons...). */
    private final Long2IntOpenHashMap placedBlock = new Long2IntOpenHashMap();
    @Nullable private transient ServerWorld world;
    private final List<String> names = new ArrayList<>();
    private final Map<String, Integer> nameIndex = new HashMap<>();

    public BlockOwnership() {
        owners.defaultReturnValue(-1);
    }

    public static BlockOwnership get(ServerWorld world) {
        BlockOwnership state = world.getPersistentStateManager().getOrCreate(BlockOwnership::fromNbt, BlockOwnership::new, ID);
        state.world = world;
        return state;
    }

    public static String companionOwner(String characterId) {
        return COMPANION_PREFIX + characterId;
    }

    public synchronized void set(BlockPos pos, String owner) {
        Integer idx = nameIndex.get(owner);
        if (idx == null) {
            idx = names.size();
            names.add(owner);
            nameIndex.put(owner, idx);
        }
        owners.put(pos.asLong(), (int) idx);
        if (world != null) placedBlock.put(pos.asLong(), Registries.BLOCK.getRawId(world.getBlockState(pos).getBlock()));
        markDirty();
    }

    public synchronized void clear(BlockPos pos) {
        placedBlock.remove(pos.asLong());
        if (owners.remove(pos.asLong()) != -1) markDirty();
    }

    @Nullable
    public synchronized String owner(BlockPos pos) {
        int idx = owners.get(pos.asLong());
        if (idx < 0) return null;
        if (world != null && placedBlock.containsKey(pos.asLong())
                && placedBlock.get(pos.asLong()) != Registries.BLOCK.getRawId(world.getBlockState(pos).getBlock())) {
            // Something else is there now: the record is stale.
            owners.remove(pos.asLong());
            placedBlock.remove(pos.asLong());
            markDirty();
            return null;
        }
        return names.get(idx);
    }

    /** Who placed a block that has just been broken (checked against what was there, not the air left behind). */
    @Nullable
    public synchronized String ownerOfBroken(BlockPos pos, net.minecraft.block.BlockState was) {
        int idx = owners.get(pos.asLong());
        if (idx < 0) return null;
        if (placedBlock.containsKey(pos.asLong()) && placedBlock.get(pos.asLong()) != Registries.BLOCK.getRawId(was.getBlock())) return null;
        return names.get(idx);
    }

    public boolean isPlayerPlaced(BlockPos pos) {
        String o = owner(pos);
        return o != null && !o.startsWith(COMPANION_PREFIX);
    }

    /** Counts player-placed blocks in a cube around pos. */
    public synchronized int playerPlacedNear(BlockPos pos, int radius) {
        int n = 0;
        for (BlockPos p : BlockPos.iterate(pos.add(-radius, -radius, -radius), pos.add(radius, radius, radius))) {
            String o = owner(p);
            if (o != null && !o.startsWith(COMPANION_PREFIX)) n++;
        }
        return n;
    }

    @Override
    public synchronized NbtCompound writeNbt(NbtCompound nbt) {
        NbtList nameList = new NbtList();
        for (String n : names) nameList.add(NbtString.of(n));
        long[] positions = new long[owners.size()];
        int[] indexes = new int[owners.size()];
        int i = 0;
        for (Long2IntOpenHashMap.Entry e : owners.long2IntEntrySet()) {
            positions[i] = e.getLongKey();
            indexes[i] = e.getIntValue();
            i++;
        }
        nbt.put("Names", nameList);
        nbt.putLongArray("Positions", positions);
        nbt.putIntArray("Owners", indexes);
        int[] blocks = new int[positions.length];
        for (int j = 0; j < positions.length; j++) blocks[j] = placedBlock.containsKey(positions[j]) ? placedBlock.get(positions[j]) : -1;
        nbt.putIntArray("Blocks", blocks);
        return nbt;
    }

    public static BlockOwnership fromNbt(NbtCompound nbt) {
        BlockOwnership state = new BlockOwnership();
        NbtList nameList = nbt.getList("Names", 8);
        for (int i = 0; i < nameList.size(); i++) {
            state.names.add(nameList.getString(i));
            state.nameIndex.put(nameList.getString(i), i);
        }
        long[] positions = nbt.getLongArray("Positions");
        int[] indexes = nbt.getIntArray("Owners");
        int[] blocks = nbt.getIntArray("Blocks");
        for (int i = 0; i < Math.min(positions.length, indexes.length); i++) {
            state.owners.put(positions[i], indexes[i]);
            if (i < blocks.length && blocks[i] >= 0) state.placedBlock.put(positions[i], blocks[i]);
        }
        return state;
    }
}
