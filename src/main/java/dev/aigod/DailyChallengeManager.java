package dev.aigod;

import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Runs the one communal goal per Minecraft day. At dawn the god is asked to set
 * today's goal; every player's contributions pool into it until sundown. Failed
 * API calls retry once a minute. Quarter-progress milestones are announced, and
 * completion or sundown failure is handed back to the god.
 */
final class DailyChallengeManager {
    private static final int RETRY_TICKS = 1_200;

    private final MinecraftServer server;
    private final GodService god;
    private final DailyStore store;
    private final DailyStore.State state;
    private final ServerBossEvent bossBar = new ServerBossEvent(
            UUID.nameUUIDFromBytes("ai-god-daily-goal".getBytes(StandardCharsets.UTF_8)),
            Component.literal("server goal"), BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS);
    private boolean pending;
    private int lastAttemptTick = -RETRY_TICKS;
    private int lastQuarter;
    private int ticks;

    DailyChallengeManager(MinecraftServer server, GodService god, DailyStore store) {
        this.server = server;
        this.god = god;
        this.store = store;
        this.state = store.load();
        ServerGoal goal = state.activeGoal;
        this.lastQuarter = goal == null || goal.amount() == 0 ? 0 : goal.progress() * 4 / goal.amount();
    }

    void tick() {
        if (++ticks % 20 != 0) return;
        long now = server.overworld().getOverworldClockTime();
        ServerGoal goal = state.activeGoal;
        if (goal != null) {
            boolean changed = pollTotals(goal);
            if (goal.complete()) {
                finish(goal, true);
            } else if (goal.expired(now)) {
                finish(goal, false);
            } else {
                updateBossBar(goal, now);
                if (changed) {
                    announceMilestone(goal);
                    store.save(state);
                }
            }
            return;
        }
        long today = DayCycle.day(now);
        if (!DayCycle.beforeSundown(now) || state.lastIssuedDay >= today || pending) return;
        if (ticks - lastAttemptTick < RETRY_TICKS) return;
        ServerPlayer speaker = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
        if (speaker == null) return;
        lastAttemptTick = ticks;
        pending = true;
        god.requestDailyGoal(speaker, DayCycle.sundownOf(now), today, List.copyOf(state.pastGoals),
                () -> pending = false,
                () -> pending = false);
    }

    /** Called by GodService when the god sets today's goal via create_daily_goal. */
    ServerGoal createGoal(JsonObject arguments, long deadlineDayTime, long day) {
        if (state.activeGoal != null) {
            throw new IllegalArgumentException("Today's server goal already exists.");
        }
        Quest.Objective objective = Quest.Objective.valueOf(required(arguments, "objective"));
        String target = objective == Quest.Objective.STAT
                ? required(arguments, "target")
                : QuestManager.normalizedId(required(arguments, "target"));
        QuestManager.validateTarget(objective, target);
        int amount = arguments.get("amount").getAsInt();
        if (amount < 1) throw new IllegalArgumentException("amount must be positive");
        ServerGoal goal = new ServerGoal(
                required(arguments, "challenge"), objective, target, amount, day, deadlineDayTime,
                command(arguments, "reward_command"), command(arguments, "punishment_command"));
        pollTotals(goal);
        state.activeGoal = goal;
        state.remember(day, goal.challenge());
        lastQuarter = 0;
        store.save(state);
        return goal;
    }

    void recordKill(String entityId) {
        recordEvent(Quest.Objective.KILL, entityId);
    }

    void recordMine(String blockId) {
        recordEvent(Quest.Objective.MINE, blockId);
    }

    String statusLine() {
        ServerGoal goal = state.activeGoal;
        if (goal == null) return "no server goal is active right now";
        long ticksLeft = Math.max(0, goal.deadlineDayTime() - server.overworld().getOverworldClockTime());
        return "%s — %d/%d %s; %d game ticks until sundown; every player's contributions count together"
                .formatted(goal.challenge(), goal.progress(), goal.amount(),
                        QuestManager.prettyTarget(goal.target()), ticksLeft);
    }

