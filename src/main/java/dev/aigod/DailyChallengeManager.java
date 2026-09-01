package dev.aigod;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Issues one challenge per player per Minecraft day. During daylight, any online
 * player who has not received today's challenge and has no active quest gets one,
 * with the deadline pinned to sundown. Failed API calls retry once a minute.
 */
final class DailyChallengeManager {
    private static final int RETRY_TICKS = 1_200;

    private final MinecraftServer server;
    private final GodService god;
    private final QuestManager quests;
    private final DailyStore store;
    private final Map<UUID, Long> issuedDay;
    private final Set<UUID> pending = new HashSet<>();
    private final Map<UUID, Integer> lastAttemptTick = new HashMap<>();
    private int ticks;

    DailyChallengeManager(MinecraftServer server, GodService god, QuestManager quests, DailyStore store) {
        this.server = server;
        this.god = god;
        this.quests = quests;
        this.store = store;
        this.issuedDay = store.load();
    }

    void tick() {
        if (++ticks % 20 != 0) return;
        long dayTime = server.overworld().getOverworldClockTime();
        if (!DayCycle.beforeSundown(dayTime)) return;
        long today = DayCycle.day(dayTime);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            if (issuedDay.getOrDefault(id, -1L) >= today) continue;
            if (pending.contains(id) || quests.active(id).isPresent()) continue;
            if (ticks - lastAttemptTick.getOrDefault(id, -RETRY_TICKS) < RETRY_TICKS) continue;
            lastAttemptTick.put(id, ticks);
            pending.add(id);
            god.requestDailyChallenge(player, DayCycle.sundownOf(dayTime),
                    () -> issued(id, today),
                    () -> pending.remove(id));
        }
    }

    private void issued(UUID id, long day) {
        pending.remove(id);
        issuedDay.put(id, day);
        store.save(issuedDay);
    }
}
