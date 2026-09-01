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

        assertFalse(quest.record(Quest.Objective.KILL, "minecraft:cow"));
        assertTrue(quest.record(Quest.Objective.KILL, "minecraft:sheep"));
        assertTrue(quest.record(Quest.Objective.KILL, "minecraft:sheep"));
        assertTrue(quest.complete());
        assertEquals(2, quest.progress());
    }

    @Test
    void collectionProgressCountsOnlyItemsGainedAfterAssignment() {
        Quest quest = new Quest(UUID.randomUUID(), "Gather tribute", Quest.Objective.COLLECT,
                "minecraft:emerald", 5, Long.MAX_VALUE, "say won", "say lost", 3);

        assertTrue(quest.recordCollected(6));
        assertEquals(3, quest.progress());
        assertTrue(quest.recordCollected(8));
        assertTrue(quest.complete());
    }
}
