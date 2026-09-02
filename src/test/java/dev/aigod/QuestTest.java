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
                "minecraft:custom/minecraft:jump", 100, Long.MAX_VALUE,
                "say won", "say lost", 250);

        assertTrue(quest.recordTotal(300));
        assertEquals(50, quest.progress());
        assertTrue(quest.recordTotal(350));
        assertTrue(quest.complete());
    }

    @Test
    void challengesExpireByWallClock() {
        Quest quest = new Quest(UUID.randomUUID(), "Old bargain", Quest.Objective.MINE,
                "minecraft:stone", 10, 1_000, "say won", "say lost", 0);

        assertFalse(quest.expired(999));
        assertTrue(quest.expired(1_000));
    }

    @Test
    void completedQuestsNeverExpireAndForceCompleteFinishesInstantly() {
        Quest quest = new Quest(UUID.randomUUID(), "Tribute", Quest.Objective.COLLECT,
                "minecraft:emerald", 5, Long.MAX_VALUE,
                "say won", "say lost", 0);

        quest.forceComplete();
        assertTrue(quest.complete());
        assertFalse(quest.expired(Long.MAX_VALUE));
    }

    @Test
    void assassinationChallengesOnlyCountTheNamedVictim() {
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
