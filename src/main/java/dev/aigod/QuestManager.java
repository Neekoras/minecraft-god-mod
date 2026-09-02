package dev.aigod;

import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatType;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
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

    Quest create(ServerPlayer player, JsonObject arguments) {
        if (quests.containsKey(player.getUUID())) {
            throw new IllegalArgumentException("This player already has an active challenge.");
        }

        Quest.Objective objective = Quest.Objective.valueOf(requiredString(arguments, "objective"));
        String target = objective == Quest.Objective.STAT
                ? requiredString(arguments, "target")
                : normalizedId(requiredString(arguments, "target"));
        validateTarget(objective, target);
        int amount = positiveInt(arguments, "amount");
        int minutes = positiveInt(arguments, "time_limit_minutes");
        int baseline = switch (objective) {
            case COLLECT -> count(player, target);
            case STAT -> player.getStats().getValue(resolveStat(target));
            default -> 0;
        };
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
        String targetPlayer = arguments.has("target_player")
                ? arguments.get("target_player").getAsString().strip() : "";
        if (objective == Quest.Objective.KILL
                && target.equals("minecraft:player")
                && !targetPlayer.isEmpty()) {
            if (server.getPlayerList().getPlayerByName(targetPlayer) == null) {
                throw new IllegalArgumentException("No online player named " + targetPlayer);
            }
            quest.setTargetPlayer(targetPlayer);
        }
        quests.put(player.getUUID(), quest);
        save();
        return quest;
    }

    void recordKill(ServerPlayer player, String entityId, String victimName) {
        record(player, Quest.Objective.KILL, entityId, victimName);
    }

    void recordMine(ServerPlayer player, String blockId) {
        record(player, Quest.Objective.MINE, blockId, null);
    }

    void tick() {
        if (++ticks % 20 != 0) return;
        long nowMillis = System.currentTimeMillis();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Quest quest = quests.get(player.getUUID());
            if (quest == null) continue;
            if (quest.objective() == Quest.Objective.COLLECT
                    && quest.recordTotal(count(player, quest.target()))) {
                changed(player, quest);
            }
            if (quest.objective() == Quest.Objective.STAT
                    && quest.recordTotal(player.getStats().getValue(resolveStat(quest.target())))) {
                changed(player, quest);
            }
            if (quest.expired(nowMillis)) {
                quests.remove(player.getUUID());
                save();
                player.sendSystemMessage(Component.literal("§cchallenge failed"));
                runOperatorCommand(quest.punishmentCommand(), player);
            }
        }
    }

    Quest cancel(ServerPlayer player) {
        Quest quest = quests.remove(player.getUUID());
        if (quest == null) {
            throw new IllegalArgumentException(player.getGameProfile().name() + " has no active challenge.");
        }
        save();
        return quest;
    }

    String forceComplete(ServerPlayer player) {
        Quest quest = quests.get(player.getUUID());
        if (quest == null) {
            throw new IllegalArgumentException(player.getGameProfile().name() + " has no active challenge.");
        }
        quest.forceComplete();
        changed(player, quest);
        return "ok: challenge for %s marked complete; reward command ran".formatted(player.getGameProfile().name());
    }

    String status(ServerPlayer player) {
        Quest quest = quests.get(player.getUUID());
        if (quest == null) return "no active challenge";
        long seconds = Math.max(0, (quest.deadlineMillis() - System.currentTimeMillis()) / 1_000);
        return "%s — %d/%d %s; %dm %02ds remain".formatted(
                quest.challenge(), quest.progress(), quest.amount(), prettyTarget(quest.target()),
                seconds / 60, seconds % 60);
    }

    private void record(ServerPlayer player, Quest.Objective objective, String target, String victimName) {
        Quest quest = quests.get(player.getUUID());
        if (quest != null && quest.record(objective, target, victimName)) changed(player, quest);
    }

    private void changed(ServerPlayer player, Quest quest) {
        if (quest.complete()) {
            player.sendSystemMessage(Component.literal("§6challenge complete. reward granted"));
            runOperatorCommand(quest.rewardCommand(), player);
            quests.remove(player.getUUID());
        } else {
            player.sendSystemMessage(Component.literal("§eprogress: %d/%d %s".formatted(
                    quest.progress(), quest.amount(), prettyTarget(quest.target()))));
        }
        save();
    }

    void runOperatorCommand(String command, ServerPlayer player) {
        String expanded = command.replace("{player}", player.getGameProfile().name());
        server.getCommands().performPrefixedCommand(
                player.createCommandSourceStack()
                        .withPermission(PermissionSet.ALL_PERMISSIONS)
                        .withSuppressedOutput(),
                expanded.startsWith("/") ? expanded.substring(1) : expanded
        );
    }

    /** "minecraft:cobblestone" -> "cobblestone", "minecraft:custom/minecraft:jump" -> "jump". */
    static String prettyTarget(String target) {
        String tail = target.substring(target.lastIndexOf('/') + 1);
        return tail.substring(tail.indexOf(':') + 1).replace('_', ' ');
    }

    static int count(ServerPlayer player, String itemId) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId)) count += stack.getCount();
        }
        return count;
    }

    static void validateTarget(Quest.Objective objective, String target) {
        if (objective == Quest.Objective.STAT) {
            resolveStat(target);
            return;
        }
        Identifier id = Identifier.tryParse(target);
        boolean valid = id != null && switch (objective) {
            case KILL -> BuiltInRegistries.ENTITY_TYPE.containsKey(id);
            case MINE -> BuiltInRegistries.BLOCK.containsKey(id);
            case COLLECT -> BuiltInRegistries.ITEM.containsKey(id);
            case STAT -> false;
        };
        if (!valid) throw new IllegalArgumentException("The god chose an unknown target: " + target);
    }

    /**
     * Resolves a "stat_type/stat_value" target like "minecraft:custom/minecraft:jump" or
     * "minecraft:killed/minecraft:zombie" against the vanilla stat registries.
     */
    static Stat<?> resolveStat(String target) {
        String[] parts = target.split("/", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                    "STAT targets must look like stat_type/stat_value, e.g. minecraft:custom/minecraft:jump");
        }
        Identifier typeId = Identifier.tryParse(normalizedId(parts[0]));
        if (typeId == null || !BuiltInRegistries.STAT_TYPE.containsKey(typeId)) {
            throw new IllegalArgumentException("Unknown stat type: " + parts[0]);
        }
        StatType<?> type = BuiltInRegistries.STAT_TYPE.getValue(typeId);
        Identifier valueId = Identifier.tryParse(normalizedId(parts[1]));
        if (valueId == null) throw new IllegalArgumentException("Unknown stat value: " + parts[1]);
        return statOf(type, valueId, target);
    }

    private static <T> Stat<T> statOf(StatType<T> type, Identifier valueId, String target) {
        if (!type.getRegistry().containsKey(valueId)) {
            throw new IllegalArgumentException("The god chose an unknown stat: " + target);
        }
        T value = type.getRegistry().getValue(valueId);
        return type.get(value);
    }

    static String normalizedId(String value) {
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
