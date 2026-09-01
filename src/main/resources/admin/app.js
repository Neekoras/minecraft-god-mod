const eventsElement = document.querySelector("#events");
const emptyElement = document.querySelector("#empty");
const template = document.querySelector("#event-template");
const connection = document.querySelector(".connection");
let events = [];
let filter = "all";

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
