package dev.aigod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DailyChallengeManagerTest {
    @Test
    void bossBarUsesTheObjectiveInsteadOfGeneratedProse() {
        ServerGoal goal = new ServerGoal(
                "Gather 24 iron ore before sundown. The dragon waits beyond tools made of wood.",
                Quest.Objective.MINE, "minecraft:iron_ore", 24, 9, 12_000,
                "say won", "say lost", false);

        assertEquals("Mine iron ore  •  0/24", DailyChallengeManager.bossBarLabel(goal));
    }
}
