package dev.aicompanion.game;

import dev.aicompanion.ai.CompanionMemory;
import dev.aicompanion.entity.CompanionEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Compact text description of what a companion can currently perceive. Must run on the server thread. */
public final class Perception {
    private Perception() {}

    public static String describe(CompanionEntity c, @Nullable CompanionMemory memory) {
        ServerWorld world = c.serverWorld();
        StringBuilder sb = new StringBuilder();
        long time = world.getTimeOfDay() % 24000;
        int hour = (int) ((time / 1000 + 6) % 24);
        String period = time < 12000 ? "day" : time < 13800 ? "sunset" : time < 22200 ? "night" : "sunrise";
        String weather = world.isThundering() ? "thunderstorm" : world.isRaining() ? "raining" : "clear";
        String biome = world.getBiome(c.getBlockPos()).getKey().map(k -> k.getValue().getPath()).orElse("unknown");
        sb.append("Time: ").append(String.format("%02d:00", hour)).append(" (").append(period).append("), day ")
                .append(world.getTimeOfDay() / 24000 + 1).append(", ").append(weather).append(". Place: ")
                .append(world.getRegistryKey().getValue().getPath()).append(", ").append(biome).append(" biome.\n");

        BlockPos pos = c.getBlockPos();
        sb.append("You: at ").append(pos.getX()).append(", ").append(pos.getY()).append(", ").append(pos.getZ())
                .append(", health ").append(Math.round(c.getHealth())).append("/").append(Math.round(c.getMaxHealth()))
                .append(", hunger ").append(c.getFood()).append("/20").append(c.getFood() <= 6 ? " (starving)" : c.getFood() <= 12 ? " (hungry)" : "");
        if (world.isSkyVisible(pos.up())) sb.append(", outdoors");
        else if (pos.getY() < world.getSeaLevel() - 8) sb.append(", underground");
        sb.append(". Holding: ").append(c.getMainHandStack().isEmpty() ? "nothing" : Ids.name(c.getMainHandStack().getItem()));
        List<String> armor = new ArrayList<>();
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack s = c.getEquippedStack(slot);
            if (!s.isEmpty()) armor.add(Ids.name(s.getItem()));
        }
        sb.append(". Wearing: ").append(armor.isEmpty() ? "no armor" : String.join(", ", armor)).append(".\n");

        sb.append("Inventory: ").append(inventory(c)).append(".\n");
        String doing = c.currentTaskDescription();
        sb.append("Currently: ").append(doing == null ? "nothing in particular" : doing).append(".\n");

        // Players
        List<String> players = new ArrayList<>();
        for (PlayerEntity p : world.getPlayers()) {
            if (p.isSpectator()) continue;
            double d = Math.sqrt(p.squaredDistanceTo(c));
            if (d > 64) continue;
            String name = p.getName().getString();
            String feeling = memory == null ? "" : ", you " + CompanionMemory.describeOpinion(memory.opinionOf(name)) + " them";
            String owner = name.equalsIgnoreCase(c.getOwnerName()) ? ", brought you into this world" : "";
            players.add(name + " (" + Math.round(d) + " blocks away" + owner + feeling + ", holding "
                    + (p.getMainHandStack().isEmpty() ? "nothing" : Ids.name(p.getMainHandStack().getItem())) + ")");
        }
        sb.append("Players nearby: ").append(players.isEmpty() ? "none" : String.join("; ", players)).append(".\n");

