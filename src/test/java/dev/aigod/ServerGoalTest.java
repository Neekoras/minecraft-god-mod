package dev.aigod;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerGoalTest {
    private static ServerGoal goal(Quest.Objective objective, String target, int amount) {
        return new ServerGoal("Together now", objective, target, amount,
                3, 84_000, "give {player} diamond 1", "summon lightning_bolt ~ ~ ~", false);
    }

    @Test
    void killsFromAnyPlayerPoolIntoOneTotal() {
        ServerGoal goal = goal(Quest.Objective.KILL, "minecraft:zombie", 3);
        assertTrue(goal.recordEvent(Quest.Objective.KILL, "minecraft:zombie"));
        assertFalse(goal.recordEvent(Quest.Objective.KILL, "minecraft:cow"));
        assertTrue(goal.recordEvent(Quest.Objective.KILL, "minecraft:zombie"));
        assertTrue(goal.recordEvent(Quest.Objective.KILL, "minecraft:zombie"));
        assertTrue(goal.complete());
        assertEquals(3, goal.progress());
    }

    @Test
    void collectionSumsEachPlayersGainsAboveTheirOwnBaseline() {
        ServerGoal goal = goal(Quest.Objective.COLLECT, "minecraft:cobblestone", 12);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        goal.updateTotal(alice, 10);   // baseline 10
        goal.updateTotal(bob, 0);      // baseline 0
        assertEquals(0, goal.progress());

        assertTrue(goal.updateTotal(alice, 15)); // +5
        assertTrue(goal.updateTotal(bob, 4));    // +4
        assertEquals(9, goal.progress());
        assertFalse(goal.complete());

        assertTrue(goal.updateTotal(bob, 7));    // +7 total
        assertTrue(goal.complete());
        assertEquals(12, goal.progress());
    }

    @Test
    void expiryOnlyBitesIncompleteGoals() {
        ServerGoal goal = goal(Quest.Objective.MINE, "minecraft:stone", 1);
        assertFalse(goal.expired(83_999));
        assertTrue(goal.expired(84_000));
        goal.recordEvent(Quest.Objective.MINE, "minecraft:stone");
        assertFalse(goal.expired(Long.MAX_VALUE));
    }
}
