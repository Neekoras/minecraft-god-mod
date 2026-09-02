const turnsElement = document.querySelector("#turns");
const emptyElement = document.querySelector("#empty");
const connection = document.querySelector(".connection");
const speakerFilter = document.querySelector("#speaker-filter");
const filterMenu = document.querySelector(".filter-menu");
const filterLabel = document.querySelector("#filter-label");
const loadEarlier = document.querySelector("#load-earlier");
let turns = [];
let selectedSpeaker = "all";
let nextAfter = null;

const playerColors = ["#8fb8ff", "#f29c9c", "#d8a8ff", "#77d8c4", "#f0bd70", "#9fcf78"];
const playerColor = (name = "server") => playerColors[[...name]
  .reduce((value, character) => value + character.charCodeAt(0), 0) % playerColors.length];
const avatarUrl = (player) => `https://mc-heads.net/avatar/${encodeURIComponent(player.uuid || player.name)}/48`;
const hearts = (health = 0, maxHealth = 10) => Array.from({ length: Math.ceil(maxHealth) }, (_, index) => {
  const fill = Math.max(0, Math.min(1, health - index)) * 100;
  return `<span class="heart" style="--fill:${fill}%"></span>`;
}).join("");

const textContent = (item) => (item.content || [])
  .map((part) => part.text || part.refusal || "")
  .filter(Boolean)
  .join("\n");

const inputDetails = (text) => {
  const player = text.match(/current_speaker=([^\n]+)/);
  const message = text.match(/message=([\s\S]*?)\n\nLive server state:/);
  const sentAt = text.match(/sent_at_epoch_ms=(\d+)/);
  if (player) return {
    speaker: player[1],
    prompt: message?.[1]?.trim() || text,
    sentAt: sentAt ? Number(sentAt[1]) : null
  };
  const automatic = text.match(/Automatic server event concerning ([^:]+): ([\s\S]*?)\n\nLive server state:/);
  if (automatic) return { speaker: "server", prompt: automatic[2].trim() };
  return { speaker: "server", prompt: text };
};

const timeAgo = (timestamp) => {
  if (!timestamp) return "earlier";
  const seconds = Math.max(0, Math.floor((Date.now() - timestamp) / 1000));
  if (seconds < 10) return "just now";
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
};

const stepFrom = (item) => {
  if (item.type === "function_call") {
    let value = item.arguments;
    try { value = JSON.parse(value); } catch (_) { /* keep the raw value */ }
    return { label: item.name, kind: "tool", call: true, value };
  }
  if (item.type === "function_call_output") {
    const error = typeof item.output === "string" && item.output.startsWith("error:");
    return { label: error ? "tool error" : "tool result", kind: error ? "error" : "tool", value: item.output };
  }
  if (item.type === "message" && item.role === "assistant") {
    return { label: "reply", kind: "reply", value: textContent(item) };
  }
  if (item.type === "reasoning") {
    const summary = (item.summary || []).map((part) => part.text).filter(Boolean).join("\n");
    return { label: "reasoning", kind: "reasoning", value: summary || "No summary exposed." };
  }
  return null;
};

const groupTurns = (items) => {
  const grouped = [];
  let current = null;
  for (const item of [...items].reverse()) {
    if (item.type === "message" && item.role === "user") {
      current = { ...inputDetails(textContent(item)), steps: [], error: false };
      grouped.push(current);
      continue;
    }
    const step = stepFrom(item);
    if (!step) continue;
    if (!current) {
      current = { speaker: "recovered", prompt: "Earlier conversation output", steps: [], error: false };
      grouped.push(current);
    }
    current.steps.push(step);
    current.error ||= step.kind === "error";
  }
  return grouped.reverse();
};

const expandedPlayers = new Set();

const playerStatsGrid = (player) => {
  const stats = [
    ["playtime", `${player.playtime_minutes ?? 0} min`],
    ["chats", player.chats ?? 0],
    ["deaths", player.deaths ?? 0],
    ["mob kills", player.mob_kills ?? 0],
    ["player kills", player.player_kills ?? 0],
    ["blocks walked", player.blocks_walked ?? 0],
    ["jumps", player.jumps ?? 0],
    ["xp level", player.xp_level ?? 0],
    ["gamemode", player.gamemode ?? "?"],
  ];
  const grid = document.createElement("div");
  grid.className = "stat-grid";
  grid.innerHTML = stats
    .map(([label, value]) => `<div class="stat"><span>${label}</span><strong>${value}</strong></div>`)
    .join("");
  return grid;
};

