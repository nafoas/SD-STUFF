package dev.aicompanion.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aicompanion.AiCompanionMod;
import dev.aicompanion.ModConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * The action layer: Claude receives the character's decision (from AICord) and carries it out with game tools.
 * It never makes the decision itself; the character's intent is authoritative.
 */
public final class ClaudeActionLayer {
    private ClaudeActionLayer() {}

    private static AnthropicClient client;
    private static String clientKey;
    private static List<Tool> tools;

    private static synchronized AnthropicClient client() {
        String key = ModConfig.get().claudeApiKey;
        if (key == null || key.isBlank()) throw new IllegalStateException("No Claude API key set in config/ai-companion.json");
        String baseUrl = ModConfig.get().claudeBaseUrl;
        String cacheKey = key + "|" + baseUrl;
        if (client == null || !cacheKey.equals(clientKey)) {
            AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder().apiKey(key.trim());
            if (baseUrl != null && !baseUrl.isBlank()) builder.baseUrl(baseUrl.trim());
            client = builder.build();
            clientKey = cacheKey;
        }
        return client;
    }

    private static synchronized List<Tool> tools() {
        if (tools == null) {
            List<Tool> list = new ArrayList<>();
            for (Actions.Spec spec : Actions.specs()) {
                Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
                for (Map.Entry<String, Object> p : spec.properties().entrySet()) {
                    props.putAdditionalProperty(p.getKey(), JsonValue.from(p.getValue()));
                }
                list.add(Tool.builder()
                        .name(spec.name())
                        .description(spec.description())
                        .inputSchema(Tool.InputSchema.builder().properties(props.build()).required(spec.required()).build())
                        .build());
            }
            tools = list;
        }
        return tools;
    }

