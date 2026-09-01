package dev.aigod;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.stats.Stats;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class GodService implements AutoCloseable {
    private final MinecraftServer server;
    private final String godName;
    private final OpenAiGodClient client;
    private final QuestManager quests;
    private final DailyChallengeManager daily;
    private final ConversationStore conversationStore;
    private final ArrayDeque<ChatTurn> queue = new ArrayDeque<>();
    private final Map<UUID, Long> pendingDailyDeadline = new HashMap<>();
    private String conversationId;
    private boolean processing;

    GodService(MinecraftServer server, String apiKey, String model, String godName, int compactThreshold,
               QuestStore questStore, DailyStore dailyStore, ConversationStore conversationStore) {
        this.server = server;
        this.godName = godName;
        this.client = new OpenAiGodClient(apiKey, model, godName, compactThreshold);
        this.quests = new QuestManager(server, questStore, this::deliverConsequence);
        this.daily = new DailyChallengeManager(server, this, quests, dailyStore);
        this.conversationStore = conversationStore;
        this.conversationId = conversationStore.load();
    }

    void hear(ServerPlayer player, String message) {
        queue.addLast(new ChatTurn(player.getUUID(), message));
        processNext();
    }

    void requestDailyChallenge(ServerPlayer player, long deadlineDayTime, Runnable onIssued, Runnable onFailed) {
        pendingDailyDeadline.put(player.getUUID(), deadlineDayTime);
        ChatTurn turn = new ChatTurn(player.getUUID(), """
                A new Minecraft day dawns. Issue today's daily challenge to %s with create_quest now.
                Make it genuinely fun and genuinely hard, different from their previous challenges, and
                achievable before sundown from the live state below. Set time_limit_minutes to any value;
                the deadline is overridden to sundown of this day. create_quest posts the one announcement;
                do not announce it with run_command or repeat it in your reply.
                """.formatted(player.getGameProfile().name()));
        turn.systemEvent = true;
        turn.onSuccess = onIssued;
        turn.onFailure = onFailed;
        queue.addLast(turn);
        processNext();
    }

    private void deliverConsequence(ServerPlayer player, Quest quest) {
        ChatTurn turn = new ChatTurn(player.getUUID(), """
                The sun has set and %s FAILED today's daily challenge: "%s" (progress %d/%d %s).
                Deliver a fitting consequence right now using run_command, matched to the challenge
                they failed (mob ambushes, lightning, traps, losses). Briefly tell them what happened.
                """.formatted(player.getGameProfile().name(), quest.challenge(),
                quest.progress(), quest.amount(), quest.target()));
        turn.systemEvent = true;
        turn.fallbackCommand = quest.punishmentCommand();
        queue.addLast(turn);
        processNext();
    }

    void playerDied(ServerPlayer player, String deathMessage) {
        ChatTurn turn = new ChatTurn(player.getUUID(), """
                %s just died: "%s". React as you see fit: mock them, mourn them, avenge them,
                punish whatever killed them, or stay_silent if this death bores you.
                """.formatted(player.getGameProfile().name(), deathMessage));
        turn.systemEvent = true;
        queue.addLast(turn);
        processNext();
    }

    void playerJoined(ServerPlayer player) {
        boolean firstJoin = player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) == 0;
        ChatTurn turn = new ChatTurn(player.getUUID(), firstJoin
                ? """
                  %s has joined this world for the first time. Give them a short, tailored,
                  in-character introduction. In at most two sentences, make clear that they can
                  speak normally and that you can act on the world with every server command.
                  """.formatted(player.getGameProfile().name())
                : """
                  %s has returned to the world. Welcome them back in one short, contextual,
                  in-character sentence using your shared memory and the live state.
                  """.formatted(player.getGameProfile().name()));
        turn.systemEvent = true;
        queue.addLast(turn);
        processNext();
    }

    void tick() {
        quests.tick();
        daily.tick();
    }

    QuestManager quests() {
        return quests;
    }

    private void processNext() {
        if (processing) return;
        ChatTurn turn = queue.peekFirst();
        if (turn == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(turn.playerId);
        if (player == null) {
            if (turn.onFailure != null) turn.onFailure.run();
            queue.removeFirst();
            processNext();
            return;
        }
        processing = true;
        turn.baseConversationId = conversationId;
        String input = snapshot(player, turn);
        client.respond(player.getUUID(), input, conversationId)
                .whenComplete((response, error) -> server.execute(() -> handle(turn, response, error)));
    }

    private void handle(ChatTurn turn, OpenAiGodClient.ResponseTurn response, Throwable error) {
        ServerPlayer player = server.getPlayerList().getPlayer(turn.playerId);
        if (error != null || player == null) {
            conversationId = turn.baseConversationId;
            if (player != null) {
                Throwable cause = error != null && error.getCause() != null ? error.getCause() : error;
                if (turn.fallbackCommand != null) {
                    quests.runOperatorCommand(turn.fallbackCommand, player);
                } else if (!turn.systemEvent) {
                    player.sendSystemMessage(Component.literal("§cThe AI God cannot answer: "
                            + (cause == null ? "speaker vanished" : cause.getMessage())));
                }
            }
            pendingDailyDeadline.remove(turn.playerId);
            if (turn.onFailure != null) turn.onFailure.run();
            finishTurn();
            return;
        }

        conversationId = response.conversationId();
        conversationStore.save(conversationId);
        boolean requestsSilence = response.toolCalls().stream()
                .anyMatch(call -> call.name().equals("stay_silent"));
        boolean createsQuest = response.toolCalls().stream()
                .anyMatch(call -> call.name().equals("create_quest"));
        if (!response.message().isBlank() && !turn.silent && !requestsSilence && !createsQuest) {
            say(response.message());
        }

        if (response.toolCalls().isEmpty()) {
            pendingDailyDeadline.remove(turn.playerId);
            if (turn.onSuccess != null) turn.onSuccess.run();
            finishTurn();
            return;
        }

        List<OpenAiGodClient.ToolResult> results = new ArrayList<>();
        for (OpenAiGodClient.ToolCall call : response.toolCalls()) {
            results.add(new OpenAiGodClient.ToolResult(call.callId(), execute(turn, player, call)));
        }
        client.continueWithTools(player.getUUID(), conversationId, results)
                .whenComplete((next, nextError) -> server.execute(() -> handle(turn, next, nextError)));
    }

    private String execute(ChatTurn turn, ServerPlayer player, OpenAiGodClient.ToolCall call) {
        try {
            return switch (call.name()) {
                case "run_command" -> runOperatorCommand(call.arguments(), player);
                case "command_help" -> commandHelp(call.arguments(), player);
                case "show_text" -> showText(call.arguments(), player);
                case "create_quest" -> createQuest(turn, call.arguments(), player);
                case "complete_challenge" -> completeChallenge(call.arguments());
                case "cancel_quest" -> cancelQuest(call.arguments());
                case "stay_silent" -> {
                    turn.silent = true;
                    yield "Silence selected. No chat message will be posted for this turn.";
                }
                default -> "error: unknown tool " + call.name();
            };
        } catch (RuntimeException exception) {
            return "error: " + exception.getMessage();
        }
    }

    private String runOperatorCommand(JsonObject arguments, ServerPlayer player) {
        String command = arguments.get("command").getAsString().strip();
        if (command.startsWith("/")) command = command.substring(1);
        command = command.replace("{player}", player.getGameProfile().name());
        CommandOutcome outcome = runCommand(command, player);
        if (!outcome.known()) return "ok: command accepted for execution: " + command;
        return outcome.succeeded()
                ? "ok: command returned %d: %s".formatted(outcome.value(), command)
                : "error: Minecraft reported command failure: " + command;
    }

    private String commandHelp(JsonObject arguments, ServerPlayer player) {
        String name = arguments.get("command").getAsString().strip();
        CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
        CommandSourceStack source = operatorSource(player);
        if (name.isEmpty()) {
            return dispatcher.getRoot().getChildren().stream()
                    .filter(node -> node.canUse(source))
                    .map(CommandNode::getName)
                    .sorted()
                    .toList()
                    .toString();
        }
        CommandNode<CommandSourceStack> node = dispatcher.getRoot().getChild(name);
        if (node == null || !node.canUse(source)) return "error: no available root command named " + name;
        String[] usage = dispatcher.getAllUsage(node, source, true);
        if (usage.length == 0) return name;
        return name + " " + String.join("\n" + name + " ", usage);
    }

    private String showText(JsonObject arguments, ServerPlayer player) {
        String text = MinecraftChatText.fromModel(arguments.get("text").getAsString());
        if (text.isBlank()) return "error: text cannot be blank";
        String color = arguments.get("color").getAsString();
        String command = "execute anchored eyes positioned ^ ^ ^3 run summon minecraft:text_display ~ ~ ~ "
                + "{billboard:\"center\",text:{text:%s,color:\"%s\"},shadow:true,background:0,Tags:[\"ai_god_text\"]}"
                .formatted(new JsonPrimitive(text).toString(), color);
        CommandOutcome outcome = runCommand(command, player);
        return !outcome.known() || outcome.succeeded()
                ? "ok: floating text created"
                : "error: Minecraft could not create the text display";
    }

    private CommandOutcome runCommand(String command, ServerPlayer player) {
        CommandSourceStack source = operatorSource(player);
        try {
            Commands.validateParseResults(server.getCommands().getDispatcher().parse(command, source));
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
            return new CommandOutcome(true, false, 0);
        }
        boolean[] called = {false};
        boolean[] succeeded = {false};
        int[] value = {0};
        server.getCommands().performPrefixedCommand(source.withCallback((success, result) -> {
            called[0] = true;
            succeeded[0] = success;
            value[0] = result;
        }), command);
        return new CommandOutcome(called[0], succeeded[0], value[0]);
    }

    private static CommandSourceStack operatorSource(ServerPlayer player) {
        return player.createCommandSourceStack().withPermission(PermissionSet.ALL_PERMISSIONS);
    }

    private String createQuest(ChatTurn turn, JsonObject arguments, ServerPlayer player) {
        Long dailyDeadline = pendingDailyDeadline.remove(player.getUUID());
        Quest quest = quests.create(player, arguments, dailyDeadline);
        turn.silent = true;
        player.sendSystemMessage(Component.literal("§d[%s] §f%s\n§eobjective: %s %s × %d"
                .formatted(godName, MinecraftChatText.fromModel(quest.challenge()),
                        quest.objective().name().toLowerCase(),
                        quest.target(), quest.amount())));
        return "ok: %s quest created for %s: %s (%s %s x%d)%s".formatted(
                quest.kind() == Quest.Kind.DAILY ? "daily" : "ad-hoc",
                player.getGameProfile().name(), quest.challenge(),
                quest.objective(), quest.target(), quest.amount(),
                quest.kind() == Quest.Kind.DAILY ? "; deadline is sundown today" : "");
    }

    private String completeChallenge(JsonObject arguments) {
        String name = arguments.get("player_name").getAsString();
        ServerPlayer target = server.getPlayerList().getPlayerByName(name);
        if (target == null) throw new IllegalArgumentException("No online player named " + name);
        return quests.forceComplete(target);
    }

    private String cancelQuest(JsonObject arguments) {
        String name = arguments.get("player_name").getAsString();
        ServerPlayer target = server.getPlayerList().getPlayerByName(name);
        if (target == null) throw new IllegalArgumentException("No online player named " + name);
        Quest cancelled = quests.cancel(target);
        if (cancelled.kind() == Quest.Kind.DAILY) {
            pendingDailyDeadline.put(target.getUUID(), cancelled.deadlineDayTime());
            return "ok: daily challenge of %s voided; if you create_quest a replacement now it keeps today's sundown deadline"
                    .formatted(name);
        }
        return "ok: quest of %s voided with no reward or punishment".formatted(name);
    }

    private void finishTurn() {
        queue.removeFirst();
        processing = false;
        processNext();
    }

    private void say(String message) {
        server.getPlayerList().broadcastSystemMessage(
                Component.literal("§d[%s] §f".formatted(godName)
                        + MinecraftChatText.fromModel(message)), false);
    }

    private String snapshot(ServerPlayer speaker, ChatTurn turn) {
        ServerLevel level = (ServerLevel) speaker.level();
        StringBuilder players = new StringBuilder();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!players.isEmpty()) players.append('\n');
            players.append("- ").append(player.getGameProfile().name())
                    .append(" health=").append("%.1f/%.1f hearts".formatted(
                            player.getHealth() / 2.0F, player.getMaxHealth() / 2.0F))
                    .append(" hunger=").append(player.getFoodData().getFoodLevel()).append("/20")
                    .append(" xp=").append(player.experienceLevel)
                    .append(" position=").append("%.0f %.0f %.0f".formatted(
                            player.getX(), player.getY(), player.getZ()))
                    .append(" rotation=").append("%.1f %.1f".formatted(
                            player.getYRot(), player.getXRot()))
                    .append(" dimension=").append(player.level().dimension().identifier())
                    .append(" holding=[").append(heldItem(player)).append(']')
                    .append(" inventory=[").append(inventory(player)).append(']')
                    .append(" quest=[").append(quests.status(player)).append(']');
        }
        String lead = turn.systemEvent
                ? "Automatic server event concerning %s: %s"
                : "New ordinary server chat message from %s: %s";
        return """
                %s

                Live server state:
                difficulty=%s, daytime_ticks=%d, raining=%s, thundering=%s
                online_players=%d
                %s
                """.formatted(
                lead.formatted(speaker.getGameProfile().name(), turn.message),
                server.getWorldData().getDifficulty(), level.getOverworldClockTime(),
                level.isRaining(), level.isThundering(),
                server.getPlayerCount(), players);
    }

    private static String heldItem(ServerPlayer player) {
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) return "nothing";
        return BuiltInRegistries.ITEM.getKey(held.getItem()) + " x" + held.getCount();
    }

    private static String inventory(ServerPlayer player) {
        StringBuilder inventory = new StringBuilder();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            if (!inventory.isEmpty()) inventory.append(", ");
            inventory.append(BuiltInRegistries.ITEM.getKey(stack.getItem()))
                    .append(" x").append(stack.getCount());
        }
        return inventory.isEmpty() ? "empty" : inventory.toString();
    }

    @Override
    public void close() {
        client.close();
    }

    private static final class ChatTurn {
        private final UUID playerId;
        private final String message;
        private String baseConversationId;
        private boolean silent;
        private boolean systemEvent;
        private String fallbackCommand;
        private Runnable onSuccess;
        private Runnable onFailure;

        private ChatTurn(UUID playerId, String message) {
            this.playerId = playerId;
            this.message = message;
        }
    }

    private record CommandOutcome(boolean known, boolean succeeded, int value) {}
}
