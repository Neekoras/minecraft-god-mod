const eventsElement = document.querySelector("#events");
const emptyElement = document.querySelector("#empty");
const template = document.querySelector("#event-template");
const connection = document.querySelector(".connection");
let events = [];
let filter = "all";

const renderState = (state = {}) => {
  const players = state.players || [];
  const schedules = state.scheduled_events || [];
  document.querySelector("#online-count").textContent = players.length;
  document.querySelector("#queue-depth").textContent = `queue ${state.queue_depth || 0}`;
  const weather = state.thundering ? "thunder" : state.raining ? "rain" : "clear";
  document.querySelector("#world-state").textContent = `day ${state.daytime_ticks ?? "—"} · ${weather}`;

  const playerList = document.querySelector("#players");
  playerList.replaceChildren();
  if (!players.length) playerList.innerHTML = '<p class="empty-inline">nobody online</p>';
  for (const player of players) {
    const row = document.createElement("div");
    row.className = "player-row";
    const health = Math.max(0, Math.min(100, (player.health / player.max_health) * 100));
    row.innerHTML = `<div><strong></strong><span class="coordinates"></span></div>
      <div class="hearts"><i style="width:${health}%"></i></div>
      <p class="player-detail"></p><p class="quest"></p>`;
    row.querySelector("strong").textContent = player.name;
    row.querySelector(".coordinates").textContent = `${player.x} ${player.y} ${player.z}`;
    row.querySelector(".player-detail").textContent = `${player.health.toFixed(1)}/${player.max_health.toFixed(1)} hearts · ${player.hunger}/20 food · ${player.holding}`;
    row.querySelector(".quest").textContent = player.quest;
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

const textContent = (item) => (item.content || [])
  .map((part) => part.text || part.refusal || "")
  .filter(Boolean)
  .join("\n");

const eventFrom = (item, index, total) => {
  if (item.type === "function_call") {
    let argumentsValue = item.arguments;
    try { argumentsValue = JSON.parse(item.arguments); } catch (_) { /* show raw arguments */ }
    return { sequence: total - index, type: `tool · ${item.name}`, kind: "tool", data: argumentsValue };
  }
  if (item.type === "function_call_output") {
    const failed = typeof item.output === "string" && item.output.startsWith("error:");
    return {
      sequence: total - index,
      type: failed ? "tool error" : "tool result",
      kind: failed ? "error" : "tool",
      data: item.output,
    };
  }
  if (item.type === "message") {
    const text = textContent(item);
    const automatic = text.startsWith("Automatic server event concerning ");
    return {
      sequence: total - index,
      type: item.role === "assistant" ? "model reply" : automatic ? "server event" : "player input",
      kind: item.role === "assistant" ? "model" : "input",
      data: text,
    };
  }
  if (item.type === "reasoning") {
    return {
      sequence: total - index,
      type: "reasoning summary",
      kind: "reasoning",
      data: (item.summary || []).map((part) => part.text).filter(Boolean).join("\n") || "No summary was exposed by the model.",
    };
  }
  return { sequence: total - index, type: item.type, kind: "model", data: item };
};

const render = () => {
  eventsElement.replaceChildren();
  const visible = events.filter((event) => filter === "all" || event.kind === filter);
  for (const event of visible) {
    const fragment = template.content.cloneNode(true);
    const article = fragment.querySelector("article");
    article.dataset.kind = event.kind;
    fragment.querySelector(".sequence").textContent = `#${event.sequence}`;
    fragment.querySelector(".event-type").textContent = event.type;
    fragment.querySelector(".event-player").textContent = event.kind;
    fragment.querySelector("pre").textContent = typeof event.data === "string"
      ? event.data
      : JSON.stringify(event.data, null, 2);
    eventsElement.append(fragment);
  }
  emptyElement.hidden = visible.length > 0;
};

const refresh = async () => {
  try {
    const response = await fetch("/api/activity", { cache: "no-store" });
    const payload = await response.json();
    if (!response.ok) throw new Error(payload.error || `HTTP ${response.status}`);
    events = (payload.items || []).map((item, index, items) => eventFrom(item, index, items.length));
    connection.className = "connection live";
    document.querySelector("#connection").textContent = payload.conversation ? "live" : "waiting";
    document.querySelector("#event-count").textContent = events.length;
    document.querySelector("#model-name").textContent = payload.conversation?.model || "—";
    document.querySelector("#tool-count").textContent = events.filter((event) => event.type.startsWith("tool ·")).length;
    renderState(payload.state);
    render();
  } catch (error) {
    connection.className = "connection error";
    document.querySelector("#connection").textContent = "offline";
  }
};

document.querySelectorAll("button[data-filter]").forEach((button) => {
  button.addEventListener("click", () => {
    document.querySelector("button.active").classList.remove("active");
    button.classList.add("active");
    filter = button.dataset.filter;
    render();
  });
});

refresh();
setInterval(refresh, 3000);
