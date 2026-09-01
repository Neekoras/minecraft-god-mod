package dev.aigod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persists, per player, the last day a daily challenge was issued and recent challenge texts. */
final class DailyStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int HISTORY_LIMIT = 7;

    static final class Record {
        long lastIssuedDay = -1;
        List<String> pastChallenges = new ArrayList<>();

        void remember(long day, String challenge) {
            lastIssuedDay = day;
            pastChallenges.add(challenge);
            while (pastChallenges.size() > HISTORY_LIMIT) pastChallenges.remove(0);
        }
    }

    private final Path path;
    private final Logger logger;

    DailyStore(Path path, Logger logger) {
        this.path = path;
        this.logger = logger;
    }

    Map<UUID, Record> load() {
        if (!Files.exists(path)) return new HashMap<>();
        try {
            JsonObject saved = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            Map<UUID, Record> records = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : saved.entrySet()) {
                Record record;
                if (entry.getValue().isJsonPrimitive()) {
                    record = new Record();
                    record.lastIssuedDay = entry.getValue().getAsLong();
                } else {
                    record = GSON.fromJson(entry.getValue(), Record.class);
                    if (record == null) record = new Record();
                    if (record.pastChallenges == null) record.pastChallenges = new ArrayList<>();
                }
                records.put(UUID.fromString(entry.getKey()), record);
            }
            return records;
        } catch (IOException | JsonParseException | IllegalArgumentException exception) {
            logger.error("Could not load AI God daily state from {}", path, exception);
            return new HashMap<>();
        }
    }

    void save(Map<UUID, Record> records) {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(records));
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            logger.error("Could not save AI God daily state to {}", path, exception);
        }
    }
}
