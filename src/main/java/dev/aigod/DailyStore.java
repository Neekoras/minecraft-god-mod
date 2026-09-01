package dev.aigod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Persists, per player, the last Minecraft day a daily challenge was issued. */
final class DailyStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path path;
    private final Logger logger;

    DailyStore(Path path, Logger logger) {
        this.path = path;
        this.logger = logger;
    }

    Map<UUID, Long> load() {
        if (!Files.exists(path)) return new HashMap<>();
        try {
            Map<UUID, Long> issued = GSON.fromJson(Files.readString(path),
                    new TypeToken<Map<UUID, Long>>() {}.getType());
            return issued == null ? new HashMap<>() : new HashMap<>(issued);
        } catch (IOException | JsonParseException exception) {
            logger.error("Could not load AI God daily state from {}", path, exception);
            return new HashMap<>();
        }
    }

    void save(Map<UUID, Long> issued) {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(issued));
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            logger.error("Could not save AI God daily state to {}", path, exception);
        }
    }
}
