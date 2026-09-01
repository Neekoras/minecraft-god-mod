package dev.aigod;

import java.util.UUID;

final class Quest {
    enum Objective { KILL, MINE, COLLECT, STAT }
    private final UUID playerId;
    private final String challenge;
    private final Objective objective;
    private final String target;
    private final int amount;
    private final long deadlineMillis;
    private final String rewardCommand;
    private final String punishmentCommand;
    private final int collectionBaseline;
    private int progress;

    Quest(UUID playerId, String challenge, Objective objective, String target, int amount,
          long deadlineMillis, String rewardCommand, String punishmentCommand,
          int collectionBaseline) {
        this.playerId = playerId;
        this.challenge = challenge;
        this.objective = objective;
        this.target = target;
        this.amount = amount;
        this.deadlineMillis = deadlineMillis;
        this.rewardCommand = rewardCommand;
        this.punishmentCommand = punishmentCommand;
        this.collectionBaseline = collectionBaseline;
    }

    boolean record(Objective event, String eventTarget) {
        if (objective != event || !target.equals(eventTarget) || complete()) return false;
        progress++;
        return true;
    }

    /** Progress for objectives polled as a running total (COLLECT inventory count, STAT value). */
    boolean recordTotal(int currentCount) {
        if ((objective != Objective.COLLECT && objective != Objective.STAT) || complete()) return false;
        int updated = Math.min(amount, Math.max(0, currentCount - collectionBaseline));
        if (updated == progress) return false;
        progress = updated;
        return true;
    }

    boolean expired(long nowMillis) {
        return !complete() && nowMillis >= deadlineMillis;
    }

    void forceComplete() {
        progress = amount;
    }

    UUID playerId() { return playerId; }
    String challenge() { return challenge; }
    Objective objective() { return objective; }
    String target() { return target; }
    int amount() { return amount; }
    int progress() { return progress; }
    long deadlineMillis() { return deadlineMillis; }
    String rewardCommand() { return rewardCommand; }
    String punishmentCommand() { return punishmentCommand; }
    boolean complete() { return progress >= amount; }
}