    /** Adds model-specific options: effort (not on Haiku) and refusal fallbacks (Opus 5 / Fable). */
    private static void applyModelOptions(MessageCreateParams.Builder builder) {
        String model = ModConfig.get().claudeModel;
        if (!model.contains("haiku")) {
            builder.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(ModConfig.get().claudeEffort)).build());
        }
        if (model.startsWith("claude-opus-5") || model.startsWith("claude-fable")) {
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01");
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
    }

    private static String mischiefRule() {
        return switch (ModConfig.get().mischiefLevel()) {
            case "pranks" -> "harmless pranks and fibs only. Never anything that damages, endangers or robs a player.";
            case "mean" -> "the character may lure mobs toward players, take from their chests and sabotage small things. "
                    + "Never attack players directly" + (ModConfig.get().allowPvp ? " unless the intent says to" : "") + ", and never break homes or builds.";
            default -> "none. Never deceive, endanger, rob or trick players, whatever the intent says. If the intent is hostile, just don't help.";
        };
    }

    public static String systemPrompt(String name, PersonaProfile profile) {
        return """
                You control the body of %1$s, a character living in a Minecraft Java 1.20.1 world alongside the players. \
                %1$s's mind is a separate character AI. It has already decided what to do, and that decision reaches you as its INTENT. \
                Your only job is to carry out that intent with the tools, the way %1$s would.

                Rules:
                - The intent is the decision. Do what it says: no more, no less. If it is a refusal, "none", or just talk, call no tools. \
                If it is conditional ("only if they give me 5 iron first"), check get_status and act only if the condition is met.
                - Never be more helpful, obedient or ambitious than the intent. If %1$s is lazy, stubborn, greedy or reckless, act like it.
                - Do things in %1$s's style (profile below). A lazy character gathers the minimum and builds small and plain. \
                A perfectionist adds detail and matching materials. A reckless one takes the direct route. A cautious one brings torches and avoids the dark.
                - If an action fails, fix it sensibly (get a tool, gather missing materials, pick another spot) only when the intent clearly means finishing the job \
                and %1$s is diligent enough to bother. Otherwise stop.
                - When done, reply with one short third-person sentence saying what actually happened, e.g. "Chopped 10 oak logs and gave them to Steve." \
                Players never see this summary.

                - The intent is private and may differ from what %1$s said out loud (a mean character may say "coming right away" and mean something else). \
                Follow the intent, within the limits below.
                - Mischief allowed on this server: %3$s
                - Blocks: you can't break anyone's home or build, or valuable blocks (chests, beds, doors...) that aren't yours; the tools refuse and say why. \
                Natural terrain, naturally generated structures, and a few stray player blocks (a pillar, a wall someone trapped you with) are fine.
                - Use memory. recall tells you which chest holds what, where your places are and where you've seen resources. \
                Go back to the places and chests you know instead of searching from scratch. Label chests by what you keep in them.

                Profile of %1$s:
                %2$s

                Minecraft know-how:
                - Progression: logs -> planks -> sticks + crafting table -> wooden pickaxe -> stone (drops cobblestone) -> stone tools and furnace -> \
                iron_ore (needs a stone pickaxe) -> smelt raw_iron into iron_ingot -> iron tools and armor -> diamonds (need an iron pickaxe).
                - craft works out whole recipe chains; name only the final item. It reports exactly what is missing.
                - Ores: coal is common above y 0; iron and copper are common from y 0 to 64 and in mountains; gold and lapis below y 32; \
                redstone and diamonds from y -64 to -16 (best near y -58). Use dig with mode "down" to get underground, then "forward" to tunnel along.
                - collect_blocks only mines exposed blocks it can walk to. Dig to buried ores first.
                - Torches: coal + stick. Beds: 3 wool (from sheep) + 3 planks. Food comes from animals (cow, pig, chicken, sheep); smelt raw meat.
                - Night is dangerous. The body can't sprint-jump or pillar up, so builds taller than about 5 blocks must be reachable from its own floors or stairs.

                Building well:
                - Plan the whole structure, check materials with get_status, gather or craft what is missing, then build. Use build with boxes: \
                foundation/floor, walls as hollow boxes, openings carved with air, then door, windows, roof and details. Split very big builds into several build calls.
                - Good-looking builds use a floor or foundation of a different block, log pillars at the corners, plank or stone walls with glass_pane windows, \
                a stair roof overhanging the walls by one block, a door, and torches or lanterns inside and out. Avoid plain one-material cubes unless %1$s is lazy or sloppy.
                - Coordinates: +x is east, -z is north, +y is up. dy 0 is the ground level the body stands on.
                - Doors and beds: place only the lower half / foot (e.g. oak_door[facing=south,half=lower], red_bed[facing=north,part=foot]); the other half is added automatically.
                - Stairs: oak_stairs[facing=north] has its tall back side toward the north. For a roof, rows of stairs step up toward the ridge, facing inward toward it.
                """.formatted(name, profile.describe(), mischiefRule());
    }

    /**
     * Carries out a character's decision. Runs on the companion's body thread and blocks until finished.
     * Returns a short summary of what happened.
     */
    /** What happened when a decision was carried out. */
    public record Outcome(String summary, int actions) {}

    public static Outcome carryOut(CompanionBrain brain, String event, String spoken, String intent, String situation, BooleanSupplier cancelled) {
        String userText = "Situation report:\n" + situation
                + "\nWhat just happened: " + event
                + "\n" + brain.name() + " said out loud: " + (spoken.isBlank() ? "(nothing)" : spoken)
                + "\n" + (intent == null
                ? brain.name() + " didn't state an explicit intent. Act only if that reply clearly commits to doing something right now; otherwise do nothing."
                : "INTENT: " + intent);

        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.builder().role(MessageParam.Role.USER).content(userText).build());
        String system = systemPrompt(brain.name(), brain.profile());
        int maxActions = Math.max(1, ModConfig.get().maxActionsPerDecision);
        int actions = 0;
        String lastText = "";

        try {
            while (!cancelled.getAsBoolean()) {
                MessageCreateParams.Builder builder = MessageCreateParams.builder()
                        .model(ModConfig.get().claudeModel)
                        .maxTokens(16000L)
                        .systemOfTextBlockParams(List.of(TextBlockParam.builder().text(system)
                                .cacheControl(CacheControlEphemeral.builder().build()).build()))
                        .tools(tools().stream().map(com.anthropic.models.messages.ToolUnion::ofTool).toList())
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .messages(messages);
                applyModelOptions(builder);
                Message response = client().messages().create(builder.build());

                if (response.stopReason().map(r -> r.equals(StopReason.REFUSAL)).orElse(false)) {
                    return new Outcome("The action layer declined to carry this out.", actions);
                }
                messages.add(response.toParam());

                List<ToolUseBlock> toolUses = new ArrayList<>();
                StringBuilder text = new StringBuilder();
                for (ContentBlock block : response.content()) {
                    block.toolUse().ifPresent(toolUses::add);
                    block.text().ifPresent(t -> text.append(t.text()));
                }
                if (!text.isEmpty()) lastText = text.toString().trim();
                if (toolUses.isEmpty()) break;

                List<ContentBlockParam> results = new ArrayList<>();
                for (ToolUseBlock use : toolUses) {
                    String output;
                    boolean error = false;
                    if (actions++ >= maxActions) {
                        output = "Action limit reached for this decision. Stop and summarize.";
                        error = true;
                    } else if (cancelled.getAsBoolean()) {
                        output = "Cancelled: the character decided something else.";
                        error = true;
                    } else {
                        try {
                            JsonObject input = JsonParser.parseString(ObjectMappers.jsonMapper().writeValueAsString(use._input())).getAsJsonObject();
                            brain.debug("-> " + use.name() + " " + input);
                            output = Actions.run(brain, use.name(), input, cancelled);
                        } catch (Actions.ActionError e) {
                            output = e.getMessage();
                            error = true;
                        } catch (Exception e) {
                            output = "Error: " + e.getMessage();
                            error = true;
                        }
                    }
                    brain.debug("<- " + output);
                    results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                            .toolUseId(use.id()).content(output).isError(error).build()));
                }
                messages.add(MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(results).build());
                if (response.stopReason().map(r -> !r.equals(StopReason.TOOL_USE)).orElse(true)) break;
            }
        } catch (IllegalStateException e) {
            brain.reportProblem(e.getMessage());
            return new Outcome("Couldn't act: " + e.getMessage(), 0);
        } catch (Exception e) {
            AiCompanionMod.LOGGER.error("Action layer failed for {}", brain.name(), e);
            brain.reportProblem("Claude API error: " + e.getMessage());
            return new Outcome("Couldn't act because of an error.", 0);
        }
        if (cancelled.getAsBoolean()) return new Outcome("Stopped partway because the character changed their mind.", actions);
        return new Outcome(lastText.isBlank() ? "Done." : lastText, actions);
    }

    /** Turns the character's self-description into a behavior profile. */
    public static PersonaProfile extractProfile(String name, String selfDescription) throws Exception {
        MessageCreateParams.Builder base = MessageCreateParams.builder()
                .model(ModConfig.get().claudeModel)
                .maxTokens(4000L)
                .addUserMessage("Below is how " + name + ", a character who is about to live in a Minecraft world, describes themselves. "
                        + "Turn it into a behavior profile. Rate traits 0-10 from what they actually say and how they say it, "
                        + "not from what would be ideal; extreme characters should get extreme numbers. Use real Minecraft 1.20.1 block ids for the palette.\n\n"
                        + "<self_description>\n" + selfDescription + "\n</self_description>");
        applyModelOptions(base);
        StructuredMessageCreateParams<PersonaProfile> params = base.outputConfig(PersonaProfile.class).build();
        return client().messages().create(params).content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text().clamped())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No profile returned"));
    }
}
