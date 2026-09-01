package dev.aigod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

final class QuestStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path path;
    private final Logger logger;

    QuestStore(Path path, Logger logger) {
        this.path = path;
        this.logger = logger;
    }

    List<Quest> load() {
        if (!Files.exists(path)) return List.of();
        try {
            Quest[] quests = GSON.fromJson(Files.readString(path), Quest[].class);
            return quests == null ? List.of() : Arrays.asList(quests);
        } catch (IOException | JsonParseException exception) {
            logger.error("Could not load AI God quests from {}", path, exception);
            return List.of();
        }
    }

    void save(Collection<Quest> quests) {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(quests));
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            logger.error("Could not save AI God quests to {}", path, exception);
        }
    }
}