const inventoryGrid = (player) => {
  const wrap = document.createElement("div");
  wrap.className = "inventory";
  const items = player.inventory || [];
  if (!items.length) {
    wrap.innerHTML = '<p class="empty-inline">empty inventory</p>';
    return wrap;
  }
  wrap.innerHTML = `<h4>inventory (${items.length})</h4><div class="inv-grid">` + items
    .map((item) => `<div class="inv-item"><span>${item.name}</span><b>×${item.count}</b></div>`)
    .join("") + "</div>";
  return wrap;
};

const renderState = (state = {}) => {
  const players = state.players || [];
  const schedules = state.scheduled_events || [];
  const goal = state.goal || {};
  const legacyGoal = !goal.active && state.server_goal
    && !state.server_goal.startsWith("no server goal") ? state.server_goal : null;
  document.querySelector("#online-count").textContent = players.length;
  document.querySelector("#queue-depth").textContent = `${state.processing ? "thinking" : "idle"} · queue ${state.queue_depth || 0}`;
  document.querySelector("#server-goal").textContent = goal.active
    ? goal.challenge : legacyGoal || "Waiting for dawn";
  document.querySelector("#goal-detail").textContent = goal.active
    ? `${goal.objective} ${goal.target} · every player contributes`
    : legacyGoal ? "Live goal from the current server build." : "Every player contributes to the same objective.";
  document.querySelector("#goal-progress").textContent = goal.active ? `${goal.progress} / ${goal.amount}` : "—";
  document.querySelector("#goal-deadline").textContent = goal.active
    ? `${Math.ceil(goal.ticks_left / 20)}s until sundown`
    : "No active goal";
  document.querySelector("#goal-progress-bar").style.width = goal.active
    ? `${Math.min(100, (goal.progress / goal.amount) * 100)}%`
    : "0";
  const weather = state.thundering ? "thunder" : state.raining ? "rain" : "clear";
  document.querySelector("#world-state").textContent = `tick ${state.daytime_ticks ?? "—"} · ${weather}`;

  const playerList = document.querySelector("#players");
  playerList.replaceChildren();
  if (!players.length) playerList.innerHTML = '<p class="empty-inline">nobody online</p>';
  for (const player of players) {
    const row = document.createElement("div");
    row.className = "player-row";
    row.style.setProperty("--player-color", playerColor(player.name));
    row.innerHTML = `<div class="player-main"><img class="avatar" width="40" height="40" alt=""><div><strong></strong><span class="coordinates"></span></div></div>
      <div class="heart-row" aria-label="health"></div>
      <p class="player-detail"></p><p class="challenge"></p>`;
    const avatar = row.querySelector(".avatar");
    avatar.src = avatarUrl(player);
    avatar.alt = `${player.name}'s Minecraft skin`;
    row.querySelector("strong").textContent = player.name;
    row.querySelector(".coordinates").textContent = `${player.biome || player.dimension} · ${player.x} ${player.y} ${player.z}`;
    row.querySelector(".heart-row").innerHTML = hearts(player.health, player.max_health);
    row.querySelector(".player-detail").textContent = `${player.health.toFixed(1)}/${player.max_health.toFixed(1)} hearts · ${player.hunger}/20 food · ${player.holding}`;
    row.querySelector(".challenge").textContent = player.challenge;

    const detail = document.createElement("div");
    detail.className = "player-expand";
    detail.hidden = !expandedPlayers.has(player.name);
    detail.append(playerStatsGrid(player), inventoryGrid(player));
    row.append(detail);
    row.classList.add("clickable");
    row.addEventListener("click", () => {
      const open = detail.hidden;
      detail.hidden = !open;
      if (open) expandedPlayers.add(player.name);
      else expandedPlayers.delete(player.name);
    });
    playerList.append(row);
  }

  const scheduleList = document.querySelector("#schedules");
  scheduleList.replaceChildren();
  if (!schedules.length) scheduleList.innerHTML = '<p class="empty-inline">nothing scheduled</p>';
  for (const schedule of schedules) {
    const row = document.createElement("div");
    row.className = "schedule-row";
    const cadence = schedule.repeat_seconds ? `every ${schedule.repeat_seconds}s` : "once";
    row.innerHTML = "<div><strong></strong><span></span></div><p></p>";
    row.querySelector("strong").textContent = schedule.id;
    row.querySelector("span").textContent = `${schedule.player} · in ${schedule.due_in_seconds}s · ${cadence}`;
    row.querySelector("p").textContent = schedule.instruction;
    scheduleList.append(row);
  }
};

