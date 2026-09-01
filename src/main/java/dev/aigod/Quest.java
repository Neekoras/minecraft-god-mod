package dev.aigod;

import java.util.UUID;

final class Quest {
    enum Objective { KILL, MINE, COLLECT, STAT }
    enum Kind { ADHOC, DAILY }
    private final UUID playerId;
    private final String challenge;
    private final Objective objective;
    private final String target;
    private final int amount;
    private final long deadlineMillis;
    private final long deadlineDayTime;
    private final Kind kind;
    private final String rewardCommand;
    private final String punishmentCommand;
    private final int collectionBaseline;
    private String targetPlayer;
    private int progress;

    Quest(UUID playerId, String challenge, Objective objective, String target, int amount,
          long deadlineMillis, String rewardCommand, String punishmentCommand,
          int collectionBaseline) {
        this(playerId, challenge, objective, target, amount, deadlineMillis, 0, Kind.ADHOC,
                rewardCommand, punishmentCommand, collectionBaseline);
    }

    Quest(UUID playerId, String challenge, Objective objective, String target, int amount,
          long deadlineMillis, long deadlineDayTime, Kind kind,
          String rewardCommand, String punishmentCommand, int collectionBaseline) {
        this.playerId = playerId;
        this.challenge = challenge;
        this.objective = objective;
        this.target = target;
        this.amount = amount;
        this.deadlineMillis = deadlineMillis;
        this.deadlineDayTime = deadlineDayTime;
        this.kind = kind;
        this.rewardCommand = rewardCommand;
        this.punishmentCommand = punishmentCommand;
        this.collectionBaseline = collectionBaseline;
    }

    boolean record(Objective event, String eventTarget, String victimName) {
        if (objective != event || !target.equals(eventTarget) || complete()) return false;
        if (targetPlayer != null && !targetPlayer.isBlank()
                && (victimName == null || !targetPlayer.equalsIgnoreCase(victimName))) {
            return false;
        }
        progress++;
        return true;
    }

    void setTargetPlayer(String name) {
        this.targetPlayer = name;
    }

    String targetPlayer() {
        return targetPlayer == null || targetPlayer.isBlank() ? null : targetPlayer;
    }

    /** Progress for objectives polled as a running total (COLLECT inventory count, STAT value). */
    boolean recordTotal(int currentCount) {
        if ((objective != Objective.COLLECT && objective != Objective.STAT) || complete()) return false;
        int updated = Math.min(amount, Math.max(0, currentCount - collectionBaseline));
        if (updated == progress) return false;
        progress = updated;
        return true;
    }

    boolean expired(long nowMillis, long nowDayTime) {
        if (complete()) return false;
        if (deadlineDayTime > 0) return nowDayTime >= deadlineDayTime;
        return nowMillis >= deadlineMillis;
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
    long deadlineDayTime() { return deadlineDayTime; }
    Kind kind() { return kind == null ? Kind.ADHOC : kind; }
    String rewardCommand() { return rewardCommand; }
    String punishmentCommand() { return punishmentCommand; }
    boolean complete() { return progress >= amount; }
}
