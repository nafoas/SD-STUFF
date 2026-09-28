package dev.aicompanion.client;

import dev.aicompanion.entity.CompanionEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.client.render.entity.model.ArmorEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

/** Draws companions as players, with their configured skin (Steve until one is set or while it downloads). */
public class CompanionRenderer extends BipedEntityRenderer<CompanionEntity, PlayerEntityModel<CompanionEntity>> {
    private final PlayerEntityModel<CompanionEntity> wide;
    private final PlayerEntityModel<CompanionEntity> slim;

    public CompanionRenderer(EntityRendererFactory.Context ctx) {
        super(ctx, new PlayerEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER), false), 0.5f);
        this.wide = this.model;
        this.slim = new PlayerEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER_SLIM), true);
        addFeature(new ArmorFeatureRenderer<>(this,
                new ArmorEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER_INNER_ARMOR)),
                new ArmorEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER_OUTER_ARMOR)),
                ctx.getModelManager()));
    }

    @Override
    public void render(CompanionEntity entity, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertices, int light) {
        SkinCache.Skin skin = SkinCache.get(entity.getSkin());
        boolean useSlim = skin != null && skin.slim() != null ? skin.slim() : entity.isSlim();
        this.model = useSlim ? slim : wide;
        super.render(entity, yaw, tickDelta, matrices, vertices, light);
    }

    @Override
    public Identifier getTexture(CompanionEntity entity) {
        SkinCache.Skin skin = SkinCache.get(entity.getSkin());
        if (skin != null && skin.texture() != null) return skin.texture();
        return SkinCache.defaultTexture(entity);
    }
}