    JsonObject adminState() {
        JsonObject value = new JsonObject();
        ServerGoal goal = state.activeGoal;
        value.addProperty("active", goal != null);
        if (goal == null) return value;
        value.addProperty("challenge", goal.challenge());
        value.addProperty("objective", goal.objective().name().toLowerCase());
        value.addProperty("target", QuestManager.prettyTarget(goal.target()));
        value.addProperty("progress", goal.progress());
        value.addProperty("amount", goal.amount());
        value.addProperty("ticks_left", Math.max(0,
                goal.deadlineDayTime() - server.overworld().getOverworldClockTime()));
        return value;
    }

    private void recordEvent(Quest.Objective objective, String target) {
        ServerGoal goal = state.activeGoal;
        if (goal != null && goal.recordEvent(objective, target)) {
            announceMilestone(goal);
            store.save(state);
        }
    }

    private boolean pollTotals(ServerGoal goal) {
        if (goal.objective() != Quest.Objective.COLLECT && goal.objective() != Quest.Objective.STAT) {
            return false;
        }
        boolean changed = false;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            int current = goal.objective() == Quest.Objective.COLLECT
                    ? QuestManager.count(player, goal.target())
                    : player.getStats().getValue(QuestManager.resolveStat(goal.target()));
            changed |= goal.updateTotal(player.getUUID(), current);
        }
        return changed;
    }

    private void announceMilestone(ServerGoal goal) {
        if (goal.amount() == 0 || goal.complete()) return;
        int quarter = goal.progress() * 4 / goal.amount();
        if (quarter <= lastQuarter) return;
        lastQuarter = quarter;
        server.getPlayerList().broadcastSystemMessage(Component.literal(
                "§eserver goal: %d/%d %s".formatted(
                        goal.progress(), goal.amount(), QuestManager.prettyTarget(goal.target()))), false);
    }

    /** Keeps the HUD boss bar naming the goal, tracking progress, and reddening toward sundown. */
    private void updateBossBar(ServerGoal goal, long now) {
        bossBar.setName(Component.literal("%s  •  %d/%d %s".formatted(
                goal.challenge(), goal.progress(), goal.amount(), QuestManager.prettyTarget(goal.target()))));
        bossBar.setProgress(goal.amount() == 0 ? 0.0F
                : Math.min(1.0F, (float) goal.progress() / goal.amount()));
        long ticksLeft = Math.max(0, goal.deadlineDayTime() - now);
        bossBar.setColor(ticksLeft > 6_000 ? BossEvent.BossBarColor.GREEN
                : ticksLeft > 2_000 ? BossEvent.BossBarColor.YELLOW
                : BossEvent.BossBarColor.RED);
        for (ServerPlayer player : new ArrayList<>(bossBar.getPlayers())) {
            if (player.hasDisconnected()) bossBar.removePlayer(player);
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            bossBar.addPlayer(player);
        }
    }

    private void finish(ServerGoal goal, boolean succeeded) {
        state.activeGoal = null;
        store.save(state);
        bossBar.removeAllPlayers();
        if (succeeded) {
            server.getPlayerList().broadcastSystemMessage(
                    Component.literal("§6server goal complete. the reward is granted to all"), false);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                god.quests().runOperatorCommand(goal.rewardCommand(), player);
            }
            god.goalCompleted(goal);
        } else {
            server.getPlayerList().broadcastSystemMessage(
                    Component.literal("§cthe sun sets on a failed server goal. judgment falls on all"), false);
            god.goalFailed(goal);
        }
    }

    private static String required(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).getAsString().isBlank()) {
            throw new IllegalArgumentException("Missing " + key);
        }
        return object.get(key).getAsString();
    }

    private static String command(JsonObject object, String key) {
        String command = required(object, key).strip();
        return command.startsWith("/") ? command.substring(1) : command;
    }
}
