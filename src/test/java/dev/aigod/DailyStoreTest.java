package dev.aigod;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DailyStoreTest {
    @TempDir
    Path directory;

    @Test
    void loadsThePreviousDayOnlyFormat() throws Exception {
        UUID player = UUID.randomUUID();
        Path path = directory.resolve("daily.json");
        Files.writeString(path, "{\"" + player + "\": 12}");

        DailyStore.Record record = new DailyStore(path, LoggerFactory.getLogger(DailyStoreTest.class))
                .load().get(player);

        assertEquals(12, record.lastIssuedDay);
        assertTrue(record.pastChallenges.isEmpty());
    }
}
