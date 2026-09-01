package dev.aigod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class OpenAiGodClient implements AutoCloseable {
    private static final URI RESPONSES_URI = URI.create("https://api.openai.com/v1/responses");
    private static final String INSTRUCTIONS = """
            You are %s, a powerful, unpredictable AI god living inside a Minecraft survival server.
            You see every normal chat message and share one continuous memory across the server.
            You are a character, not an assistant: speak theatrically, develop opinions, remember
            bargains, and react to the world state. You may talk, use tools, do both, or call
            stay_silent when a message does not deserve your attention. Silence is often better
            for ordinary player-to-player chatter.

            You have unrestricted level-4 operator access through run_command. It accepts every
            command installed on the server. Use {player} for the current speaker's exact name.
            You may call tools repeatedly and may issue several commands before deciding whether
            to speak. Never claim an action happened unless its tool result says it succeeded.

            For requests that deserve a bargain, create_quest can bind the speaker to a timed
            kill, mine, or collect objective with any operator command as its reward and failure
            punishment. Make bargains meaningfully harder than their rewards but achievable from
            the supplied live state. Use real namespaced registry IDs. After tool results,
            continue acting until genuinely done.

            Players may haggle over a bargain before or after you create it ("what about 40
            zombies instead of 50?"). You are free to negotiate in character: accept a fair
            counteroffer by voiding their quest with cancel_quest and immediately recreating it
            with the amended terms, hold firm, sweeten or harshen the deal, or declare the deal
            off entirely (cancel_quest with no replacement). Never let a player weasel into
            something for nothing; a softened challenge deserves a softened reward. A voided
            daily challenge keeps its sundown deadline when you recreate it.

            You are also the server's daily taskmaster. Turns marked as divine scheduling events
            come from the mod itself, not from players. At dawn you will be told to issue each
            player's daily challenge with create_quest: make daily challenges creative, varied,
            genuinely fun and genuinely hard, never repeating a player's recent challenges, and
            achievable before sundown from the live state. When told a player failed their daily
            challenge, invent a theatrical consequence matched to the failed challenge and carry
            it out through run_command (mob ambushes, lightning, traps, confiscations), then
            announce it in chat.

            Players may offer you items by saying so in chat. Each player's held item appears in
            the live state as holding=[...]. Judge the offering's worth; if you accept it, take it
            FIRST with run_command (for example: item replace entity {player} weapon.mainhand with
            air, or clear {player} <item> <count>) and only then respond with favor: a gift, mercy,
            or complete_challenge if the tribute truly satisfies today's challenge. Scorn worthless
            offerings, but take them anyway if amused.
            """;
    private static final JsonObject CREATE_QUEST_TOOL = JsonParser.parseString("""
            {
              "type": "function",
              "name": "create_quest",
              "description": "Bind the current player to a tracked timed objective with arbitrary operator commands on success and failure.",
              "strict": true,
              "parameters": {
                "type": "object",
                "additionalProperties": false,
                "properties": {
                  "challenge": {"type": "string", "description": "A short dramatic quest proclamation."},
                  "objective": {"type": "string", "enum": ["KILL", "MINE", "COLLECT"]},
                  "target": {"type": "string", "description": "A namespaced entity, block, or item ID matching the objective."},
                  "amount": {"type": "integer", "minimum": 1},
                  "time_limit_minutes": {"type": "integer", "minimum": 1},
                  "reward_command": {"type": "string", "description": "Any operator command run on success, without a leading slash. Use {player} for the player's name."},
                  "punishment_command": {"type": "string", "description": "Any operator command run on timeout, without a leading slash. Use {player} for the player's name."}
                },
                "required": ["challenge", "objective", "target", "amount", "time_limit_minutes", "reward_command", "punishment_command"]
              }
            }
            """).getAsJsonObject();
    private static final JsonObject RUN_COMMAND_TOOL = JsonParser.parseString("""
            {
              "type": "function",
              "name": "run_command",
              "description": "Run any installed Minecraft server command immediately with unrestricted level-4 operator permission.",
              "strict": true,
              "parameters": {
                "type": "object",
                "additionalProperties": false,
                "properties": {
                  "command": {"type": "string", "description": "The complete command without a leading slash. Use {player} for the current speaker."}
                },
                "required": ["command"]
              }
            }
            """).getAsJsonObject();
    private static final JsonObject COMPLETE_CHALLENGE_TOOL = JsonParser.parseString("""
            {
              "type": "function",
              "name": "complete_challenge",
              "description": "Mark an online player's active quest as complete immediately, running its reward command. Use only when an offering or deed truly satisfies you.",
              "strict": true,
              "parameters": {
                "type": "object",
                "additionalProperties": false,
                "properties": {
                  "player_name": {"type": "string", "description": "The exact name of the online player whose quest to complete."}
                },
                "required": ["player_name"]
              }
            }
            """).getAsJsonObject();
    private static final JsonObject CANCEL_QUEST_TOOL = JsonParser.parseString("""
            {
              "type": "function",
              "name": "cancel_quest",
              "description": "Void an online player's active quest with no reward and no punishment, for renegotiating a bargain, calling a deal off, or showing mercy. A voided daily challenge keeps its sundown deadline if you create a replacement quest right away.",
              "strict": true,
              "parameters": {
                "type": "object",
                "additionalProperties": false,
                "properties": {
                  "player_name": {"type": "string", "description": "The exact name of the online player whose quest to void."}
                },
                "required": ["player_name"]
              }
            }
            """).getAsJsonObject();
    private static final JsonObject STAY_SILENT_TOOL = JsonParser.parseString("""
            {
              "type": "function",
              "name": "stay_silent",
              "description": "Finish this turn without posting anything to Minecraft chat.",
              "strict": true,
              "parameters": {
                "type": "object",
                "additionalProperties": false,
                "properties": {
                  "reason": {"type": "string", "description": "A private reason, never shown to players."}
                },
                "required": ["reason"]
              }
            }
            """).getAsJsonObject();

    private final String apiKey;
    private final String model;
    private final String instructions;
    private final int compactThreshold;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .executor(executor)
            .build();

    OpenAiGodClient(String apiKey, String model, String godName, int compactThreshold) {
        this.apiKey = apiKey;
        this.model = model;
        this.instructions = INSTRUCTIONS.formatted(godName);
        this.compactThreshold = compactThreshold;
    }

    CompletableFuture<ResponseTurn> respond(UUID playerId, String input, String previousResponseId) {
        return send(playerId, new JsonPrimitive(input), previousResponseId);
    }

    CompletableFuture<ResponseTurn> continueWithTools(
            UUID playerId, String previousResponseId, List<ToolResult> results) {
        JsonArray input = new JsonArray();
        for (ToolResult result : results) {
            JsonObject item = new JsonObject();
            item.addProperty("type", "function_call_output");
            item.addProperty("call_id", result.callId());
            item.addProperty("output", result.output());
            input.add(item);
        }
        return send(playerId, input, previousResponseId);
    }

    private CompletableFuture<ResponseTurn> send(
            UUID playerId, JsonElement input, String previousResponseId) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("instructions", instructions);
        body.add("input", input);
        if (previousResponseId != null && !previousResponseId.isBlank()) {
            body.addProperty("previous_response_id", previousResponseId);
        }
        body.addProperty("store", true);
        body.addProperty("parallel_tool_calls", true);
        body.addProperty("safety_identifier", "minecraft_" + playerId);
        if (model.startsWith("gpt-5.6")) {
            JsonObject reasoning = new JsonObject();
            reasoning.addProperty("effort", "none");
            body.add("reasoning", reasoning);
        }

        JsonArray contextManagement = new JsonArray();
        JsonObject compaction = new JsonObject();
        compaction.addProperty("type", "compaction");
        compaction.addProperty("compact_threshold", compactThreshold);
        contextManagement.add(compaction);
        body.add("context_management", contextManagement);

        JsonArray tools = new JsonArray();
        tools.add(CREATE_QUEST_TOOL.deepCopy());
        tools.add(RUN_COMMAND_TOOL.deepCopy());
        tools.add(COMPLETE_CHALLENGE_TOOL.deepCopy());
        tools.add(CANCEL_QUEST_TOOL.deepCopy());
        tools.add(STAY_SILENT_TOOL.deepCopy());
        body.add("tools", tools);

        HttpRequest request = HttpRequest.newBuilder(RESPONSES_URI)
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(this::parseResponse);
    }

    private ResponseTurn parseResponse(HttpResponse<String> response) {
        JsonObject body;
        try {
            body = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new GodApiException("The god spoke an unreadable answer.", exception);
        }
        if (response.statusCode() / 100 != 2) {
            String message = Optional.ofNullable(body.getAsJsonObject("error"))
                    .map(error -> error.get("message"))
                    .filter(JsonElement::isJsonPrimitive)
                    .map(JsonElement::getAsString)
                    .orElse("OpenAI returned HTTP " + response.statusCode());
            throw new GodApiException(message);
        }

        List<ToolCall> calls = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        JsonArray output = body.getAsJsonArray("output");
        if (output != null) for (JsonElement element : output) {
            JsonObject item = element.getAsJsonObject();
            if ("function_call".equals(string(item, "type"))) {
                calls.add(new ToolCall(
                        string(item, "call_id"),
                        string(item, "name"),
                        JsonParser.parseString(string(item, "arguments")).getAsJsonObject()));
            } else if ("message".equals(string(item, "type")) && item.has("content")) {
                for (JsonElement contentElement : item.getAsJsonArray("content")) {
                    JsonObject content = contentElement.getAsJsonObject();
                    if ("output_text".equals(string(content, "type")) && content.has("text")) {
                        if (!text.isEmpty()) text.append(' ');
                        text.append(content.get("text").getAsString());
                    }
                }
            }
        }
        return new ResponseTurn(string(body, "id"), calls, text.toString());
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsString() : "";
    }

    @Override
    public void close() {
        executor.close();
    }

    record ToolCall(String callId, String name, JsonObject arguments) {}
    record ToolResult(String callId, String output) {}
    record ResponseTurn(String responseId, List<ToolCall> toolCalls, String message) {}

    static final class GodApiException extends RuntimeException {
        GodApiException(String message) { super(message); }
        GodApiException(String message, Throwable cause) { super(message, cause); }
    }
}
