package dev.aigod;

import java.util.UUID;

record ScheduledEvent(
        String id,
        UUID playerId,
        String playerName,
        String instruction,
        long dueAtMillis,
        long repeatMillis
) {
    ScheduledEvent next(long now) {
        return new ScheduledEvent(id, playerId, playerName, instruction,
                now + repeatMillis, repeatMillis);
    }
}
