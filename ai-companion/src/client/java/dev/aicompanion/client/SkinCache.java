package dev.aicompanion.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aicompanion.AiCompanionMod;
import dev.aicompanion.entity.CompanionEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Downloads companion skins. A skin setting is either a direct PNG URL or a Minecraft username
 * (looked up through Mojang's public profile API, which also says whether the skin uses slim arms).
 */
public final class SkinCache {
    public record Skin(@Nullable Identifier texture, @Nullable Boolean slim) {}

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final Map<String, Skin> CACHE = new ConcurrentHashMap<>();
    private static final Skin LOADING = new Skin(null, null);

    private SkinCache() {}

    public static Identifier defaultTexture(CompanionEntity entity) {
        return DefaultSkinHelper.getTexture(entity.getUuid());
    }

    @Nullable
    public static Skin get(String setting) {
        if (setting == null || setting.isBlank()) return null;
        Skin skin = CACHE.get(setting);
        if (skin == null) {
            CACHE.put(setting, LOADING);
            CompletableFuture.runAsync(() -> load(setting));
            return null;
        }
        return skin == LOADING ? null : skin;
    }

    private static void load(String setting) {
        try {
            String url = setting.trim();
            Boolean slim = null;
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                JsonObject profile = json("https://api.mojang.com/users/profiles/minecraft/" + url);
                String id = profile.get("id").getAsString();
                JsonObject full = json("https://sessionserver.mojang.com/session/minecraft/profile/" + id);
                String encoded = full.getAsJsonArray("properties").get(0).getAsJsonObject().get("value").getAsString();
                JsonObject textures = JsonParser.parseString(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonObject("textures").getAsJsonObject("SKIN");
                url = textures.get("url").getAsString();
                slim = textures.has("metadata") && "slim".equals(textures.getAsJsonObject("metadata").get("model").getAsString());
            }
            byte[] png = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofByteArray()).body();
            NativeImage image = NativeImage.read(new ByteArrayInputStream(png));
            if (image.getWidth() != 64 || image.getHeight() != 64) {
                image.close();
                throw new IllegalArgumentException("skin must be a 64x64 PNG");
            }
            Boolean slimArms = slim;
            Identifier id = new Identifier(AiCompanionMod.MOD_ID, "skins/" + Integer.toHexString(setting.hashCode()));
            MinecraftClient.getInstance().execute(() -> {
                MinecraftClient.getInstance().getTextureManager().registerTexture(id, new NativeImageBackedTexture(image));
                CACHE.put(setting, new Skin(id, slimArms));
            });
        } catch (Exception e) {
            AiCompanionMod.LOGGER.warn("Couldn't load companion skin '{}': {}", setting, e.getMessage());
            CACHE.put(setting, new Skin(null, null));
        }
    }

    private static JsonObject json(String url) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IllegalStateException("HTTP " + r.statusCode() + " from " + url);
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }
}
