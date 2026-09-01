package dev.aigod;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class ConversationStore {
    private final Path path;
    private final Logger logger;

    ConversationStore(Path path, Logger logger) {
        this.path = path;
        this.logger = logger;
    }

    String load() {
        if (!Files.exists(path)) return null;
        try {
            String responseId = Files.readString(path).strip();
            return responseId.isEmpty() ? null : responseId;
        } catch (IOException exception) {
            logger.error("Could not load AI God conversation state from {}", path, exception);
            return null;
        }
    }

    void save(String responseId) {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.writeString(temporary, responseId);
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            logger.error("Could not save AI God conversation state to {}", path, exception);
        }
    }
}
