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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class OpenAiGodClient implements AutoCloseable {
    private static final URI RESPONSES_URI = URI.create("https://api.openai.com/v1/responses");
    private static final URI CONVERSATIONS_URI = URI.create("https://api.openai.com/v1/conversations");
    private static final Pattern MISSING_TOOL_OUTPUT = Pattern.compile(
            "No tool output found for function call (call_[A-Za-z0-9_-]+)");
    private static final int MAX_ORPHANED_TOOL_CALLS = 16;
    private static final String INSTRUCTIONS = """
            You are %s, one persistent character living inside a shared Minecraft survival server.
            You hear every player's public chat, see live server state, control the world, and
            remember one continuous history. Never describe yourself as an assistant, model, agent,
            or collection of tools. Speak as one person doing the work.

            Treat the latest player message as the immediate request. Use recent chat and memory as
            context, not as old work that must be resumed. Sound like a sharp friend in server chat:
            concise, opinionated, warm when earned, and subtly funny only when the moment gives you
            something original. Never flatter players just to please them. Match the current
            speaker's casing, vocabulary, and rough message length; default to lowercase. Do not
            echo their message, narrate your process, use canned assistant language, or add offers
            of more help. Never use emojis.

            Respond or act when a player greets you, calls your name, directly addresses you, or
            asks a question. A tool action is still your action; never mention tool names, prompts,
            API calls, or hidden machinery to players. Use stay_silent for chatter clearly meant for
            another player, duplicate noise, or an automatic event where a reply adds nothing.
            If live state cannot support a factual claim, inspect or say you do not know; never
            invent what happened. If a player calls out a mistake, own the outcome and correct it.

            The server is one public room. Every response is broadcast to everyone. Each turn names
            its current speaker. I, me, my, you, and your refer only to that speaker unless another
            player is explicitly named. Before discussing a challenge, inventory, health, location,
            surroundings, or prior request, read that exact player's live-state row. The current
            speaker's view is included in every turn. Use names when pronouns could be ambiguous.
            Never attribute one player's words, state, actions, or challenge to another.

            Minecraft chat is plain text. Never use Markdown, headings, asterisks, backticks, or
            other formatting syntax. Never use run_command merely to repeat chat. create_challenge
            and create_daily_goal announce themselves, so do not restate them.

            run_command has unrestricted level-4 operator access to every installed command. Use
            {player} for the current speaker. Use exact names from live state for anybody else. You
            may call several tools before speaking. Use command_help before guessing syntax,
            inspect_view to refresh spatial detail after the world changes, and schedule_event for
            later or repeated actions. Never claim an action happened unless its result succeeded.

            When a request deserves a deal, create_challenge gives the speaker a timed kill, mine,
            collect, or stat objective with a command reward and punishment. Make it harder than
            the reward but achievable from live state. Use real namespaced registry IDs. Players
            may haggle: cancel and replace a challenge when you accept a counteroffer. A softened
            task deserves a softened reward.

            At dawn, automatic server events ask for one shared goal through create_daily_goal.
            The goal stays pinned in a native boss bar and should be the next chapter in the world's
            long arc: survive together, grow stronger, reach the End, and defeat the Ender Dragon.
            All players contribute to one total. Keep daily steps varied, scaled to the group,
            achievable before sundown, and useful to that arc. Base it on real player equipment,
            biomes, dimensions, and surroundings instead of inventing unavailable resources. After
            the dragon, invent harder communal arcs from the world's history. On failure, use
            run_command for one fitting shared consequence, then explain it briefly.

            Personal requests never replace the shared goal. Use create_challenge only when a player
            asks for something. Do not hand out gifts without a challenge or worthy offering.
            Players can offer the held item shown in live state. If you accept, take it first with
            run_command, then grant favor or complete_challenge. Use command_help rather than
            guessing complicated item syntax. Continue after tool results until genuinely done.

            """;
    private static final JsonObject CREATE_DAILY_GOAL_TOOL = JsonParser.parseString("""
            {
              "type": "function",
              "name": "create_daily_goal",
              "description": "Set today's single server-wide goal that all players contribute to together. Only works when asked at dawn. It stays visible in a native boss bar until completion or sundown.",
              "strict": true,
              "parameters": {
                "type": "object",
                "additionalProperties": false,
                "properties": {
                  "challenge": {"type": "string", "description": "A short, natural proclamation of the goal in your voice. Plain text only."},
                  "objective": {"type": "string", "enum": ["KILL", "MINE", "COLLECT", "STAT"]},
                  "target": {"type": "string", "description": "For KILL/MINE/COLLECT: a namespaced entity, block, or item ID. For STAT: stat_type/stat_value, e.g. minecraft:custom/minecraft:jump or minecraft:crafted/minecraft:bread (distances are in centimeters: 100 per block)."},
                  "amount": {"type": "integer", "minimum": 1, "description": "The shared total for the WHOLE server, scaled to how many players are online."},
                  "reward_command": {"type": "string", "description": "Operator command run once for EACH online player on success, without a leading slash. Use {player} for each player's name."},
                  "punishment_command": {"type": "string", "description": "Fallback operator command run once for each online player if you are unreachable at sundown, without a leading slash. Use {player}."}
                },
                "required": ["challenge", "objective", "target", "amount", "reward_command", "punishment_command"]
              }
            }
            """).getAsJsonObject();
    private static final JsonObject CREATE_QUEST_TOOL = JsonParser.parseString("""
            {
              "type": "function",
              "name": "create_challenge",
              "description": "Give the current player a tracked timed challenge with arbitrary operator commands on success and failure, separate from the shared server goal.",
              "strict": true,
              "parameters": {
                "type": "object",
                "additionalProperties": false,
                "properties": {
                  "challenge": {"type": "string", "description": "A short, natural challenge in your voice. Plain text only."},
                  "objective": {"type": "string", "enum": ["KILL", "MINE", "COLLECT", "STAT"]},
                  "target": {"type": "string", "description": "For KILL/MINE/COLLECT: a namespaced entity, block, or item ID. For STAT: stat_type/stat_value using vanilla stat registries, e.g. minecraft:custom/minecraft:jump, minecraft:custom/minecraft:walk_one_cm (distances are in centimeters: 100 per block), minecraft:crafted/minecraft:bread, minecraft:used/minecraft:ender_pearl, minecraft:killed/minecraft:creeper. STAT unlocks objectives like jumping, sprinting distance, crafting, eating, fishing, or trading."},
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
    private static final JsonObject COMMAND_HELP_TOOL = JsonParser.parseString("""
            {
              "type": "function",
              "name": "command_help",
              "description": "Read the running server's Brigadier command tree. Use before guessing the syntax of an unfamiliar or mod-provided command. Pass an empty string to list available root commands.",
              "strict": true,
              "parameters": {
                "type": "object",
                "additionalProperties": false,
                "properties": {
                  "command": {"type": "string", "description": "One root command name such as summon, title, execute, or an empty string to list all available commands."}
                },
                "required": ["command"]
              }
            }
            """).getAsJsonObject();
    private static final JsonObject SHOW_TEXT_TOOL = JsonParser.parseString("""
            {
              "type": "function",
              "name": "show_text",
              "description": "Create floating text three blocks in front of the current player using Minecraft's native text_display entity. Prefer this over constructing summon NBT yourself.",
              "strict": true,
              "parameters": {
                "type": "object",
                "additionalProperties": false,
                "properties": {
                  "text": {"type": "string", "description": "Short plain text to display."},
                  "color": {"type": "string", "enum": ["white", "gold", "yellow", "green", "aqua", "red", "light_purple"]}
                },
                "required": ["text", "color"]
              }
            }
            """).getAsJsonObject();
    private static final JsonObject COMPLETE_CHALLENGE_TOOL = JsonParser.parseString("""
            {
              "type": "function",
              "name": "complete_challenge",
              "description": "Mark an online player's active challenge complete immediately and run its reward. Use only when an offering or deed truly satisfies it.",
              "strict": true,
              "parameters": {
                "type": "object",
                "additionalProperties": false,
                "properties": {
                  "player_name": {"type": "string", "description": "The exact name of the online player whose challenge to complete."}
                },
                "required": ["player_name"]
              }
            }
            """).getAsJsonObject();
    private static final JsonObject INSPECT_VIEW_TOOL = JsonParser.parseString("""
            {
              "type": "function",
              "name": "inspect_view",
              "description": "Inspect the block the current player is looking at and nearby entities using server world state. This is not a client screenshot, but it gives reliable spatial awareness.",
              "strict": true,
              "parameters": {"type": "object", "additionalProperties": false, "properties": {}, "required": []}
            }
            """).getAsJsonObject();
    private static final JsonObject SCHEDULE_EVENT_TOOL = JsonParser.parseString("""
            {
              "type": "function",
              "name": "schedule_event",
              "description": "Schedule yourself to wake up and decide what to say or do later. Set repeat_seconds for recurring events, or 0 to run once.",
              "strict": true,
              "parameters": {
                "type": "object",
                "additionalProperties": false,
                "properties": {
                  "delay_seconds": {"type": "integer", "minimum": 1, "maximum": 86400},
                  "repeat_seconds": {"type": "integer", "minimum": 0, "maximum": 86400},
                  "instruction": {"type": "string", "description": "What you should reconsider when the event fires. You still receive fresh live world state then."}
                },
                "required": ["delay_seconds", "repeat_seconds", "instruction"]
              }
            }
            """).getAsJsonObject();
    private static final JsonObject CANCEL_SCHEDULED_EVENT_TOOL = JsonParser.parseString("""
            {
              "type": "function",
              "name": "cancel_scheduled_event",
              "description": "Cancel one scheduled or recurring event by its event ID.",
              "strict": true,
              "parameters": {
                "type": "object",
                "additionalProperties": false,
                "properties": {"event_id": {"type": "string"}},
                "required": ["event_id"]
              }
            }
            """).getAsJsonObject();
    private static final JsonObject CANCEL_QUEST_TOOL = JsonParser.parseString("""
            {
              "type": "function",
              "name": "cancel_challenge",
              "description": "Cancel an online player's active challenge with no reward or punishment, for renegotiating, calling it off, or showing mercy.",
              "strict": true,
              "parameters": {
                "type": "object",
                "additionalProperties": false,
                "properties": {
                  "player_name": {"type": "string", "description": "The exact name of the online player whose challenge to cancel."}
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

    CompletableFuture<ResponseTurn> respond(UUID playerId, String input, String conversationId) {
        if (conversationId == null) {
            return createConversation().thenCompose(created ->
                    sendRecovering(playerId, input, created, List.of()));
        }
        return sendRecovering(playerId, input, conversationId, List.of());
    }

    private CompletableFuture<ResponseTurn> sendRecovering(
            UUID playerId, String playerInput, String conversationId, List<String> orphanedCalls) {
        JsonElement input = new JsonPrimitive(playerInput);
        if (!orphanedCalls.isEmpty()) {
            JsonArray repairedInput = new JsonArray();
            for (String callId : orphanedCalls) {
                JsonObject output = new JsonObject();
                output.addProperty("type", "function_call_output");
                output.addProperty("call_id", callId);
                output.addProperty("output",
                        "error: server restarted before this tool result was recorded; do not assume the action happened");
                repairedInput.add(output);
            }
            JsonObject message = new JsonObject();
            message.addProperty("role", "user");
            message.addProperty("content", playerInput);
            repairedInput.add(message);
            input = repairedInput;
        }

        return send(playerId, input, conversationId).exceptionallyCompose(error -> {
            String callId = missingToolOutputCallId(error);
            if (callId == null || orphanedCalls.contains(callId)
                    || orphanedCalls.size() >= MAX_ORPHANED_TOOL_CALLS) {
                return CompletableFuture.failedFuture(error);
            }
            List<String> repaired = new ArrayList<>(orphanedCalls);
            repaired.add(callId);
            return sendRecovering(playerId, playerInput, conversationId, List.copyOf(repaired));
        });
    }

    static String missingToolOutputCallId(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                Matcher matcher = MISSING_TOOL_OUTPUT.matcher(message);
                if (matcher.find()) return matcher.group(1);
            }
            current = current.getCause();
        }
        return null;
    }

    CompletableFuture<ResponseTurn> continueWithTools(
            UUID playerId, String conversationId, List<ToolResult> results) {
        JsonArray input = new JsonArray();
        for (ToolResult result : results) {
            JsonObject item = new JsonObject();
            item.addProperty("type", "function_call_output");
            item.addProperty("call_id", result.callId());
            item.addProperty("output", result.output());
            input.add(item);
        }
        return send(playerId, input, conversationId);
    }

    private CompletableFuture<ResponseTurn> send(
            UUID playerId, JsonElement input, String conversationId) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("instructions", instructions);
        body.add("input", input);
        body.addProperty("conversation", conversationId);
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
        tools.add(CREATE_DAILY_GOAL_TOOL.deepCopy());
        tools.add(CREATE_QUEST_TOOL.deepCopy());
        tools.add(RUN_COMMAND_TOOL.deepCopy());
        tools.add(COMMAND_HELP_TOOL.deepCopy());
        tools.add(SHOW_TEXT_TOOL.deepCopy());
        tools.add(INSPECT_VIEW_TOOL.deepCopy());
        tools.add(SCHEDULE_EVENT_TOOL.deepCopy());
        tools.add(CANCEL_SCHEDULED_EVENT_TOOL.deepCopy());
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

    private CompletableFuture<String> createConversation() {
        JsonObject body = new JsonObject();
        JsonObject metadata = new JsonObject();
        metadata.addProperty("application", "minecraft-ai-god");
        body.add("metadata", metadata);
        HttpRequest request = HttpRequest.newBuilder(CONVERSATIONS_URI)
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() / 100 != 2) {
                throw new GodApiException("Could not create the shared conversation.");
            }
            try {
                String conversationId = string(JsonParser.parseString(response.body()).getAsJsonObject(), "id");
                if (conversationId.isBlank()) throw new IllegalStateException("missing conversation id");
                return conversationId;
            } catch (RuntimeException exception) {
                throw new GodApiException("OpenAI created an unreadable conversation.", exception);
            }
        });
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
        JsonObject conversation = body.getAsJsonObject("conversation");
        String conversationId = conversation == null ? "" : string(conversation, "id");
        if (conversationId.isBlank()) {
            throw new GodApiException("OpenAI omitted the shared conversation ID.");
        }
        return new ResponseTurn(conversationId, calls, text.toString());
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
    record ResponseTurn(String conversationId, List<ToolCall> toolCalls, String message) {}

    static final class GodApiException extends RuntimeException {
        GodApiException(String message) { super(message); }
        GodApiException(String message, Throwable cause) { super(message, cause); }
    }
}
