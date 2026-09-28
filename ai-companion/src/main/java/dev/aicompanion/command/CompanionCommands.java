package dev.aicompanion.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.aicompanion.CompanionManager;
import dev.aicompanion.ModConfig;
import dev.aicompanion.ai.AicordClient;
import dev.aicompanion.ai.CompanionBrain;
import dev.aicompanion.entity.CompanionEntity;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/** /companion ... */
public final class CompanionCommands {
    private CompanionCommands() {}

    private static final SuggestionProvider<ServerCommandSource> CHARACTERS = (ctx, builder) -> CommandSource.suggestMatching(
            CompanionManager.cachedCharacters().stream().map(AicordClient.Character::name), builder);
    private static final SuggestionProvider<ServerCommandSource> ACTIVE = (ctx, builder) -> CommandSource.suggestMatching(
            CompanionManager.brains().stream().map(CompanionBrain::name), builder);

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        LiteralArgumentBuilder<ServerCommandSource> root = literal("companion");
        root.then(literal("characters").executes(CompanionCommands::listCharacters));
        root.then(literal("list").executes(CompanionCommands::listActive));
        root.then(literal("spawn").requires(CompanionCommands::canManage)
                .then(argument("name", StringArgumentType.greedyString()).suggests(CHARACTERS).executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
                    String name = StringArgumentType.getString(ctx, "name");
                    ctx.getSource().sendFeedback(() -> Text.literal("Looking up " + name + " on AICord...").formatted(Formatting.GRAY), false);
                    CompanionManager.spawn(player, name, text -> ctx.getSource().sendFeedback(() -> text, false));
                    return 1;
                })));
        root.then(literal("dismiss").requires(CompanionCommands::canManage)
                .then(argument("name", StringArgumentType.greedyString()).suggests(ACTIVE).executes(ctx -> {
                    String name = StringArgumentType.getString(ctx, "name");
                    boolean ok = CompanionManager.dismiss(name);
                    ctx.getSource().sendFeedback(() -> Text.literal(ok ? name + " left the world. Their memories are kept." : name + " isn't in the world."), false);
                    return ok ? 1 : 0;
                })));
        root.then(literal("stop").then(argument("name", StringArgumentType.greedyString()).suggests(ACTIVE).executes(ctx -> withBody(ctx, (brain, e) -> {
            e.cancelTask("stopped by command");
            ctx.getSource().sendFeedback(() -> Text.literal(brain.name() + " stopped what they were doing."), false);
        }))));
        root.then(literal("profile").then(argument("name", StringArgumentType.greedyString()).suggests(ACTIVE).executes(ctx -> withBrain(ctx, brain -> {
            ctx.getSource().sendFeedback(() -> Text.literal(brain.name() + (brain.hasProfile() ? "" : " (not interviewed yet)") + ":\n" + brain.profile().describe()), false);
        }))));
        root.then(literal("log").then(argument("name", StringArgumentType.greedyString()).suggests(ACTIVE).executes(ctx -> withBrain(ctx, brain -> {
            StringBuilder sb = new StringBuilder(brain.name() + "'s recent activity:");
            java.text.SimpleDateFormat time = new java.text.SimpleDateFormat("HH:mm");
            for (var e : brain.memory().recentJournal(20)) {
                sb.append("\n ").append(time.format(new java.util.Date(e.time))).append(" [").append(e.kind).append("] ").append(e.text);
            }
            ctx.getSource().sendFeedback(() -> Text.literal(sb.toString()), false);
        }))));
        root.then(literal("memory").then(argument("name", StringArgumentType.greedyString()).suggests(ACTIVE).executes(ctx -> withBody(ctx, (brain, e) -> {
            String report = dev.aicompanion.ai.MemoryReport.recall(brain.memory(), e, "");
            ctx.getSource().sendFeedback(() -> Text.literal(brain.name() + " remembers:\n" + report), false);
        }))));
        root.then(literal("reinterview").requires(CompanionCommands::canManage)
                .then(argument("name", StringArgumentType.greedyString()).suggests(ACTIVE).executes(ctx -> withBrain(ctx, brain -> {
                    ctx.getSource().sendFeedback(() -> Text.literal("Asking " + brain.name() + " about themselves again...").formatted(Formatting.GRAY), false);
                    brain.interview(() -> ctx.getSource().getServer().execute(() ->
                            ctx.getSource().sendFeedback(() -> Text.literal(brain.name() + "'s profile:\n" + brain.profile().describe()), false)));
                }))));
        root.then(literal("debug").requires(CompanionCommands::canManage)
                .then(argument("name", StringArgumentType.greedyString()).suggests(ACTIVE).executes(ctx -> withBrain(ctx, brain -> {
                    brain.toggleDebug();
                    ctx.getSource().sendFeedback(() -> Text.literal("Toggled debug messages for " + brain.name() + " (shown to their owner)."), false);
                }))));
        root.then(literal("reload").requires(src -> src.hasPermissionLevel(2)).executes(ctx -> {
            ModConfig.load();
            ctx.getSource().sendFeedback(() -> Text.literal("Reloaded config/ai-companion.json."), true);
            return 1;
        }));
        dispatcher.register(root);
    }

    private static boolean canManage(ServerCommandSource source) {
        return source.hasPermissionLevel(ModConfig.get().managePermissionLevel);
    }

    private static int listCharacters(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        source.sendFeedback(() -> Text.literal("Fetching your AICord characters...").formatted(Formatting.GRAY), false);
        CompanionManager.refreshCharacters().whenComplete((list, error) -> source.getServer().execute(() -> {
            if (error != null) {
                Throwable t = error;
                while (t.getCause() != null) t = t.getCause();
                String msg = t.getMessage();
                source.sendFeedback(() -> Text.literal("Couldn't reach AICord: " + msg).formatted(Formatting.RED), false);
                return;
            }
            StringBuilder sb = new StringBuilder("Your AICord characters (" + list.size() + "):");
            for (AicordClient.Character c : list) sb.append("\n - ").append(c.name()).append(c.model().isEmpty() ? "" : " (" + c.model() + ")");
            source.sendFeedback(() -> Text.literal(sb.toString()), false);
        }));
        return 1;
    }

    private static int listActive(CommandContext<ServerCommandSource> ctx) {
        StringBuilder sb = new StringBuilder("Companions:");
        int n = 0;
        for (CompanionBrain brain : CompanionManager.brains()) {
            CompanionEntity e = brain.entity();
            if (e == null) continue;
            n++;
            String doing = e.currentTaskDescription();
            sb.append("\n - ").append(brain.name()).append(" at ").append(e.getBlockPos().toShortString())
                    .append(", ").append(doing == null ? (brain.isThinkingOrActing() ? "thinking" : "idle") : doing);
        }
        if (n == 0) sb.append(" none in the world. Use /companion spawn <name>.");
        ctx.getSource().sendFeedback(() -> Text.literal(sb.toString()), false);
        return n;
    }

    private interface BrainAction {
        void run(CompanionBrain brain);
    }

    private interface BodyAction {
        void run(CompanionBrain brain, CompanionEntity e);
    }

    private static int withBrain(CommandContext<ServerCommandSource> ctx, BrainAction action) {
        String name = StringArgumentType.getString(ctx, "name");
        CompanionBrain brain = CompanionManager.byName(name);
        if (brain == null) {
            ctx.getSource().sendError(Text.literal("No companion called " + name + "."));
            return 0;
        }
        action.run(brain);
        return 1;
    }

    private static int withBody(CommandContext<ServerCommandSource> ctx, BodyAction action) {
        return withBrain(ctx, brain -> {
            CompanionEntity e = brain.entity();
            if (e == null) ctx.getSource().sendError(Text.literal(brain.name() + " isn't in the world."));
            else action.run(brain, e);
        });
    }
}
