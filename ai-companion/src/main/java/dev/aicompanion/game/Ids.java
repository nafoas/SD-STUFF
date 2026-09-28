package dev.aicompanion.game;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/** Turns the names Claude uses ("oak_log", "#logs", "iron ore") into game objects, and back. */
public final class Ids {
    private Ids() {}

    /** Common casual names for block groups, mapped to vanilla block tags. */
    private static final Map<String, String> BLOCK_ALIASES = Map.ofEntries(
            Map.entry("log", "logs"), Map.entry("logs", "logs"), Map.entry("wood", "logs"), Map.entry("any_log", "logs"),
            Map.entry("planks", "planks"), Map.entry("leaves", "leaves"), Map.entry("sand", "sand"),
            Map.entry("dirt", "dirt"), Map.entry("wool", "wool"), Map.entry("flowers", "flowers"));

    public static String normalize(String name) {
        String s = name.trim().toLowerCase().replace(' ', '_');
        return s.startsWith("minecraft:") ? s.substring("minecraft:".length()) : s;
    }

    public static Optional<Identifier> id(String name) {
        String s = normalize(name);
        if (s.startsWith("#")) s = s.substring(1);
        return Optional.ofNullable(Identifier.tryParse(s.contains(":") ? s : "minecraft:" + s));
    }

    public static Optional<Item> item(String name) {
        return id(name).filter(Registries.ITEM::containsId).map(Registries.ITEM::get).filter(i -> i != Items.AIR);
    }

    public static Optional<Block> block(String name) {
        return id(name).filter(Registries.BLOCK::containsId).map(Registries.BLOCK::get);
    }

    public static String name(Item item) {
        Identifier id = Registries.ITEM.getId(item);
        return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
    }

    public static String name(Block block) {
        Identifier id = Registries.BLOCK.getId(block);
        return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
    }

    /**
     * A predicate matching world blocks for a name. Accepts block ids, "#tags", casual group names ("logs"),
     * and treats "iron_ore" as any iron ore (including deepslate variants).
     */
    public static Optional<Predicate<BlockState>> blockMatcher(String name) {
        String s = normalize(name);
        String tagName = null;
        if (s.startsWith("#")) tagName = s.substring(1);
        else if (BLOCK_ALIASES.containsKey(s)) tagName = BLOCK_ALIASES.get(s);
        else if (s.endsWith("_ore") && !s.startsWith("deepslate_") && !s.startsWith("nether_")) {
            Identifier oreTag = Identifier.tryParse("minecraft:" + s + "s");
            if (oreTag != null && Registries.BLOCK.getEntryList(TagKey.of(RegistryKeys.BLOCK, oreTag)).isPresent()) tagName = s + "s";
        }
        if (tagName != null) {
            Identifier tagId = Identifier.tryParse(tagName.contains(":") ? tagName : "minecraft:" + tagName);
            if (tagId == null) return Optional.empty();
            TagKey<Block> tag = TagKey.of(RegistryKeys.BLOCK, tagId);
            if (Registries.BLOCK.getEntryList(tag).isEmpty()) return Optional.empty();
            return Optional.of(state -> state.isIn(tag));
        }
        return block(s).filter(b -> !b.getDefaultState().isAir()).map(b -> state -> state.isOf(b));
    }

    /** Parses a block with optional properties, e.g. "oak_stairs[facing=east,half=bottom]". */
    public static Optional<BlockState> blockState(String text) {
        String s = text.trim().toLowerCase();
        try {
            return Optional.of(BlockArgumentParser.block(Registries.BLOCK.getReadOnlyWrapper(), s, false).blockState());
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
