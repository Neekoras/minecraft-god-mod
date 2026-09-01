package dev.aigod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Persists the server-wide daily goal, the day it was issued, and recent goal texts. */
final class DailyStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int HISTORY_LIMIT = 7;

    static final class State {
        long lastIssuedDay = -1;
        List<String> pastGoals = new ArrayList<>();
        ServerGoal activeGoal;

        void remember(long day, String goal) {
            lastIssuedDay = day;
            pastGoals.add(goal);
            while (pastGoals.size() > HISTORY_LIMIT) pastGoals.remove(0);
        }
    }

    private final Path path;
    private final Logger logger;

    DailyStore(Path path, Logger logger) {
        this.path = path;
        this.logger = logger;
    }

    State load() {
        if (!Files.exists(path)) return new State();
        try {
            State state = GSON.fromJson(Files.readString(path), State.class);
            return state == null ? new State() : state;
        } catch (IOException | JsonParseException exception) {
            logger.error("Could not load AI God daily state from {}", path, exception);
            return new State();
        }
    }

    void save(State state) {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(state));
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            logger.error("Could not save AI God daily state to {}", path, exception);
        }
    }
}
