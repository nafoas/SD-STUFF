package dev.aicompanion.mixin;

import dev.aicompanion.world.BlockOwnership;
import dev.aicompanion.world.BuildAwareness;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Records which player placed each block, so companions can tell someone's build from natural terrain. */
@Mixin(BlockItem.class)
public class BlockItemMixin {
    @Inject(method = "place(Lnet/minecraft/item/ItemPlacementContext;)Lnet/minecraft/util/ActionResult;", at = @At("RETURN"))
    private void aiCompanion$recordPlacement(ItemPlacementContext context, CallbackInfoReturnable<ActionResult> cir) {
        if (cir.getReturnValue().isAccepted() && context.getPlayer() instanceof ServerPlayerEntity player
                && context.getWorld() instanceof ServerWorld world) {
            BlockOwnership.get(world).set(context.getBlockPos(), player.getGameProfile().getName());
            BuildAwareness.invalidate(context.getBlockPos());
        }
    }
}
