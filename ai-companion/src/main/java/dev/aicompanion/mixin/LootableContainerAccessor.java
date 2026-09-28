package dev.aicompanion.mixin;

import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets companions tell an unlooted, naturally generated chest (dungeon, village) from a player's chest. */
@Mixin(LootableContainerBlockEntity.class)
public interface LootableContainerAccessor {
    @Accessor("lootTableId")
    Identifier aiCompanion$getLootTableId();
}
