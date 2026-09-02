# Graph Report - minecraft-god-mod  (2026-09-01)

## Corpus Check
- 44 files · ~27,265 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 408 nodes · 973 edges · 28 communities (16 shown, 10 thin omitted)
- Extraction: 83% EXTRACTED · 17% INFERRED · 0% AMBIGUOUS · INFERRED: 170 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `be71382d`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- GodService
- org.junit.jupiter.api.Test
- DayCycle
- DailyChallengeManager
- org.slf4j.Logger
- OpenAiGodClient
- MinecraftChatText
- AdminServer
- app.js
- Graphify Command and Extraction
- Build Script Utilities
- Minecraft Deployment Script
- User Data and Setup Script
- Server Icon Asset
- Graph Export and Benchmarking
- Project Documentation and Setup
- Graphify Query and Traversal
- Graphify Add and Watch
- Git Commit Hook Integration
- Incremental Update and Clustering
- Ponytail Ladder Documentation
- GitHub Clone and Merge
- Audio and Video Transcription
- Graphify Engineering Agents
- Extraction Subagent Specification
- com.google.gson.JsonObject

## God Nodes (most connected - your core abstractions)
1. `GodService` - 62 edges
2. `DailyChallengeManager` - 30 edges
3. `QuestManager` - 28 edges
4. `ServerGoal` - 28 edges
5. `Quest` - 25 edges
6. `AdminServer` - 21 edges
7. `OpenAiGodClient` - 21 edges
8. `Objective` - 16 edges
9. `What You Must Do When Invoked` - 12 edges
10. `DailyStore` - 11 edges

## Surprising Connections (you probably didn't know these)
- `AdminServer` --references--> `ConversationStore`  [EXTRACTED]
  src/main/java/dev/aigod/AdminServer.java → src/main/java/dev/aigod/ConversationStore.java
- `AiGodMod` --references--> `AdminServer`  [EXTRACTED]
  src/main/java/dev/aigod/AiGodMod.java → src/main/java/dev/aigod/AdminServer.java
- `AiGodMod` --references--> `GodService`  [EXTRACTED]
  src/main/java/dev/aigod/AiGodMod.java → src/main/java/dev/aigod/GodService.java
- `GodService` --references--> `ConversationStore`  [EXTRACTED]
  src/main/java/dev/aigod/GodService.java → src/main/java/dev/aigod/ConversationStore.java
- `DailyChallengeManager` --references--> `GodService`  [EXTRACTED]
  src/main/java/dev/aigod/DailyChallengeManager.java → src/main/java/dev/aigod/GodService.java

## Import Cycles
- None detected.

## Communities (28 total, 10 thin omitted)

### Community 0 - "GodService"
Cohesion: 0.09
Nodes (10): net.minecraft.commands.CommandSourceStack, net.minecraft.network.chat.Component, net.minecraft.server.level.ServerPlayer, Override, ChatTurn, CommandOutcome, DeferredCommand, GodService (+2 more)

### Community 1 - "org.junit.jupiter.api.Test"
Cohesion: 0.08
Nodes (9): org.junit.jupiter.api.Test, Quest, DailyChallengeManagerTest, DailyStoreTest, GodServiceChatTurnTest, GodServiceTest, MinecraftChatTextTest, OpenAiGodClientTest (+1 more)

### Community 3 - "DailyChallengeManager"
Cohesion: 0.08
Nodes (14): net.minecraft.server.level.ServerBossEvent, net.minecraft.server.MinecraftServer, Chapter, DailyChallengeManager, JsonObject, DailyStore, State, Objective (+6 more)

### Community 4 - "org.slf4j.Logger"
Cohesion: 0.11
Nodes (9): com.google.gson.Gson, com.google.gson.JsonElement, net.fabricmc.api.ModInitializer, org.slf4j.Logger, AiGodMod, ConversationStore, QuestStore, ScheduledEvent (+1 more)

### Community 5 - "OpenAiGodClient"
Cohesion: 0.16
Nodes (14): com.google.gson.JsonArray, com.sun.net.httpserver.HttpServer, java.net.http.HttpClient, java.net.http.HttpResponse, java.net.URI, java.util.regex.Pattern, JsonArray, GodApiException (+6 more)

