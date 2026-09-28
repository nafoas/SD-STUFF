package dev.aicompanion.client;

import dev.aicompanion.AiCompanionMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class AiCompanionClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(AiCompanionMod.COMPANION, CompanionRenderer::new);
    }
}
