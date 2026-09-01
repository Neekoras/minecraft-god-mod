package dev.aigod;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DailyStoreTest {
    @TempDir
    Path directory;

    @Test
    void roundTripsTheSharedGoalState() {
        Path path = directory.resolve("daily.json");
        DailyStore store = new DailyStore(path, LoggerFactory.getLogger(DailyStoreTest.class));

        DailyStore.State state = new DailyStore.State();
        state.remember(4, "mine 64 cobblestone together");
        state.activeGoal = new ServerGoal("mine 64 cobblestone together", Quest.Objective.MINE,
                "minecraft:cobblestone", 64, 4, 108_000, "give {player} bread 4",
                "summon lightning_bolt ~ ~ ~");
        store.save(state);

        DailyStore.State loaded = store.load();
        assertEquals(4, loaded.lastIssuedDay);
        assertEquals(1, loaded.pastGoals.size());
        assertEquals("mine 64 cobblestone together", loaded.activeGoal.challenge());
        assertEquals(64, loaded.activeGoal.amount());
    }

    @Test
    void migratesLegacyPerPlayerDayState() throws Exception {
        UUID player = UUID.randomUUID();
        Path path = directory.resolve("daily.json");
        Files.writeString(path, "{\"" + player + "\": 12}");

        DailyStore.State state = new DailyStore(path, LoggerFactory.getLogger(DailyStoreTest.class)).load();

        assertEquals(12, state.lastIssuedDay);
        assertTrue(state.pastGoals.isEmpty());
        assertNull(state.activeGoal);
    }
}
