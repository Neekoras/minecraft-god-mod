package dev.aigod;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
    private String previousResponseId;
    private boolean processing;

    GodService(MinecraftServer server, String apiKey, String model, String godName, int compactThreshold,
               String commandCatalog, QuestStore questStore, DailyStore dailyStore,
               ConversationStore conversationStore) {
        this.server = server;
        this.godName = godName;
        this.client = new OpenAiGodClient(apiKey, model, godName, compactThreshold, commandCatalog);
        this.quests = new QuestManager(server, questStore, this::deliverConsequence);
        this.daily = new DailyChallengeManager(server, this, quests, dailyStore);
        this.conversationStore = conversationStore;
        this.previousResponseId = conversationStore.load();
    }

    void hear(ServerPlayer player, String message) {
        queue.addLast(new ChatTurn(player.getUUID(), message));
        processNext();
    }

    void requestDailyChallenge(ServerPlayer player, long deadlineDayTime, long day,
                               List<String> pastChallenges, Runnable onIssued, Runnable onFailed) {
        pendingDailyDeadline.put(player.getUUID(), deadlineDayTime);
        String history = pastChallenges.isEmpty()
                ? "This is their first daily challenge ever; make it a memorable initiation."
                : "Their recent daily challenges, oldest first, which you must NOT repeat or closely echo:\n- "
                        + String.join("\n- ", pastChallenges);
        ChatTurn turn = new ChatTurn(player.getUUID(), """
                A new Minecraft day dawns (server day %d). Issue today's daily challenge to %s with
                create_quest now. Make it genuinely fun and genuinely hard, scaled to how long the
                server has lived and to their gear in the live state below, and achievable before
                sundown. Set time_limit_minutes to any value; the deadline is overridden to sundown
                of this day. Proclaim the challenge in chat with theatrical flair.
                %s
                """.formatted(day, player.getGameProfile().getName(), history));
        turn.systemEvent = true;
        turn.onSuccess = onIssued;
        turn.onFailure = onFailed;
        queue.addLast(turn);
        processNext();
    }

    void advancementEarned(ServerPlayer player, String title, String description) {
        ChatTurn turn = new ChatTurn(player.getUUID(), """
                %s just earned the advancement "%s" (%s). React if it moves you: praise, envy,
                a small gift, an ominous remark, or stay_silent for routine milestones.
                """.formatted(player.getGameProfile().getName(), title, description));
        turn.systemEvent = true;
        queue.addLast(turn);
        processNext();
    }

    private void deliverConsequence(ServerPlayer player, Quest quest) {
        player.sendSystemMessage(Component.literal("§cThe sun sets on your failure. %s passes judgment."
                .formatted(godName)));
        ChatTurn turn = new ChatTurn(player.getUUID(), """
                The sun has set and %s FAILED today's daily challenge: "%s" (progress %d/%d %s).
                Deliver a fitting, dramatic consequence right now using run_command, matched to the
                challenge they failed (mob ambushes, lightning, traps, losses). Announce it in chat.
                """.formatted(player.getGameProfile().getName(), quest.challenge(),
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
                """.formatted(player.getGameProfile().getName(), deathMessage));
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
        turn.baseResponseId = previousResponseId;
        client.respond(player.getUUID(), snapshot(player, turn), previousResponseId)
                .whenComplete((response, error) -> server.execute(() -> handle(turn, response, error)));
    }

    private void handle(ChatTurn turn, OpenAiGodClient.ResponseTurn response, Throwable error) {
        ServerPlayer player = server.getPlayerList().getPlayer(turn.playerId);
        if (error != null || player == null) {
            previousResponseId = turn.baseResponseId;
            if (player != null) {
                if (turn.fallbackCommand != null) {
                    quests.runOperatorCommand(turn.fallbackCommand, player);
                } else if (!turn.systemEvent) {
                    Throwable cause = error != null && error.getCause() != null ? error.getCause() : error;
                    player.sendSystemMessage(Component.literal("§cThe AI God cannot answer: "
                            + (cause == null ? "speaker vanished" : cause.getMessage())));
                }
            }
            pendingDailyDeadline.remove(turn.playerId);
            if (turn.onFailure != null) turn.onFailure.run();
            finishTurn();
            return;
        }

        previousResponseId = response.responseId();
        boolean requestsSilence = response.toolCalls().stream()
                .anyMatch(call -> call.name().equals("stay_silent"));
        if (!response.message().isBlank() && !turn.silent && !requestsSilence) say(response.message());

        if (response.toolCalls().isEmpty()) {
            conversationStore.save(previousResponseId);
            pendingDailyDeadline.remove(turn.playerId);
            if (turn.onSuccess != null) turn.onSuccess.run();
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
        command = command.replace("{player}", player.getGameProfile().getName());
        server.getCommands().performPrefixedCommand(player.createCommandSourceStack().withPermission(4), command);
        return "ok: dispatched as level-4 operator: " + command;
    }

    private String createQuest(JsonObject arguments, ServerPlayer player) {
        Long dailyDeadline = pendingDailyDeadline.remove(player.getUUID());
        Quest quest = quests.create(player, arguments, dailyDeadline);
        player.sendSystemMessage(Component.literal("§eObjective: %s %s × %d."
                .formatted(quest.objective().name().toLowerCase(), quest.target(), quest.amount())));
        if (quest.kind() == Quest.Kind.DAILY) dailyFanfare(player, quest);
        return "ok: %s quest created for %s: %s (%s %s x%d)%s".formatted(
                quest.kind() == Quest.Kind.DAILY ? "daily" : "ad-hoc",
                player.getGameProfile().getName(), quest.challenge(),
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

    private void dailyFanfare(ServerPlayer player, Quest quest) {
        String name = player.getGameProfile().getName();
        quests.runOperatorCommand("title " + name + " times 10 70 20", player);
        quests.runOperatorCommand("title " + name + " subtitle {\"text\":"
                + new JsonPrimitive(quest.challenge()) + ",\"color\":\"gold\"}", player);
        quests.runOperatorCommand("title " + name + " title {\"text\":\"Daily Challenge\",\"color\":\"red\",\"bold\":true}", player);
        quests.runOperatorCommand("playsound minecraft:entity.ender_dragon.growl master " + name, player);
    }

    private void finishTurn() {
        queue.removeFirst();
        processing = false;
        processNext();
    }

    private void say(String message) {
        server.getPlayerList().broadcastSystemMessage(
                Component.literal("§d[%s] §f".formatted(godName) + message), false);
    }

    private String snapshot(ServerPlayer speaker, ChatTurn turn) {
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
                    .append(" holding=[").append(heldItem(player)).append(']')
                    .append(" inventory=[").append(inventory(player)).append(']')
                    .append(" quest=[").append(quests.status(player)).append(']');
        }
        String lead = turn.systemEvent
                ? "Divine scheduling event concerning %s: %s"
                : "New ordinary server chat message from %s: %s";
        return """
                %s

                Live server state:
                difficulty=%s, day=%d, sky=%s, daytime_ticks=%d, raining=%s, thundering=%s
                online_players=%d
                %s
                """.formatted(
                lead.formatted(speaker.getGameProfile().getName(), turn.message),
                server.getWorldData().getDifficulty(), DayCycle.day(level.getDayTime()),
                DayCycle.phase(level.getDayTime()), level.getDayTime(),
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
        private String baseResponseId;
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
}