### Community 7 - "AdminServer"
Cohesion: 0.27
Nodes (4): com.sun.net.httpserver.HttpExchange, AdminServer, JsonObject, Override

### Community 8 - "app.js"
Cohesion: 0.13
Nodes (28): avatarUrl(), connection, emptyElement, expandedPlayers, filterLabel, filterMenu, groupTurns(), hearts() (+20 more)

### Community 9 - "Graphify Command and Extraction"
Cohesion: 0.08
Nodes (24): For /graphify add and --watch, For /graphify query, For the commit hook and native CLAUDE.md integration, For --update and --cluster-only, /graphify, Honesty Rules, Interpreter guard for subcommands, Part A - Structural extraction for code files (+16 more)

### Community 10 - "Build Script Utilities"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

### Community 16 - "Graph Export and Benchmarking"
Cohesion: 0.22
Nodes (8): graphify reference: extra exports and benchmark, Step 6b - Wiki (only if --wiki flag), Step 7 - Neo4j export (only if --neo4j or --neo4j-push flag), Step 7a - FalkorDB export (only if --falkordb or --falkordb-push flag), Step 7b - SVG export (only if --svg flag), Step 7c - GraphML export (only if --graphml flag), Step 7d - MCP server (only if --mcp flag), Step 8 - Token reduction benchmark (only if total_words > 5000)

### Community 17 - "Project Documentation and Setup"
Cohesion: 0.25
Nodes (7): Admin, Memory and saved state, Minecraft, with ChatGPT, Operator access, Production, Run locally, The game

### Community 18 - "Graphify Query and Traversal"
Cohesion: 0.33
Nodes (5): For /graphify explain, For /graphify path, graphify reference: query, path, explain, Step 0 — Constrained query expansion (REQUIRED before traversal), Step 1 — Traversal

### Community 19 - "Graphify Add and Watch"
Cohesion: 0.50
Nodes (3): For /graphify add, For --watch, graphify reference: add a URL and watch a folder

### Community 20 - "Git Commit Hook Integration"
Cohesion: 0.50
Nodes (3): For git commit hook, For native CLAUDE.md integration, graphify reference: commit hook and native CLAUDE.md integration

### Community 21 - "Incremental Update and Clustering"
Cohesion: 0.50
Nodes (3): For --cluster-only, For --update (incremental re-extraction), graphify reference: incremental update and cluster-only

### Community 27 - "com.google.gson.JsonObject"
Cohesion: 0.19
Nodes (5): com.google.gson.JsonObject, net.minecraft.resources.Identifier, net.minecraft.stats.Stat, net.minecraft.stats.StatType, QuestManager

## Knowledge Gaps
- **67 isolated node(s):** `user-data.sh script`, `AWS_DEFAULT_REGION`, `KILL`, `MINE`, `COLLECT` (+62 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 99 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **10 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `GodService` connect `GodService` to `com.google.gson.JsonObject`, `DailyChallengeManager`, `org.slf4j.Logger`, `OpenAiGodClient`?**
  _High betweenness centrality (0.162) - this node is a cross-community bridge._
- **Why does `OpenAiGodClient` connect `OpenAiGodClient` to `GodService`, `org.junit.jupiter.api.Test`, `com.google.gson.JsonObject`, `org.slf4j.Logger`?**
  _High betweenness centrality (0.053) - this node is a cross-community bridge._
- **Why does `DailyChallengeManager` connect `DailyChallengeManager` to `GodService`, `org.slf4j.Logger`?**
  _High betweenness centrality (0.047) - this node is a cross-community bridge._
- **What connects `user-data.sh script`, `AWS_DEFAULT_REGION`, `KILL` to the rest of the system?**
  _67 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `GodService` be split into smaller, more focused modules?**
  _Cohesion score 0.08704557091653865 - nodes in this community are weakly interconnected._
- **Should `org.junit.jupiter.api.Test` be split into smaller, more focused modules?**
  _Cohesion score 0.0786308973172988 - nodes in this community are weakly interconnected._
- **Should `DailyChallengeManager` be split into smaller, more focused modules?**
  _Cohesion score 0.08165057067603161 - nodes in this community are weakly interconnected._