const render = () => {
  turnsElement.replaceChildren();
  const visible = turns.filter((turn) => selectedSpeaker === "all"
    || (selectedSpeaker === "errors" ? turn.error : turn.speaker === selectedSpeaker));
  for (const turn of visible) {
    const article = document.createElement("article");
    article.className = "turn";
    article.style.setProperty("--player-color", playerColor(turn.speaker));
    article.innerHTML = `<div class="turn-head"><div class="turn-player"><img class="turn-avatar" width="24" height="24" alt=""><strong></strong></div><span></span></div><p class="prompt"></p><div class="steps"></div>`;
    const avatar = article.querySelector(".turn-avatar");
    avatar.src = avatarUrl({ name: turn.speaker });
    avatar.alt = `${turn.speaker}'s Minecraft skin`;
    article.querySelector("strong").textContent = turn.speaker;
    article.querySelector(".turn-head span").textContent = timeAgo(turn.sentAt);
    article.querySelector(".prompt").textContent = turn.prompt;
    const steps = article.querySelector(".steps");
    for (const step of turn.steps) {
      const row = document.createElement("div");
      row.className = `step ${step.kind}`;
      row.innerHTML = '<span class="step-label"></span><pre></pre>';
      row.querySelector(".step-label").textContent = step.label;
      row.querySelector("pre").textContent = typeof step.value === "string" ? step.value : JSON.stringify(step.value, null, 2);
      steps.append(row);
    }
    turnsElement.append(article);
  }
  emptyElement.hidden = visible.length > 0;
};

const updateFilters = () => {
  speakerFilter.querySelectorAll("[data-dynamic]").forEach((button) => button.remove());
  [...new Set(turns.map((turn) => turn.speaker))].sort().forEach((speaker) => {
    const button = document.createElement("button");
    button.type = "button";
    button.role = "menuitemradio";
    button.dataset.value = speaker;
    button.dataset.dynamic = "true";
    const avatar = document.createElement("img");
    avatar.src = avatarUrl({ name: speaker });
    avatar.alt = "";
    const label = document.createElement("span");
    label.textContent = speaker;
    button.append(avatar, label);
    speakerFilter.append(button);
  });
  if (!speakerFilter.querySelector(`[data-value="${CSS.escape(selectedSpeaker)}"]`)) selectedSpeaker = "all";
  speakerFilter.querySelectorAll("button").forEach((button) => {
    const active = button.dataset.value === selectedSpeaker;
    button.classList.toggle("active", active);
    button.setAttribute("aria-checked", active);
  });
  filterLabel.textContent = selectedSpeaker === "all" ? "Everyone"
    : selectedSpeaker === "errors" ? "Errors only" : selectedSpeaker;
};

const setLive = () => {
  connection.className = "connection live";
  document.querySelector("#connection").textContent = "live";
};

const setOffline = () => {
  connection.className = "connection error";
  document.querySelector("#connection").textContent = "offline";
};

const refreshState = async () => {
  try {
    const response = await fetch("/api/state", { cache: "no-store" });
    const payload = await response.json();
    if (!response.ok) throw new Error(payload.error || `HTTP ${response.status}`);
    setLive();
    document.querySelector("#model-name").textContent = payload.model || "—";
    renderState(payload.state);
  } catch (_) {
    setOffline();
  }
};

const refreshTurns = async () => {
  try {
    const response = await fetch("/api/turns", { cache: "no-store" });
    const payload = await response.json();
    if (!response.ok) throw new Error(payload.error || `HTTP ${response.status}`);
    turns = groupTurns(payload.items || []);
    nextAfter = payload.next_after || null;
    loadEarlier.hidden = !nextAfter;
    setLive();
    document.querySelector("#turn-count").textContent = turns.length;
    document.querySelector("#model-name").textContent = payload.conversation?.model || "—";
    document.querySelector("#tool-count").textContent = turns.flatMap((turn) => turn.steps).filter((step) => step.call).length;
    updateFilters();
    render();
  } catch (_) {
    setOffline();
  }
};

const loadEarlierTurns = async () => {
  if (!nextAfter) return;
  loadEarlier.disabled = true;
  loadEarlier.textContent = "Loading…";
  try {
    const response = await fetch(`/api/turns?after=${encodeURIComponent(nextAfter)}`, { cache: "no-store" });
    const payload = await response.json();
    if (!response.ok) throw new Error(payload.error || `HTTP ${response.status}`);
    turns.push(...groupTurns(payload.items || []));
    nextAfter = payload.next_after || null;
    updateFilters();
    render();
  } finally {
    loadEarlier.disabled = false;
    loadEarlier.textContent = "Load earlier turns";
    loadEarlier.hidden = !nextAfter;
  }
};

speakerFilter.addEventListener("click", (event) => {
  const button = event.target.closest("button[data-value]");
  if (!button) return;
  selectedSpeaker = button.dataset.value;
  updateFilters();
  render();
  filterMenu.open = false;
});
loadEarlier.addEventListener("click", loadEarlierTurns);
refreshState();
refreshTurns();
setInterval(refreshState, 3000);
setInterval(refreshTurns, 5000);
