package dev.aicompanion.mixin;

import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.mob.MobEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the mod add "hunt companions too" to hostile mobs' targeting. */
@Mixin(MobEntity.class)
public interface MobEntityAccessor {
    @Accessor("targetSelector")
    GoalSelector aiCompanion$getTargetSelector();
}
