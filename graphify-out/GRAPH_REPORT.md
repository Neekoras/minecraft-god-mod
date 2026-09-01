# Graph Report - minecraft-god-mod  (2026-09-01)

## Corpus Check
- 42 files · ~24,934 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 377 nodes · 873 edges · 27 communities (17 shown, 8 thin omitted)
- Extraction: 84% EXTRACTED · 16% INFERRED · 0% AMBIGUOUS · INFERRED: 143 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `44600988`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- GodService
- QuestManager
- org.junit.jupiter.api.Test
- ServerGoal
- org.slf4j.Logger
- com.google.gson.JsonObject
- net.minecraft.server.MinecraftServer
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

## God Nodes (most connected - your core abstractions)
1. `GodService` - 55 edges
2. `QuestManager` - 28 edges
3. `ServerGoal` - 25 edges
4. `Quest` - 23 edges
5. `AdminServer` - 21 edges
6. `DailyChallengeManager` - 21 edges
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

## Communities (27 total, 8 thin omitted)

### Community 0 - "GodService"
Cohesion: 0.10
Nodes (8): net.minecraft.server.level.ServerPlayer, Override, ChatTurn, CommandOutcome, DeferredCommand, GodService, JsonObject, Override

### Community 1 - "QuestManager"
Cohesion: 0.09
Nodes (8): Objective, COLLECT, KILL, MINE, STAT, Quest, QuestManager, QuestTest

### Community 2 - "org.junit.jupiter.api.Test"
Cohesion: 0.08
Nodes (9): org.junit.jupiter.api.Test, DayCycle, MinecraftChatText, DailyStoreTest, DayCycleTest, GodServiceChatTurnTest, MinecraftChatTextTest, OpenAiGodClientTest (+1 more)

### Community 3 - "ServerGoal"
Cohesion: 0.15
Nodes (5): DailyChallengeManager, JsonObject, DailyStore, State, ServerGoal

### Community 4 - "org.slf4j.Logger"
Cohesion: 0.12
Nodes (8): com.google.gson.Gson, net.fabricmc.api.ModInitializer, org.slf4j.Logger, AiGodMod, ConversationStore, QuestStore, ScheduledEvent, ScheduleStore

### Community 5 - "com.google.gson.JsonObject"
Cohesion: 0.18
Nodes (13): com.google.gson.JsonElement, com.google.gson.JsonObject, java.net.http.HttpClient, java.net.http.HttpResponse, java.net.URI, java.util.regex.Pattern, GodApiException, JsonObject (+5 more)

### Community 6 - "net.minecraft.server.MinecraftServer"
Cohesion: 0.25
Nodes (7): net.minecraft.commands.CommandSourceStack, net.minecraft.network.chat.Component, net.minecraft.resources.Identifier, net.minecraft.server.level.ServerBossEvent, net.minecraft.server.MinecraftServer, net.minecraft.stats.Stat, net.minecraft.stats.StatType

### Community 7 - "AdminServer"
Cohesion: 0.25
Nodes (5): com.sun.net.httpserver.HttpExchange, com.sun.net.httpserver.HttpServer, AdminServer, JsonObject, Override

### Community 8 - "app.js"
Cohesion: 0.14
Nodes (25): avatarUrl(), connection, emptyElement, filterLabel, filterMenu, groupTurns(), hearts(), inputDetails() (+17 more)

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

## Knowledge Gaps
- **66 isolated node(s):** `user-data.sh script`, `AWS_DEFAULT_REGION`, `KILL`, `MINE`, `COLLECT` (+61 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 98 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **8 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `GodService` connect `GodService` to `QuestManager`, `ServerGoal`, `org.slf4j.Logger`, `com.google.gson.JsonObject`, `net.minecraft.server.MinecraftServer`?**
  _High betweenness centrality (0.151) - this node is a cross-community bridge._
- **Why does `OpenAiGodClient` connect `com.google.gson.JsonObject` to `GodService`, `org.junit.jupiter.api.Test`, `org.slf4j.Logger`?**
  _High betweenness centrality (0.058) - this node is a cross-community bridge._
- **Why does `QuestManager` connect `QuestManager` to `GodService`, `ServerGoal`, `org.slf4j.Logger`, `net.minecraft.server.MinecraftServer`?**
  _High betweenness centrality (0.049) - this node is a cross-community bridge._
- **What connects `user-data.sh script`, `AWS_DEFAULT_REGION`, `KILL` to the rest of the system?**
  _66 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `GodService` be split into smaller, more focused modules?**
  _Cohesion score 0.1033182503770739 - nodes in this community are weakly interconnected._
- **Should `QuestManager` be split into smaller, more focused modules?**
  _Cohesion score 0.0935374149659864 - nodes in this community are weakly interconnected._
- **Should `org.junit.jupiter.api.Test` be split into smaller, more focused modules?**
  _Cohesion score 0.07926829268292683 - nodes in this community are weakly interconnected._