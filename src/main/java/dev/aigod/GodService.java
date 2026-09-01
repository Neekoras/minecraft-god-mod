package dev.aigod;

import com.google.gson.JsonArray;
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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class GodService implements AutoCloseable {
    private final MinecraftServer server;
    private final String godName;
    private final OpenAiGodClient client;
    private final QuestManager quests;
    private final DailyChallengeManager daily;
    private final ConversationStore conversationStore;
    private final ScheduleStore scheduleStore;
    private final ArrayDeque<ChatTurn> queue = new ArrayDeque<>();
    private final Map<UUID, Long> pendingDailyDeadline = new HashMap<>();
    private final List<DeferredCommand> deferredCommands = new ArrayList<>();
    private final List<ScheduledEvent> scheduledEvents = new ArrayList<>();
    private final Map<UUID, Set<String>> completedAdvancements = new HashMap<>();
    private String conversationId;
    private boolean processing;
    private int ticks;
    private volatile String adminState = "{\"players\":[],\"scheduled_events\":[]}";

    GodService(MinecraftServer server, String apiKey, String model, String godName, int compactThreshold,
               QuestStore questStore, DailyStore dailyStore, ConversationStore conversationStore,
               ScheduleStore scheduleStore) {
        this.server = server;
        this.godName = godName;
        this.client = new OpenAiGodClient(apiKey, model, godName, compactThreshold);
        this.quests = new QuestManager(server, questStore, this::deliverConsequence);
        this.daily = new DailyChallengeManager(server, this, quests, dailyStore);
        this.conversationStore = conversationStore;
        this.scheduleStore = scheduleStore;
        this.scheduledEvents.addAll(scheduleStore.load());
        this.conversationId = conversationStore.load();
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
                of this day. create_quest posts the one announcement; do not announce it with
                run_command or repeat it in your reply.
                %s
                """.formatted(day, player.getGameProfile().name(), history));
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
        completedAdvancements.put(player.getUUID(), currentAdvancements(player));
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
        ticks++;
        runDeferredCommands();
        runScheduledEvents();
        if (ticks % 20 == 0) {
            detectAdvancements();
            refreshAdminState();
        }
    }

    String adminState() {
        return adminState;
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
                case "inspect_view" -> inspectView(player);
                case "schedule_event" -> scheduleEvent(call.arguments(), player);
                case "cancel_scheduled_event" -> cancelScheduledEvent(call.arguments());
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
        String tag = "ai_god_text_" + UUID.randomUUID().toString().substring(0, 8);
        runCommand("kill @e[type=minecraft:text_display,tag=ai_god_text,distance=..16]", player);
        String command = "execute anchored eyes positioned ^ ^1 ^5 run summon minecraft:text_display ~ ~ ~ "
                + "{billboard:\"center\",text:{text:%s,color:\"%s\"},shadow:false,background:0,"
                + "see_through:true,line_width:160,Tags:[\"ai_god_text\",\"%s\"]}"
                .formatted(new JsonPrimitive(text).toString(), color, tag);
        CommandOutcome outcome = runCommand(command, player);
        if (!outcome.known() || outcome.succeeded()) {
            deferredCommands.add(new DeferredCommand(ticks + 20 * 12, "kill @e[tag=" + tag + "]"));
        }
        return !outcome.known() || outcome.succeeded()
                ? "ok: subtle floating text created; it disappears automatically after 12 seconds"
                : "error: Minecraft could not create the text display";
    }

    private String inspectView(ServerPlayer player) {
        HitResult hit = player.pick(32, 1, false);
        String lookingAt = "nothing within 32 blocks";
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            var position = blockHit.getBlockPos();
            lookingAt = BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(position).getBlock())
                    + " at " + position.getX() + " " + position.getY() + " " + position.getZ();
        }
        List<String> nearby = player.level().getEntities(player, player.getBoundingBox().inflate(16)).stream()
                .limit(20)
                .map(entity -> BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()) + " at "
                        + "%.0f %.0f %.0f".formatted(entity.getX(), entity.getY(), entity.getZ()))
                .toList();
        return "looking_at=" + lookingAt + "; nearby_entities=" + nearby;
    }

    private String scheduleEvent(JsonObject arguments, ServerPlayer player) {
        if (scheduledEvents.size() >= 64) return "error: the server already has 64 scheduled events";
        int delay = arguments.get("delay_seconds").getAsInt();
        int repeat = arguments.get("repeat_seconds").getAsInt();
        if (delay < 1 || delay > 86_400) return "error: delay_seconds must be between 1 and 86400";
        if (repeat != 0 && (repeat < 10 || repeat > 86_400)) {
            return "error: repeat_seconds must be 0 or between 10 and 86400";
        }
        String instruction = arguments.get("instruction").getAsString().strip();
        if (instruction.isBlank()) return "error: instruction cannot be blank";
        String id = UUID.randomUUID().toString().substring(0, 8);
        scheduledEvents.add(new ScheduledEvent(id, player.getUUID(), player.getGameProfile().name(), instruction,
                System.currentTimeMillis() + delay * 1_000L, repeat * 1_000L));
        saveScheduledEvents();
        refreshAdminState();
        return "ok: scheduled event " + id + (repeat == 0 ? " once" : " repeating every " + repeat + " seconds");
    }

    private String cancelScheduledEvent(JsonObject arguments) {
        String id = arguments.get("event_id").getAsString();
        boolean removed = scheduledEvents.removeIf(event -> event.id().equals(id));
        if (removed) saveScheduledEvents();
        refreshAdminState();
        return removed ? "ok: cancelled scheduled event " + id : "error: no scheduled event named " + id;
    }

    private void runDeferredCommands() {
        Iterator<DeferredCommand> iterator = deferredCommands.iterator();
        while (iterator.hasNext()) {
            DeferredCommand command = iterator.next();
            if (command.dueTick > ticks) continue;
            runServerCommand(command.command);
            iterator.remove();
        }
    }

    private void runServerCommand(String command) {
        CommandSourceStack source = server.createCommandSourceStack()
                .withPermission(PermissionSet.ALL_PERMISSIONS)
                .withSuppressedOutput();
        try {
            Commands.validateParseResults(server.getCommands().getDispatcher().parse(command, source));
            server.getCommands().performPrefixedCommand(source, command);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException ignored) {
            // A cleanup command can safely expire if its entity no longer exists.
        }
    }

    private void runScheduledEvents() {
        long now = System.currentTimeMillis();
        List<ScheduledEvent> due = scheduledEvents.stream().filter(event -> event.dueAtMillis() <= now).toList();
        boolean handled = false;
        for (ScheduledEvent event : due) {
            ServerPlayer player = server.getPlayerList().getPlayer(event.playerId());
            if (player == null) continue;
            handled = true;
            ChatTurn turn = new ChatTurn(event.playerId(), "Scheduled event " + event.id()
                    + " is due now: " + event.instruction());
            turn.systemEvent = true;
            queue.addLast(turn);
            scheduledEvents.remove(event);
            if (event.repeatMillis() > 0) scheduledEvents.add(event.next(now));
        }
        if (handled) {
            saveScheduledEvents();
            refreshAdminState();
            processNext();
        }
    }

    private void saveScheduledEvents() {
        scheduleStore.save(scheduledEvents);
    }

    private void detectAdvancements() {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Set<String> now = currentAdvancements(player);
            Set<String> known = completedAdvancements.computeIfAbsent(player.getUUID(), ignored -> new HashSet<>(now));
            for (String advancement : now) {
                if (known.add(advancement)) {
                    ChatTurn turn = new ChatTurn(player.getUUID(), player.getGameProfile().name()
                            + " just unlocked advancement " + advancement
                            + ". React briefly if it is interesting. You may celebrate with particles or sound, or stay silent.");
                    turn.systemEvent = true;
                    queue.addLast(turn);
                }
            }
        }
        processNext();
    }

    private Set<String> currentAdvancements(ServerPlayer player) {
        Set<String> completed = new HashSet<>();
        for (var advancement : server.getAdvancements().getAllAdvancements()) {
            if (advancement.value().display().isPresent()
                    && player.getAdvancements().getOrStartProgress(advancement).isDone()) {
                completed.add(advancement.id().toString());
            }
        }
        return completed;
    }

    private void refreshAdminState() {
        JsonObject state = new JsonObject();
        state.addProperty("daytime_ticks", server.overworld().getOverworldClockTime());
        state.addProperty("raining", server.overworld().isRaining());
        state.addProperty("thundering", server.overworld().isThundering());
        state.addProperty("queue_depth", queue.size());
        JsonArray players = new JsonArray();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            JsonObject value = new JsonObject();
            value.addProperty("name", player.getGameProfile().name());
            value.addProperty("health", player.getHealth() / 2.0F);
            value.addProperty("max_health", player.getMaxHealth() / 2.0F);
            value.addProperty("hunger", player.getFoodData().getFoodLevel());
            value.addProperty("x", Math.round(player.getX()));
            value.addProperty("y", Math.round(player.getY()));
            value.addProperty("z", Math.round(player.getZ()));
            value.addProperty("dimension", player.level().dimension().identifier().toString());
            value.addProperty("holding", heldItem(player));
            value.addProperty("quest", quests.status(player));
            players.add(value);
        }
        state.add("players", players);
        JsonArray schedules = new JsonArray();
        for (ScheduledEvent event : scheduledEvents) {
            JsonObject value = new JsonObject();
            value.addProperty("id", event.id());
            value.addProperty("player", event.playerName());
            value.addProperty("instruction", event.instruction());
            value.addProperty("due_in_seconds", Math.max(0,
                    (event.dueAtMillis() - System.currentTimeMillis()) / 1_000));
            value.addProperty("repeat_seconds", event.repeatMillis() / 1_000);
            schedules.add(value);
        }
        state.add("scheduled_events", schedules);
        adminState = state.toString();
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
        return player.createCommandSourceStack()
                .withPermission(PermissionSet.ALL_PERMISSIONS)
                .withSuppressedOutput();
    }

    private String createQuest(ChatTurn turn, JsonObject arguments, ServerPlayer player) {
        Long dailyDeadline = pendingDailyDeadline.remove(player.getUUID());
        Quest quest = quests.create(player, arguments, dailyDeadline);
        turn.silent = true;
        player.sendSystemMessage(Component.literal("§d[%s] §f%s"
                .formatted(godName, MinecraftChatText.fromModel(quest.challenge()))));
        if (quest.kind() == Quest.Kind.DAILY) dailyFanfare(player, quest);
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

    private void dailyFanfare(ServerPlayer player, Quest quest) {
        String name = player.getGameProfile().name();
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
        String schedules = scheduledEvents.isEmpty() ? "" : "\nActive scheduled events:\n"
                + scheduledEvents.stream()
                .map(event -> "- " + event.id() + " for " + event.playerName() + ": "
                        + event.instruction() + " (due in " + Math.max(0,
                        (event.dueAtMillis() - System.currentTimeMillis()) / 1_000) + "s; repeat "
                        + event.repeatMillis() / 1_000 + "s)")
                .collect(java.util.stream.Collectors.joining("\n"));
        return """
                %s

                Live server state:
                difficulty=%s, day=%d, sky=%s, daytime_ticks=%d, raining=%s, thundering=%s
                online_players=%d
                %s%s
                """.formatted(
                lead.formatted(speaker.getGameProfile().name(), turn.message),
                server.getWorldData().getDifficulty(), DayCycle.day(level.getOverworldClockTime()),
                DayCycle.phase(level.getOverworldClockTime()), level.getOverworldClockTime(),
                level.isRaining(), level.isThundering(),
                server.getPlayerCount(), players, schedules);
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
    private record DeferredCommand(long dueTick, String command) {}

}
