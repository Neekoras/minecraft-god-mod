package dev.aigod;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestTest {
    @Test
    void onlyMatchingEventsAdvanceAQuest() {
        Quest quest = new Quest(UUID.randomUUID(), "Cull the flock", Quest.Objective.KILL,
                "minecraft:sheep", 2, Long.MAX_VALUE, "give {player} diamond 1",
                "summon lightning_bolt ~ ~ ~", 0);

        assertFalse(quest.record(Quest.Objective.KILL, "minecraft:cow", null));
        assertTrue(quest.record(Quest.Objective.KILL, "minecraft:sheep", null));
        assertTrue(quest.record(Quest.Objective.KILL, "minecraft:sheep", null));
        assertTrue(quest.complete());
        assertEquals(2, quest.progress());
    }

    @Test
    void collectionProgressCountsOnlyItemsGainedAfterAssignment() {
        Quest quest = new Quest(UUID.randomUUID(), "Gather tribute", Quest.Objective.COLLECT,
                "minecraft:emerald", 5, Long.MAX_VALUE, "say won", "say lost", 3);

        assertTrue(quest.recordTotal(6));
        assertEquals(3, quest.progress());
        assertTrue(quest.recordTotal(8));
        assertTrue(quest.complete());
    }

    @Test
    void statObjectivesProgressFromTheirBaseline() {
        Quest quest = new Quest(UUID.randomUUID(), "Leap for me", Quest.Objective.STAT,
                "minecraft:custom/minecraft:jump", 100, Long.MAX_VALUE, 12_000, Quest.Kind.DAILY,
                "say won", "say lost", 250);

        assertTrue(quest.recordTotal(300));
        assertEquals(50, quest.progress());
        assertTrue(quest.recordTotal(350));
        assertTrue(quest.complete());
    }

    @Test
    void dailyQuestsExpireByGameTimeNotWallClock() {
        Quest quest = new Quest(UUID.randomUUID(), "Slay by sundown", Quest.Objective.KILL,
                "minecraft:zombie", 3, Long.MAX_VALUE, 12_000, Quest.Kind.DAILY,
                "give {player} diamond 1", "summon lightning_bolt ~ ~ ~", 0);

        assertEquals(Quest.Kind.DAILY, quest.kind());
        assertFalse(quest.expired(Long.MAX_VALUE, 11_999));
        assertTrue(quest.expired(0, 12_000));
    }

    @Test
    void adhocQuestsExpireByWallClock() {
        Quest quest = new Quest(UUID.randomUUID(), "Old bargain", Quest.Objective.MINE,
                "minecraft:stone", 10, 1_000, "say won", "say lost", 0);

        assertEquals(Quest.Kind.ADHOC, quest.kind());
        assertFalse(quest.expired(999, Long.MAX_VALUE));
        assertTrue(quest.expired(1_000, 0));
    }

    @Test
    void completedQuestsNeverExpireAndForceCompleteFinishesInstantly() {
        Quest quest = new Quest(UUID.randomUUID(), "Tribute", Quest.Objective.COLLECT,
                "minecraft:emerald", 5, Long.MAX_VALUE, 12_000, Quest.Kind.DAILY,
                "say won", "say lost", 0);

        quest.forceComplete();
        assertTrue(quest.complete());
        assertFalse(quest.expired(Long.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test
    void assassinationContractsOnlyCountTheNamedVictim() {
        Quest quest = new Quest(UUID.randomUUID(), "End Dennis", Quest.Objective.KILL,
                "minecraft:player", 1, Long.MAX_VALUE, "give {player} diamond 5",
                "kill {player}", 0);
        quest.setTargetPlayer("Dennis_Test");

        assertFalse(quest.record(Quest.Objective.KILL, "minecraft:player", "Someone_Else"));
        assertFalse(quest.record(Quest.Objective.KILL, "minecraft:player", null));
        assertTrue(quest.record(Quest.Objective.KILL, "minecraft:player", "Dennis_Test"));
        assertTrue(quest.complete());
    }
}
