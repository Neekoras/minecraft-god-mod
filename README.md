# AI God

Website: [mcgodmod.com](https://mcgodmod.com)

AI God is a server-side Fabric mod that puts one persistent OpenAI-powered god
in ordinary Minecraft chat. There is no bot command and no separate player UI: every
player message becomes a turn, and the god decides whether to answer, act,
offer a bargain, or remain completely silent.

```text
<Dennis> can I have a diamond pickaxe?
[AI God] bring me twelve sheep before sunset. then we'll talk pickaxes.
```

The god is intentionally not sandboxed. It can execute **every command installed
on the server with permission level 4**, including commands from other mods and
data packs.

## Join the live server

Open **Minecraft: Java Edition 26.2**, choose **Multiplayer**, and add:

```text
mcgodmod.com
```

No client mod is needed. The server is public, uses normal Minecraft account
authentication, and currently allows up to 10 players. Just talk in ordinary
server chat; there is no `/god` command or special prefix.

The first time a player joins, the god generates a short personal introduction.
Later joins get a shorter contextual welcome based on the same shared conversation
and current world state. These are normal model turns, not canned announcements.

The same domain hosts the project landing page. Minecraft uses its standard
DNS service record to reach the game server, so players still enter only
`mcgodmod.com`.

## What it can do

The model receives eleven custom tools:

- `run_command` executes any installed server command as the speaking player
  with level-4 permission. Relative coordinates and selectors therefore start
  from that player. The model can call it repeatedly in one turn.
- `command_help` reads the running server's real Brigadier command tree before
  the model uses unfamiliar vanilla, mod, or data-pack syntax.
- `show_text` creates short-lived native `text_display` entities without making
  the model hand-write version-sensitive entity data. Old text is removed first
  and new text disappears after 12 seconds.
- `inspect_view` reports the block in the player's crosshair and nearby entities
  from authoritative server state.
- `schedule_event` wakes the model later, once or repeatedly, with fresh world
  state so ordinary chat such as "say something every minute" works. Schedules
  persist in `ai-god-schedules.json` across server restarts.
- `cancel_scheduled_event` stops one of those events by ID.
- `create_quest` creates a timed personal `KILL`, `MINE`, `COLLECT`, or
  `STAT` objective for one player. Its success reward and timeout punishment
  are unrestricted operator commands. `STAT` objectives track any vanilla
  statistic (`minecraft:custom/minecraft:jump`,
  `minecraft:crafted/minecraft:bread`, sprint distance, fishing, trading, and
  hundreds more).
- `create_daily_goal` sets the one server-wide daily goal that every player
  contributes to together, announced with a full-screen title and due at
  sundown. Same objective types as `create_quest`.
- `complete_challenge` marks an online player's active quest complete and runs
  its reward command, for when an offering or deed satisfies the god.
- `cancel_quest` voids a player's active quest with no reward or punishment,
  so the god can renegotiate bargains, call deals off, or show mercy.
- `stay_silent` ends the turn without putting a god message in chat.

Tool results go back to the model. It can issue more commands after seeing a
result, then speak or choose silence. Command syntax is parsed before execution,
and the real command result goes back to the model so it cannot report a failed
command as successful. There is no local tool-call count cap and no allowlist of
Minecraft commands.

The god sees live context on every turn:

- the new chat message and its speaker;
- every online player's health, hunger, XP, position, dimension, inventory, and
  active quest;
- server difficulty, day time, rain, and thunder;
- the preceding shared server conversation and prior tool results.

Chat turns are queued in order, so simultaneous messages cannot fork or race
the god's shared memory. Model-written chat is kept as plain Minecraft text: no
Markdown renderer, headings, or emoji-heavy formatting.

## The daily server goal

Every Minecraft morning the god sets **one goal for the whole server**, due by
**sundown of that day** (game time, not wall-clock). Every player's kills,
mined blocks, gathered items, or stats pool into the same shared total, and
progress is announced at each quarter in plain units ("server goal: 5/12
cobblestone"). The goal arrives with a full-screen title, a subtitle carrying
the proclamation, and an ender-dragon growl.

- Reach the total before sundown and the goal's reward command runs once for
  every online player.
- Fail, and the god is told about the failure and invents a consequence for
  everyone on the spot, matched to the goal it set: mob ambushes, lightning,
  confiscations, whatever it decides through `run_command`. If the API is
  unreachable at sundown, the goal's stored punishment command runs for each
  player instead, so failure is never free.

Personal requests stay separate: asking the god for something in chat ("i
want a diamond pickaxe") gets an individual side bargain via the normal quest
system, with its own task, reward, and punishment, without touching the
server goal.

The dawn request tells the god the server day number (to scale difficulty),
how many players are online (to size the total), and the last seven goals (so
it does not repeat itself); the active goal and history live in
`ai-god-daily.json`, so restarts do not double-issue. If the goal cannot be
set (API error, empty server), the mod retries once a minute until sundown.

The god's standing instructions include a command playbook (titles, sounds,
particles, themed mob summons, effects, `worldborder` as a server-wide
ultimatum), and `command_help` gives it the real command tree of the running
server, mods included. The live snapshot names the sky phase (dawn, midday,
dusk, night), the server day, and the goal's live progress.

## Offerings and deaths

There are still no commands. Players offer items by saying so in normal chat
("take my offering") while holding the tribute; the god sees each player's
held item in the live snapshot, takes accepted offerings itself via vanilla
commands, and may respond with gifts, mercy, or `complete_challenge`.

Player deaths are reported to the god as they happen, with the vanilla death
message, so it can mock, mourn, avenge, or ignore them.

Visible advancement unlocks are also reported. The god can congratulate the
player, trigger native particles or sounds, do something stranger, or stay
silent. Operator command output is suppressed; players see the god's final chat
message rather than raw summon/effect feedback followed by a duplicate reply.

The dedicated server cannot access client pixels. Instead, `inspect_view` gives
the model useful and honest spatial awareness without requiring a client mod or
pretending a screenshot exists.

Bargains are negotiable in plain chat. Ask for 100 diamonds, get told to kill
50 zombies, counter with "what about 40?" and the god may accept the amended
deal (voiding and recreating the quest), hold firm, or declare the deal off.

## Memory and compaction

AI God attaches every Responses API turn to one OpenAI Conversation. The
conversation ID is saved in `ai-god-conversation.txt` inside the world folder,
allowing the same shared memory to survive normal server restarts. Conversation
items have no 30-day response-object expiry, so the native history also powers
the admin activity page without a second logging system.

Server-side context compaction is enabled automatically at 100,000 rendered
tokens. OpenAI compresses older conversation and tool history and carries the
result forward; the mod continues sending only the newest live server turn.
Override the threshold with `AI_GOD_COMPACT_THRESHOLD`.

This follows OpenAI's documented [conversation-state](https://developers.openai.com/api/docs/guides/conversation-state)
and [server-side compaction](https://developers.openai.com/api/docs/guides/compaction#server-side-compaction)
patterns.

## Requirements

- Minecraft Java Edition 26.2 dedicated server
- Fabric Loader 0.18 or newer
- Fabric API for Minecraft 26.2
- Java 25 or newer
- An OpenAI API key with access to the configured model

The default model is
[`gpt-5.6-terra`](https://developers.openai.com/api/docs/models/gpt-5.6-terra)
with reasoning effort `none`. It supports the Responses API and function
calling while keeping chat responsive. Four live custom-tool trials per model
from the production EC2 server measured median response times of 0.932 seconds
for `gpt-5.4-mini`, 1.160 seconds for `gpt-5.6-luna`, and 2.442 seconds for
`gpt-5.6-terra`, all with reasoning disabled. Set `AI_GOD_MODEL` to use another
compatible model; GPT-5.6 overrides also use reasoning effort `none`.

## Build

```bash
./gradlew test build
```

The server-ready artifact is written to:

```text
build/libs/ai-god-0.1.0.jar
```

Copy that JAR and the matching Fabric API JAR into the dedicated server's
`mods/` directory.

## Run locally

Provide configuration through the server process environment:

```bash
export OPENAI_API_KEY="sk-..."
export AI_GOD_MODEL="gpt-5.6-terra"                # optional
export AI_GOD_NAME="AI God"                        # optional display name and persona name
export AI_GOD_COMPACT_THRESHOLD="100000"          # optional
export AI_GOD_ADMIN_PORT="8765"                    # optional, loopback only
export AI_GOD_ADMIN_PASSWORD="use-a-long-password" # enables the admin page
java -jar fabric-server-launch.jar nogui
```

For the quickest development server, accept Minecraft's EULA once and use the
Fabric Loom task:

```bash
mkdir -p run
printf 'eula=true\n' > run/eula.txt
OPENAI_API_KEY="sk-..." ./gradlew runServer
```

That creates a disposable local world under `run/`. Join it from the same Mac
at `localhost:25565`. Stop it with `Ctrl-C`. Use a separate development API key
when possible.

Do not put the API key in this repository, `server.properties`, a command
block, or Minecraft chat.

Once the server starts, players just chat normally. Every message is considered
by the god; no `/god` command or prefix is required. Because every message can
produce an API turn, a busy public server can generate substantial API usage.

## Quest behavior

Quests are persisted to `ai-god-quests.json` in the world folder.

- `KILL` advances when the quest owner directly kills the target entity.
- `MINE` advances when the quest owner breaks the target block.
- `COLLECT` counts matching items gained after the quest was created.
- Completion runs the stored reward command.
- An expired ad-hoc quest runs its punishment when that player is online, then
  clears. An expired daily challenge instead hands the failure back to the god
  to improvise a consequence, falling back to the stored punishment command if
  the API call fails.

`{player}` in tool and quest commands is replaced with the current player's
exact Minecraft name.

## How it works

```text
normal server chat
  -> live world/player snapshot
  -> OpenAI Responses API + one persistent Conversation
      -> speak
      -> stay silent
      -> run one or more unrestricted operator commands
      -> create a tracked quest
  -> tool results returned to the model
  -> repeat until the model is done
  -> optional AI God message in normal server chat
```

The HTTP requests run on virtual threads. All Minecraft state reads, quest
updates, and commands execute on the server thread.

## Development

```bash
./gradlew test       # quest logic tests
./gradlew build      # compile, test, and remap the distributable JAR
./gradlew runServer  # local Fabric server smoke test (requires EULA acceptance)
```

The project is deliberately small: Java's built-in HTTP client talks directly
to the Responses API, Gson handles JSON through Minecraft's existing runtime,
and Fabric events provide chat and quest progress.

The static landing page lives in [`website/`](website/). Cloudflare Pages is
connected directly to this GitHub repository and publishes that directory from
each `main` push that changes `website/**`; there is no separate site build
command.

## Admin activity

When `AI_GOD_ADMIN_PASSWORD` is set, the mod serves a small read-only activity
page on `127.0.0.1:8765`. Every page, asset, and API request requires HTTP Basic
authentication with username `admin` and that password. It does not
create another log or database. It reads the native OpenAI Conversation directly,
showing recent player/server inputs, model replies, tool arguments, tool results,
exposed reasoning summaries, and tool errors.
It also shows live players, hearts, food, coordinates, held items, active quests,
weather, pending AI turns, and scheduled events.
OpenAI does not expose private chain-of-thought, so the dashboard does not claim
to show it.

Production can publish that loopback page as `admin.mcgodmod.com` through a
Cloudflare Tunnel. Cloudflare Access is not required because the mod enforces the
password itself. The EC2 security group does not need another inbound port. The
deployment reads the password from the encrypted SSM parameter
`/minecraft-ai-god/admin-password`; use a long URL-safe value so it can be loaded
cleanly by systemd.

## AWS deployment

Production is deliberately one small stack:

```text
push to main -> GitHub Actions builds and tests -> private S3 artifact
             -> Systems Manager runs minecraft-deploy on one EC2 server
             -> systemd restarts Minecraft with the new JAR
```

- EC2: one `t3.medium` Amazon Linux 2023 instance in `us-west-1`
- Address: `mcgodmod.com` (Elastic IP `54.193.72.0`, TCP port `25565`)
- Runtime: Java 25, Fabric Loader, Fabric API, and one `minecraft.service`
- Administration: AWS Systems Manager; SSH port 22 is not exposed
- Secrets: encrypted Systems Manager parameter
  `/minecraft-ai-god/openai-api-key`
- Storage: a private, encrypted, versioned S3 bucket for releases and backups
- Recovery: the server stops briefly for a daily world backup; backups expire
  after 14 days and old release artifacts after 30 days

Systems Manager is used because the instance can retrieve the OpenAI key and
receive deployment commands through its AWS identity. The key is never stored
in GitHub Actions, the AMI, user-data, or this repository.

Every push to `main` runs [.github/workflows/deploy.yml](.github/workflows/deploy.yml).
The workflow tests and builds the exact commit, uploads its JAR, asks Systems
Manager to deploy it, waits for the command, and fails if the restart fails.
Expect roughly 15 seconds of downtime during a deploy. No approval click is
required.

Useful operator commands:

```bash
# Show the service status without opening SSH.
aws ssm send-command --region us-west-1 \
  --instance-ids i-015a57b27d1f5c1f8 \
  --document-name AWS-RunShellScript \
  --parameters 'commands=["systemctl status minecraft --no-pager"]'

# Replace or rotate the encrypted OpenAI key. Never commit the value.
aws ssm put-parameter --region us-west-1 \
  --name /minecraft-ai-god/openai-api-key \
  --type SecureString --value 'NEW_KEY_HERE' --overwrite

# Apply a rotated key immediately using the already-uploaded release.
aws ssm send-command --region us-west-1 \
  --instance-ids i-015a57b27d1f5c1f8 \
  --document-name AWS-RunShellScript \
  --parameters 'commands=["/usr/local/bin/minecraft-deploy s3://minecraft-ai-god-928535088750-us-west-1/latest/ai-god.jar"]'
```

AWS bills for the EC2 instance, its 20 GB disk, Elastic IP usage, S3 storage,
and network traffic. OpenAI API usage is billed separately. Stop the instance
when it is not needed; release the instance and Elastic IP when the server is
retired.
