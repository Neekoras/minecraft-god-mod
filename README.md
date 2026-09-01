# AI God

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

Open **Minecraft: Java Edition 1.21.1**, choose **Multiplayer**, and add:

```text
54.193.72.0:25565
```

No client mod is needed. The server is public, uses normal Minecraft account
authentication, and currently allows up to 10 players. Just talk in ordinary
server chat; there is no `/god` command or special prefix.

## What it can do

The model receives three custom tools:

- `run_command` executes any installed server command as the speaking player
  with level-4 permission. Relative coordinates and selectors therefore start
  from that player. The model can call it repeatedly in one turn.
- `create_quest` creates a timed `KILL`, `MINE`, or `COLLECT` objective. Its
  success reward and timeout punishment are unrestricted operator commands.
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

- Minecraft Java Edition 1.21.1 dedicated server
- Fabric Loader 0.18 or newer
- Fabric API for Minecraft 1.21.1
- Java 21 or newer
- An OpenAI API key with access to the configured model

The default model is
[`gpt-5.6-luna`](https://developers.openai.com/api/docs/models/gpt-5.6-luna)
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
export AI_GOD_MODEL="gpt-5.6-luna"                 # optional
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
- An expired quest runs its punishment when that player is online, then clears.

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

## AWS deployment

Production is deliberately one small stack:

```text
push to main -> GitHub Actions builds and tests -> private S3 artifact
             -> Systems Manager runs minecraft-deploy on one EC2 server
             -> systemd restarts Minecraft with the new JAR
```

- EC2: one `t3.medium` Amazon Linux 2023 instance in `us-west-1`
- Address: Elastic IP `54.193.72.0`, TCP port `25565`
- Runtime: Java 21, Fabric Loader, Fabric API, and one `minecraft.service`
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
