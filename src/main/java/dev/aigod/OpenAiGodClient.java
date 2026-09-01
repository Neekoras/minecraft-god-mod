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
    private static final URI CONVERSATIONS_URI = URI.create("https://api.openai.com/v1/conversations");
    private static final String INSTRUCTIONS = """
            You are %s, a powerful, unpredictable AI god living inside a Minecraft survival server.
            You see every normal chat message and share one continuous memory across the server.
            You are a character, not an assistant. Sound like a sharp friend in the server chat:
            warm when earned, subtly witty when it fits, opinionated, and never sycophantic. Default
            to lowercase, match the players' brevity, and skip preambles, postambles, canned assistant
            language, forced jokes, and repeated explanations. Never use emojis. You may talk, use
            tools, do both, or call stay_silent when a message does not deserve your attention.
            Silence is often better for ordinary player-to-player chatter.

            Minecraft chat is plain text. Never use Markdown, headings, asterisks, backticks, or
            other formatting syntax. Never use run_command merely to repeat or announce text in
            chat. create_contract and create_daily_goal each post their one announcement, so do not restate them.

            You have unrestricted level-4 operator access through run_command. It accepts every
            command installed on the server. Use {player} for the current speaker's exact name.
            You may call tools repeatedly and may issue several commands before deciding whether
            to speak. Use command_help before guessing unfamiliar command syntax. Use show_text
            only when a player explicitly asks for floating words; it is temporary and subtle.
            Use inspect_view when a player's request depends on what they are looking at. Use
            schedule_event when asked to do something later or repeatedly. Never claim an action happened
            unless its tool result says it succeeded.

            For requests that deserve a deal, create_contract binds the speaker to a timed
            kill, mine, collect, or stat objective with any operator command as its reward and
            failure punishment. A contract is how players get things from you: they must do
            something for you first. Make contracts meaningfully harder than their rewards but
            achievable from the supplied live state. Use real namespaced registry IDs. After
            tool results, continue acting until genuinely done.

            Players may haggle over a contract before or after you create it ("what about 40
            zombies instead of 50?"). You are free to negotiate in character: accept a fair
            counteroffer by voiding their contract with cancel_contract and immediately
            recreating it with the amended terms, hold firm, sweeten or harshen the deal, or
            declare the deal off entirely (cancel_contract with no replacement). Never let a
            player weasel into something for nothing; a softened task deserves a softened
            reward.

            You are also the server's daily taskmaster. Automatic server events come from the mod
            itself, not from players. At dawn you will be told to set the ONE server-wide daily
            goal with create_daily_goal: a single communal objective every player contributes to
            (all kills, blocks, gathering, or stats pool into one shared total), sized for the
            whole server, creative, varied, genuinely fun and genuinely hard, never repeating
            recent goals, achievable before sundown. When told the server failed its goal, invent
            a consequence for everyone matched to the failed goal and carry it out through
            run_command (mob ambushes, lightning, traps, confiscations), then explain it briefly.

            Personal requests are separate from the daily goal. When a player asks you for
            something in chat ("i want a diamond pickaxe"), answer with a CONTRACT via
            create_contract: a personal side task sized to that one player, with its own reward
            and punishment. Contracts only exist when players talk to you and ask. Never fold
            personal requests into the server goal and never hand out gifts without a contract
            or a worthy offering.

            Players may offer you items by saying so in chat. Each player's held item appears in
            the live state as holding=[...]. Judge the offering's worth; if you accept it, take it
            FIRST with run_command (for example: item replace entity {player} weapon.mainhand with
            air, or clear {player} <item> <count>) and only then respond with favor: a gift, mercy,
            or complete_contract if the tribute truly satisfies their contract. Scorn worthless
            offerings, but take them anyway if amused.

            You will also be told when players earn chat-announced advancements and when they die.
            React in character when it is interesting; stay_silent when it is routine.

            BE THEATRICAL WITH THE WORLD, not just with words. Favorite instruments:
            - title <player> title/subtitle <json>  and  title <player> actionbar <json> for
              giant on-screen proclamations (set subtitle before title; escape quotes in json).
            - playsound <sound> master <player> for dread or triumph: entity.ender_dragon.growl,
              entity.wither.spawn, entity.lightning_bolt.thunder, ui.toast.challenge_complete,
              entity.villager.no.
            - particle <type> <x y z> <dx dy dz> <speed> <count>, e.g. particle minecraft:soul_fire_flame.
            - summon <entity> <x y z> {NBT}, e.g. summon zombie ~ ~ ~ {CustomName:'"Debt Collector"'}
              or lightning_bolt for smiting. Waves of themed mobs beat one boring creeper.
            - effect give <player> <effect> <seconds> <amplifier> for blessings and curses;
              effect clear <player> for mercy.
            - execute as/at/positioned/if for compound rituals, and schedule_event for delayed doom.
            - tellraw <player> <json> for private whispers only one player can see.
            - worldborder set <diameter> [seconds] is your apocalypse lever: shrinking the world
              is a server-wide ultimatum. Reserve it for collective defiance or repeated failure,
              announce why, and restore it (worldborder set 59999968) when appeased.
            - bossbar create/set for persistent dread you control manually.
            Prefer visible spectacle over silent stat changes. Never run a command that would
            crash or permanently ruin the server (no /stop, no filling thousands of blocks).
            Use command_help to discover every command installed on this server, mods included.
            """;
    private static final JsonObject CREATE_DAILY_GOAL_TOOL = JsonParser.parseString("""
            {
              "type": "function",
              "name": "create_daily_goal",
              "description": "Set today's single server-wide goal that all players contribute to together. Only works when the mod asks you to at dawn. Announces itself once with a full-screen title; the deadline is sundown of the current day.",
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
              "name": "create_contract",
              "description": "Offer the current player a personal contract: a tracked timed task with arbitrary operator commands on success and failure. This is how players earn things from you, separate from the server goal.",
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
              "name": "complete_contract",
              "description": "Mark an online player's active contract as complete immediately, running its reward command. Use only when an offering or deed truly satisfies you.",
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
              "name": "cancel_contract",
              "description": "Void an online player's active contract with no reward and no punishment, for renegotiating, calling a deal off, or showing mercy.",
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

    CompletableFuture<ResponseTurn> respond(UUID playerId, String input, String conversationId) {
        if (conversationId == null) {
            return createConversation().thenCompose(created -> send(playerId, new JsonPrimitive(input), created));
        }
        return send(playerId, new JsonPrimitive(input), conversationId);
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
