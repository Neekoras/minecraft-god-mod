# Minecraft, with ChatGPT

[mcgodmod.com](https://mcgodmod.com) is a public Minecraft Java 26.2 server with
one AI character shared by the whole world. Talk in normal server chat. There is
no command or prefix.

```text
<Dennis> can I have a diamond pickaxe?
[AI God] bring me twelve sheep before sunset. then we'll talk pickaxes.
```

The mod runs only on the server, so players do not install anything.

## The game

The world has one communal challenge every Minecraft day. It stays pinned at the
top of every player's screen in a native boss bar, shows combined progress, and
ends at sundown. The AI chooses each challenge as the next step in the world's
survival arc toward the Ender Dragon.

Everyone contributes. Success runs the chosen reward for every online player.
Failure gives the AI a turn to punish the server; if the API is unavailable, the
stored fallback punishment runs instead.

Players can also ask for personal favors. The AI may answer, act immediately, or
create a timed personal challenge with its own reward and punishment. Personal
challenges do not replace the shared goal.

The AI also reacts to joins, deaths, and visible advancements. It can inspect the
block a player is looking at, remember the shared conversation across restarts,
and schedule one-time or repeating actions.

Rapid messages from the same player are combined into one turn after two seconds
of quiet. Repeated identical lines are not counted or described to the model.

## Operator access

The Responses API model receives these tools:

- `run_command`: run any installed Minecraft command with level-4 permission.
- `command_help`: read the live Brigadier command tree before using unfamiliar syntax.
- `inspect_view`: inspect the speaker's targeted block and nearby entities.
- `show_text`: create temporary native floating text.
- `create_challenge`, `complete_challenge`, `cancel_challenge`: manage one personal task.
- `create_daily_goal`: set the shared boss-bar goal at dawn.
- `schedule_event`, `cancel_scheduled_event`: wake the AI later with fresh world state.
- `stay_silent`: finish without writing to chat.

Tool results return to the model, which can keep acting before it speaks. Commands
are unrestricted; there is no allowlist or local tool-call cap.

Each turn includes every online player's health, food, XP, position, biome,
dimension, inventory, held item, and active challenge, plus weather, time,
schedules, and the current shared goal. The speaker's targeted block and nearby
entities are included automatically. This is server-world vision, not a client
screenshot. Responses are broadcast to everyone, while the prompt keeps the
current speaker's identity explicit.

## Run locally

Requirements: Java 25, Minecraft Java 26.2, Fabric Loader, Fabric API, and an
OpenAI API key.

```bash
export OPENAI_API_KEY="sk-..."
export AI_GOD_MODEL="gpt-5.6-terra"
export AI_GOD_ADMIN_PASSWORD="local-password"
mkdir -p run
printf 'eula=true\n' > run/eula.txt
./gradlew runServer
```

Join `localhost:25565`. The disposable development world is under `run/`.

Build the production JAR with:

```bash
./gradlew test build
```

The artifact is `build/libs/ai-god-0.1.0.jar`.

Optional settings:

```text
AI_GOD_MODEL=gpt-5.6-terra
AI_GOD_NAME=AI God
AI_GOD_COMPACT_THRESHOLD=100000
AI_GOD_ADMIN_PORT=8765
AI_GOD_ADMIN_PASSWORD=...
```

## Memory and saved state

One OpenAI Conversation is shared by every player. Its ID and game state live in
the world folder:

```text
ai-god-conversation.txt
ai-god-quests.json
ai-god-daily.json
ai-god-schedules.json
```

Server-side compaction starts at 100,000 rendered tokens. The conversation
continues after compaction and normal server restarts.

## Admin

`admin.mcgodmod.com` is a password-protected, read-only view of the shared
conversation. It groups each player or server input with the model reply,
reasoning summary, tool calls, and tool results that belong to that turn. Turns
can be filtered by player or error. It also shows the shared goal, live players,
health, inventory summaries, the AI queue, and scheduled events.

The page reads the OpenAI Conversation directly and adds live state from the mod;
there is no second chat-log database. OpenAI does not expose private
chain-of-thought, so the page shows only returned reasoning summaries.

Locally it listens on `127.0.0.1:8765` and uses HTTP Basic authentication with
username `admin` and `AI_GOD_ADMIN_PASSWORD`.

## Production

Production is one `t3.medium` Amazon Linux server in `us-west-1` managed through
AWS Systems Manager. GitHub Actions builds and tests each relevant `main` push,
uploads the exact JAR to private S3, and restarts Minecraft with that artifact.

Queued workflows check the current `main` SHA before deployment. Superseded
commits still finish their tests but do not restart the game. Deployment stages
the JAR and secrets before stopping Minecraft, then waits for port 25565 instead
of using a fixed delay. The Java process normally stops in one to two seconds and
takes about 23 seconds to accept players again.

The server uses systemd `Restart=always`, so crashes restart automatically.
Normal deployments still require one short restart because a Fabric mod JAR
cannot be hot-swapped safely in a running world.

The prepared fresh-world seed is `42203442493`, a Java 26.2 plains-village spawn
chosen to give new players shelter and a useful starting area. `deploy/user-data.sh`
uses it for new servers. Replacing an existing production world is a separate,
one-time operation; ordinary deploys never reset player builds.

The landing page is in `website/`. Cloudflare Pages publishes only changes under
that directory.
