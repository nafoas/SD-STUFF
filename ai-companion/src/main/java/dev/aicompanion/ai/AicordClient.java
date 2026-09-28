package dev.aicompanion.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aicompanion.ModConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal client for the AICord Chat API (https://docs.aicordapp.com/api/aicord-chat-api).
 * AICord holds each character's personality; this only sends messages and reads replies.
 */
public final class AicordClient {
    public record Character(String id, String name, String model) {}

    public record ChatMessage(String role, String text) {
        public static ChatMessage user(String text) {
            return new ChatMessage("user", text);
        }

        public static ChatMessage character(String text) {
            return new ChatMessage("character", text);
        }
    }

    public static class AicordException extends IOException {
        public AicordException(String message) {
            super(message);
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    /** Whether the key is sent as "Bearer <key>" (null = not yet known). The docs don't say which form they expect. */
    private static volatile Boolean useBearer = null;

    private AicordClient() {}

    public static List<Character> listCharacters() throws IOException, InterruptedException {
        JsonElement body = send("GET", "/characters", null);
        List<Character> result = new ArrayList<>();
        for (JsonElement el : body.getAsJsonArray()) {
            JsonObject o = el.getAsJsonObject();
            result.add(new Character(
                    o.get("id").getAsString(),
                    o.get("name").getAsString(),
                    o.has("model") && !o.get("model").isJsonNull() ? o.get("model").getAsString() : ""));
        }
        return result;
    }

    /** Sends the conversation to a character and returns its reply text. */
    public static String chat(String characterId, List<ChatMessage> messages) throws IOException, InterruptedException {
        JsonObject request = new JsonObject();
        request.addProperty("character", characterId);
        JsonArray msgs = new JsonArray();
        for (ChatMessage m : messages) {
            JsonObject msg = new JsonObject();
            msg.addProperty("role", m.role());
            JsonArray content = new JsonArray();
            JsonObject part = new JsonObject();
            part.addProperty("type", "text");
            part.addProperty("text", m.text());
            content.add(part);
            msg.add("content", content);
            msgs.add(msg);
        }
        request.add("messages", msgs);
        JsonObject response = send("POST", "/chat", request.toString()).getAsJsonObject();
        return response.has("text") && !response.get("text").isJsonNull() ? response.get("text").getAsString() : "";
    }

    private static JsonElement send(String method, String path, String body) throws IOException, InterruptedException {
        String key = ModConfig.get().aicordApiKey;
        if (key == null || key.isBlank()) throw new AicordException("No AICord API key set in config/ai-companion.json");
        key = key.trim();
        if (key.regionMatches(true, 0, "Bearer ", 0, 7)) key = key.substring(7).trim();

        boolean[] attempts = useBearer == null ? new boolean[]{true, false} : new boolean[]{useBearer};
        HttpResponse<String> response = null;
        for (boolean bearer : attempts) {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(ModConfig.get().aicordBaseUrl + path))
                    .timeout(Duration.ofSeconds(90))
                    .header("Authorization", bearer ? "Bearer " + key : key)
                    .header("Content-Type", "application/json");
            req = body == null ? req.GET() : req.method(method, HttpRequest.BodyPublishers.ofString(body));
            response = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 401) {
                useBearer = bearer;
                break;
            }
        }
        int status = response.statusCode();
        switch (status) {
            case 200 -> {
                return JsonParser.parseString(response.body());
            }
            case 401 -> throw new AicordException("AICord rejected the API key (401)");
            case 402 -> throw new AicordException("AICord account is out of credits (402)");
            case 403 -> throw new AicordException("AICord account is banned or not allowed (403)");
            case 404 -> throw new AicordException(path.equals("/chat") ? "AICord character not found (404)" : "No AICord characters found (404)");
            default -> throw new AicordException("AICord returned HTTP " + status + ": " + truncate(response.body(), 200));
        }
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
