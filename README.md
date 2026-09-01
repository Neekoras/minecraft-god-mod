# AI God

Website: [mcgodmod.com](https://mcgodmod.com)

AI God is a server-side Fabric mod that puts one persistent OpenAI-powered god
in ordinary Minecraft chat. There is no bot command and no separate UI: every
player message becomes a turn, and the god decides whether to answer, act,
offer a bargain, or remain completely silent.

```text
<Dennis> can I have a diamond pickaxe?
[AI God] Bring me the wool of twelve sheep before nightfall. Fail, and the sky answers.
Objective: kill minecraft:sheep × 12.
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

The same domain hosts the project landing page. Minecraft uses its standard
DNS service record to reach the game server, so players still enter only
`mcgodmod.com`.

## What it can do

The model receives five custom tools:

- `run_command` executes any installed server command as the speaking player
  with level-4 permission. Relative coordinates and selectors therefore start
  from that player. The model can call it repeatedly in one turn.
- `create_quest` creates a timed `KILL`, `MINE`, or `COLLECT` objective. Its
  success reward and timeout punishment are unrestricted operator commands.
- `complete_challenge` marks an online player's active quest complete and runs
  its reward command, for when an offering or deed satisfies the god.
- `cancel_quest` voids a player's active quest with no reward or punishment,
  so the god can renegotiate bargains, call deals off, or show mercy.
- `stay_silent` ends the turn without putting a god message in chat.

Tool results go back to the model. It can issue more commands after seeing a
result, then speak or choose silence. There is no local tool-call count cap and
no allowlist of Minecraft commands.

The god sees live context on every turn:

- the new chat message and its speaker;
- every online player's health, hunger, XP, position, dimension, inventory, and
  active quest;
- server difficulty, day time, rain, and thunder;
- the preceding shared server conversation and prior tool results.

Chat turns are queued in order, so simultaneous messages cannot fork or race
the god's shared memory.

## Daily challenges

Every Minecraft morning the god issues each online player a daily challenge,
created through the normal quest system but with its deadline pinned to
**sundown of that day** (game time, not wall-clock). The god is instructed to
keep daily challenges varied, fun, and hard.

- Complete it before sundown and the quest's reward command runs as usual.
- Fail, and the god itself is told about the failure and invents a consequence
  on the spot, matched to the challenge it set: mob ambushes, lightning,
  confiscations, whatever it decides through `run_command`. If the API is
  unreachable at sundown, the quest's stored punishment command runs instead,
  so failure is never free.

One challenge is issued per player per day; the last issued day is persisted
to `ai-god-daily.json` in the world folder so restarts do not double-issue.
Players who join mid-day receive their challenge on the next scheduler pass.
If a challenge cannot be issued (API error), the mod retries once a minute
until sundown.

## Offerings and deaths

There are still no commands. Players offer items by saying so in normal chat
("take my offering") while holding the tribute; the god sees each player's
held item in the live snapshot, takes accepted offerings itself via vanilla
commands, and may respond with gifts, mercy, or `complete_challenge`.

Player deaths are reported to the god as they happen, with the vanilla death
message, so it can mock, mourn, avenge, or ignore them.

Bargains are negotiable in plain chat. Ask for 100 diamonds, get told to kill
50 zombies, counter with "what about 40?" and the god may accept the amended
deal (voiding and recreating the quest), hold firm, or declare the deal off.
A renegotiated daily challenge keeps its sundown deadline.

## Memory and compaction

AI God chains Responses API turns with `previous_response_id`. The latest
completed response ID is saved in `ai-god-conversation.txt` inside the world
folder, allowing the conversation to survive a normal server restart.

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
  -> OpenAI Responses API + shared previous_response_id
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
