package dev.aigod;

import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class GodService implements AutoCloseable {
    private final MinecraftServer server;
    private final OpenAiGodClient client;
    private final QuestManager quests;
    private final ConversationStore conversationStore;
    private final ArrayDeque<ChatTurn> queue = new ArrayDeque<>();
    private String previousResponseId;
    private boolean processing;

    GodService(MinecraftServer server, String apiKey, String model, int compactThreshold,
               QuestStore questStore, ConversationStore conversationStore) {
        this.server = server;
        this.client = new OpenAiGodClient(apiKey, model, compactThreshold);
        this.quests = new QuestManager(server, questStore);
        this.conversationStore = conversationStore;
        this.previousResponseId = conversationStore.load();
    }

    void hear(ServerPlayer player, String message) {
        queue.addLast(new ChatTurn(player.getUUID(), message));
        processNext();
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
            queue.removeFirst();
            processNext();
            return;
        }
        processing = true;
        turn.baseResponseId = previousResponseId;
        client.respond(player.getUUID(), snapshot(player, turn.message), previousResponseId)
                .whenComplete((response, error) -> server.execute(() -> handle(turn, response, error)));
    }

    private void handle(ChatTurn turn, OpenAiGodClient.ResponseTurn response, Throwable error) {
        ServerPlayer player = server.getPlayerList().getPlayer(turn.playerId);
        if (error != null || player == null) {
            previousResponseId = turn.baseResponseId;
            if (player != null) {
                Throwable cause = error != null && error.getCause() != null ? error.getCause() : error;
                player.sendSystemMessage(Component.literal("§cThe AI God cannot answer: "
                        + (cause == null ? "speaker vanished" : cause.getMessage())));
            }
            finishTurn();
            return;
        }

        previousResponseId = response.responseId();
        boolean requestsSilence = response.toolCalls().stream()
                .anyMatch(call -> call.name().equals("stay_silent"));
        if (!response.message().isBlank() && !turn.silent && !requestsSilence) say(response.message());

        if (response.toolCalls().isEmpty()) {
            conversationStore.save(previousResponseId);
            finishTurn();
            return;
        }

        List<OpenAiGodClient.ToolResult> results = new ArrayList<>();
        for (OpenAiGodClient.ToolCall call : response.toolCalls()) {
            results.add(new OpenAiGodClient.ToolResult(call.callId(), execute(turn, player, call)));
        }
        client.continueWithTools(player.getUUID(), previousResponseId, results)
                .whenComplete((next, nextError) -> server.execute(() -> handle(turn, next, nextError)));
    }

    private String execute(ChatTurn turn, ServerPlayer player, OpenAiGodClient.ToolCall call) {
        try {
            return switch (call.name()) {
                case "run_command" -> runOperatorCommand(call.arguments(), player);
                case "create_quest" -> createQuest(call.arguments(), player);
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
        command = command.replace("{player}", player.getGameProfile().getName());
        server.getCommands().performPrefixedCommand(player.createCommandSourceStack().withPermission(4), command);
        return "ok: dispatched as level-4 operator: " + command;
    }

    private String createQuest(JsonObject arguments, ServerPlayer player) {
        Quest quest = quests.create(player, arguments);
        player.sendSystemMessage(Component.literal("§eObjective: %s %s × %d."
                .formatted(quest.objective().name().toLowerCase(), quest.target(), quest.amount())));
        return "ok: quest created for %s: %s (%s %s x%d)".formatted(
                player.getGameProfile().getName(), quest.challenge(),
                quest.objective(), quest.target(), quest.amount());
    }

    private void finishTurn() {
        queue.removeFirst();
        processing = false;
        processNext();
    }

    private void say(String message) {
        server.getPlayerList().broadcastSystemMessage(Component.literal("§d[AI God] §f" + message), false);
    }

    private String snapshot(ServerPlayer speaker, String message) {
        ServerLevel level = speaker.serverLevel();
        StringBuilder players = new StringBuilder();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!players.isEmpty()) players.append('\n');
            players.append("- ").append(player.getGameProfile().getName())
                    .append(" health=").append("%.1f/%.1f hearts".formatted(
                            player.getHealth() / 2.0F, player.getMaxHealth() / 2.0F))
                    .append(" hunger=").append(player.getFoodData().getFoodLevel()).append("/20")
                    .append(" xp=").append(player.experienceLevel)
                    .append(" position=").append("%.0f %.0f %.0f".formatted(
                            player.getX(), player.getY(), player.getZ()))
                    .append(" dimension=").append(player.level().dimension().location())
                    .append(" inventory=[").append(inventory(player)).append(']')
                    .append(" quest=[").append(quests.status(player)).append(']');
        }
        return """
                New ordinary server chat message from %s: %s

                Live server state:
                difficulty=%s, daytime_ticks=%d, raining=%s, thundering=%s
                online_players=%d
                %s
                """.formatted(
                speaker.getGameProfile().getName(), message,
                server.getWorldData().getDifficulty(), level.getDayTime(),
                level.isRaining(), level.isThundering(),
                server.getPlayerCount(), players);
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
        private String baseResponseId;
        private boolean silent;

        private ChatTurn(UUID playerId, String message) {
            this.playerId = playerId;
            this.message = message;
        }
    }
}
