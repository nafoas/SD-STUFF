package dev.aicompanion;

import dev.aicompanion.command.CompanionCommands;
import dev.aicompanion.entity.CompanionEntity;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AiCompanionMod implements ModInitializer {
    public static final String MOD_ID = "ai-companion";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final EntityType<CompanionEntity> COMPANION = Registry.register(Registries.ENTITY_TYPE,
            new Identifier(MOD_ID, "companion"),
            FabricEntityTypeBuilder.create(SpawnGroup.MISC, CompanionEntity::new)
                    .dimensions(EntityDimensions.fixed(0.6f, 1.8f))
                    .trackRangeBlocks(64)
                    .build());

    @Override
    public void onInitialize() {
        ModConfig.load();
        FabricDefaultAttributeRegistry.register(COMPANION, CompanionEntity.createAttributes());

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> CompanionCommands.register(dispatcher));

        ServerLifecycleEvents.SERVER_STARTED.register(CompanionManager::setServer);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> CompanionManager.shutdown());
        ServerTickEvents.END_SERVER_TICK.register(CompanionManager::tick);

        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof CompanionEntity c) CompanionManager.onEntityLoaded(c);
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (entity instanceof CompanionEntity c) CompanionManager.onEntityUnloaded(c);
        });

        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) ->
                CompanionManager.onPlayerChat(sender, message.getSignedContent()));

        LOGGER.info("AI Companion loaded");
    }
}
