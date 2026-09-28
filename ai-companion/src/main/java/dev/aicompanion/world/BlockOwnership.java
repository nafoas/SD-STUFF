package dev.aicompanion.world;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
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
    private final List<String> names = new ArrayList<>();
    private final Map<String, Integer> nameIndex = new HashMap<>();

    public BlockOwnership() {
        owners.defaultReturnValue(-1);
    }

    public static BlockOwnership get(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(BlockOwnership::fromNbt, BlockOwnership::new, ID);
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
        markDirty();
    }

    public synchronized void clear(BlockPos pos) {
        if (owners.remove(pos.asLong()) != -1) markDirty();
    }

    @Nullable
    public synchronized String owner(BlockPos pos) {
        int idx = owners.get(pos.asLong());
        return idx < 0 ? null : names.get(idx);
    }

    public boolean isPlayerPlaced(BlockPos pos) {
        String o = owner(pos);
        return o != null && !o.startsWith(COMPANION_PREFIX);
    }

    /** Counts player-placed blocks in a cube around pos. */
    public synchronized int playerPlacedNear(BlockPos pos, int radius) {
        int n = 0;
        for (BlockPos p : BlockPos.iterate(pos.add(-radius, -radius, -radius), pos.add(radius, radius, radius))) {
            int idx = owners.get(p.asLong());
            if (idx >= 0 && !names.get(idx).startsWith(COMPANION_PREFIX)) n++;
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
        for (int i = 0; i < Math.min(positions.length, indexes.length); i++) state.owners.put(positions[i], indexes[i]);
        return state;
    }
}
