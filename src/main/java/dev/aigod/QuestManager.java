package dev.aigod;

import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class QuestManager {
    private final MinecraftServer server;
    private final QuestStore store;
    private final Map<UUID, Quest> quests = new HashMap<>();
    private int ticks;

    QuestManager(MinecraftServer server, QuestStore store) {
        this.server = server;
        this.store = store;
        for (Quest quest : store.load()) quests.put(quest.playerId(), quest);
    }

    Optional<Quest> active(UUID playerId) {
        return Optional.ofNullable(quests.get(playerId));
    }

    Quest create(ServerPlayer player, JsonObject arguments) {
        if (quests.containsKey(player.getUUID())) {
            throw new IllegalArgumentException("You already have an active quest.");
        }

        Quest.Objective objective = Quest.Objective.valueOf(requiredString(arguments, "objective"));
        String target = normalizedId(requiredString(arguments, "target"));
        validateTarget(objective, target);
        int amount = positiveInt(arguments, "amount");
        int minutes = positiveInt(arguments, "time_limit_minutes");
        int baseline = objective == Quest.Objective.COLLECT ? count(player, target) : 0;
        Quest quest = new Quest(
                player.getUUID(),
                requiredString(arguments, "challenge"),
                objective,
                target,
                amount,
                System.currentTimeMillis() + minutes * 60_000L,
                command(arguments, "reward_command"),
                command(arguments, "punishment_command"),
                baseline
        );
        quests.put(player.getUUID(), quest);
        save();
        return quest;
    }

    void recordKill(ServerPlayer player, String entityId) {
        record(player, Quest.Objective.KILL, entityId);
    }

    void recordMine(ServerPlayer player, String blockId) {
        record(player, Quest.Objective.MINE, blockId);
    }

    void tick() {
        if (++ticks % 20 != 0) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Quest quest = quests.get(player.getUUID());
            if (quest == null) continue;
            if (quest.objective() == Quest.Objective.COLLECT
                    && quest.recordCollected(count(player, quest.target()))) {
                changed(player, quest);
            }
            if (!quest.complete() && System.currentTimeMillis() >= quest.deadlineMillis()) {
                player.sendSystemMessage(Component.literal("§cThe AI God finds you wanting. Punishment falls."));
                runOperatorCommand(quest.punishmentCommand(), player);
                quests.remove(player.getUUID());
                save();
            }
        }
    }

    String status(ServerPlayer player) {
        Quest quest = quests.get(player.getUUID());
        if (quest == null) return "The AI God has placed no burden upon you.";
        long seconds = Math.max(0, (quest.deadlineMillis() - System.currentTimeMillis()) / 1_000);
        return "%s — %d/%d %s; %dm %02ds remain".formatted(
                quest.challenge(), quest.progress(), quest.amount(), quest.target(), seconds / 60, seconds % 60);
    }

    private void record(ServerPlayer player, Quest.Objective objective, String target) {
        Quest quest = quests.get(player.getUUID());
        if (quest != null && quest.record(objective, target)) changed(player, quest);
    }

    private void changed(ServerPlayer player, Quest quest) {
        if (quest.complete()) {
            player.sendSystemMessage(Component.literal("§6Quest complete. The AI God grants your reward."));
            runOperatorCommand(quest.rewardCommand(), player);
            quests.remove(player.getUUID());
        } else {
            player.sendSystemMessage(Component.literal("§eDivine progress: %d/%d".formatted(quest.progress(), quest.amount())));
        }
        save();
    }

    private void runOperatorCommand(String command, ServerPlayer player) {
        String expanded = command.replace("{player}", player.getGameProfile().getName());
        server.getCommands().performPrefixedCommand(
                player.createCommandSourceStack().withPermission(4),
                expanded.startsWith("/") ? expanded.substring(1) : expanded
        );
    }

    private static int count(ServerPlayer player, String itemId) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId)) count += stack.getCount();
        }
        return count;
    }

    private static void validateTarget(Quest.Objective objective, String target) {
        ResourceLocation id = ResourceLocation.tryParse(target);
        boolean valid = id != null && switch (objective) {
            case KILL -> BuiltInRegistries.ENTITY_TYPE.containsKey(id);
            case MINE -> BuiltInRegistries.BLOCK.containsKey(id);
            case COLLECT -> BuiltInRegistries.ITEM.containsKey(id);
        };
        if (!valid) throw new IllegalArgumentException("The god chose an unknown target: " + target);
    }

    private static String normalizedId(String value) {
        return value.contains(":") ? value : "minecraft:" + value;
    }

    private static int positiveInt(JsonObject object, String key) {
        int value = object.get(key).getAsInt();
        if (value < 1) throw new IllegalArgumentException(key + " must be positive");
        return value;
    }

    private static String requiredString(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).getAsString().isBlank()) {
            throw new IllegalArgumentException("Missing " + key);
        }
        return object.get(key).getAsString();
    }

    private static String command(JsonObject object, String key) {
        String command = requiredString(object, key).strip();
        return command.startsWith("/") ? command.substring(1) : command;
    }

    private void save() {
        store.save(quests.values());
    }
}