        // Creatures
        Map<String, int[]> creatures = new TreeMap<>();
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, c.getBoundingBox().expand(24), e -> e != c && e.isAlive() && !(e instanceof PlayerEntity))) {
            String name = e instanceof CompanionEntity other ? other.getCharacterName() + " (a companion like you)" : e.getType().getName().getString().toLowerCase();
            int d = (int) Math.round(Math.sqrt(e.squaredDistanceTo(c)));
            if (memory != null && (e instanceof net.minecraft.entity.passive.AnimalEntity || e instanceof net.minecraft.entity.passive.VillagerEntity)) {
                BlockPos p = e.getBlockPos();
                memory.sighted(net.minecraft.registry.Registries.ENTITY_TYPE.getId(e.getType()).getPath(), world.getRegistryKey().getValue().toString(), p.getX(), p.getY(), p.getZ(), System.currentTimeMillis());
            }
            creatures.merge(name, new int[]{1, d}, (a, b) -> new int[]{a[0] + 1, Math.min(a[1], b[1])});
        }
        List<String> cr = new ArrayList<>();
        creatures.forEach((name, v) -> cr.add(v[0] + " " + name + " (nearest " + v[1] + " blocks)"));
        sb.append("Creatures nearby: ").append(cr.isEmpty() ? "none" : String.join(", ", cr)).append(".\n");

        sb.append("Notable blocks nearby: ").append(notableBlocks(c, memory)).append(".\n");

        if (memory != null) sb.append(dev.aicompanion.ai.MemoryReport.summary(memory, c));
        return sb.toString();
    }

    public static String inventory(CompanionEntity c) {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < c.getInventory().size(); i++) {
            ItemStack s = c.getInventory().getStack(i);
            if (!s.isEmpty()) counts.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        if (counts.isEmpty()) return "empty";
        List<String> parts = new ArrayList<>();
        counts.forEach((item, n) -> parts.add(n + " " + Ids.name(item)));
        return String.join(", ", parts);
    }

    private static String notableBlocks(CompanionEntity c, @Nullable CompanionMemory memory) {
        Map<Block, int[]> found = new LinkedHashMap<>();
        Map<Block, BlockPos> nearestPos = new LinkedHashMap<>();
        BlockPos origin = c.getBlockPos();
        int r = 16;
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -8; dy <= 8; dy++) {
                    p.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    BlockState s = c.getWorld().getBlockState(p);
                    if (!isNotable(s)) continue;
                    int d = (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
                    int[] prev = found.get(s.getBlock());
                    if (prev == null || d < prev[1]) nearestPos.put(s.getBlock(), p.toImmutable());
                    found.merge(s.getBlock(), new int[]{1, d}, (a, b) -> new int[]{a[0] + 1, Math.min(a[1], b[1])});
                }
            }
        }
        if (memory != null) {
            // Object permanence: remember where resources are, and forget ones that are gone.
            String dim = c.getWorld().getRegistryKey().getValue().toString();
            long now = System.currentTimeMillis();
            nearestPos.forEach((block, pos) -> {
                String kind = sightingKind(block);
                if (kind != null) memory.sighted(kind, dim, pos.getX(), pos.getY(), pos.getZ(), now);
            });
            for (var entry : memory.sightings.entrySet()) {
                String k = entry.getKey();
                boolean blockKind = k.endsWith("_ore") || k.endsWith("_log") || List.of("ancient_debris", "sugar_cane", "pumpkin", "melon", "lava").contains(k);
                if (!blockKind) continue; // creatures move around; only blocks can be checked like this
                entry.getValue().removeIf(sight -> sight.dimension.equals(dim) && origin.getSquaredDistance(sight.x, sight.y, sight.z) < 10 * 10
                        && found.keySet().stream().noneMatch(b -> entry.getKey().equals(sightingKind(b))));
            }
        }
        if (found.isEmpty()) return "nothing special";
        List<String> parts = new ArrayList<>();
        found.entrySet().stream().sorted((a, b) -> a.getValue()[1] - b.getValue()[1]).limit(14)
                .forEach(e -> parts.add(e.getValue()[0] + " " + Ids.name(e.getKey()) + " (nearest " + e.getValue()[1] + ")"));
        return String.join(", ", parts);
    }

    /** Name a resource is remembered under: deepslate ores count as the plain ore. */
    @Nullable
    private static String sightingKind(Block block) {
        String path = Ids.name(block);
        if (path.startsWith("deepslate_") && path.endsWith("_ore")) path = path.substring("deepslate_".length());
        if (path.endsWith("_ore") || path.endsWith("_log") || path.equals("ancient_debris") || path.equals("sugar_cane")
                || path.equals("pumpkin") || path.equals("melon") || path.equals("lava")) return path;
        return null;
    }

    private static boolean isNotable(BlockState s) {
        if (s.isIn(BlockTags.LOGS) || s.isIn(BlockTags.BEDS)) return true;
        if (s.isOf(Blocks.CRAFTING_TABLE) || s.isOf(Blocks.FURNACE) || s.isOf(Blocks.CHEST) || s.isOf(Blocks.BARREL)
                || s.isOf(Blocks.LAVA) || s.isOf(Blocks.WHEAT) || s.isOf(Blocks.SUGAR_CANE) || s.isOf(Blocks.PUMPKIN) || s.isOf(Blocks.MELON)) return true;
        String path = Ids.name(s.getBlock());
        return path.endsWith("_ore") || path.equals("ancient_debris");
    }
}
