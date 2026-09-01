package dev.aigod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

final class AdminServer implements AutoCloseable {
    private static final String CONVERSATIONS_URL = "https://api.openai.com/v1/conversations/";
    private static final int PAGE_SIZE = 100;

    private final HttpServer server;
    private final HttpClient http;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final String apiKey;
    private final String model;
    private final byte[] expectedAuthorization;
    private final ConversationStore conversationStore;
    private final Supplier<String> stateSupplier;
    private final Logger logger;
    private volatile String cachedConversationId;
    private volatile String cachedFirstItemId;
    private volatile String cachedBody;

    AdminServer(String apiKey, String model, ConversationStore conversationStore, int port,
                String password, Supplier<String> stateSupplier, Logger logger)
            throws IOException {
        this.apiKey = apiKey;
        this.model = model;
        this.expectedAuthorization = ("admin:" + password).getBytes(StandardCharsets.UTF_8);
        this.conversationStore = conversationStore;
        this.stateSupplier = stateSupplier;
        this.logger = logger;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(executor)
                .build();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/api/state", this::state);
        server.createContext("/api/turns", this::turns);
        server.createContext("/", this::asset);
        server.setExecutor(executor);
        server.start();
        logger.info("AI God admin listening on http://127.0.0.1:{}", port);
    }

    private void state(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) return;
        if (!"GET".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "text/plain; charset=utf-8", "method not allowed");
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        payload.add("state", JsonParser.parseString(stateSupplier.get()));
        payload.addProperty("refreshed_at", Instant.now().toString());
        send(exchange, 200, "application/json; charset=utf-8", payload.toString());
    }

    private void turns(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) return;
        if (!"GET".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "text/plain; charset=utf-8", "method not allowed");
            return;
        }
        String after = queryParameter(exchange.getRequestURI(), "after");
        try {
            send(exchange, 200, "application/json; charset=utf-8",
                    conversation(after));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            send(exchange, 503, "application/json; charset=utf-8", error("interrupted"));
        } catch (RuntimeException exception) {
            logger.warn("Could not refresh AI God admin activity", exception);
            boolean canUseCache = after == null && cachedBody != null;
            send(exchange, canUseCache ? 200 : 502, "application/json; charset=utf-8",
                    canUseCache ? cachedBody : error(exception.getMessage()));
        }
    }

    private synchronized String conversation(String after) throws InterruptedException {
        String conversationId = conversationStore.load();
        if (conversationId == null) return empty();
        String baseUrl = CONVERSATIONS_URL + conversationId;
        String pageUrl = baseUrl + "/items?limit=" + PAGE_SIZE + "&order=desc"
                + (after == null ? "" : "&after=" + encode(after));
        JsonObject page = get(pageUrl);
        String firstItemId = page.has("first_id") && !page.get("first_id").isJsonNull()
                ? page.get("first_id").getAsString() : null;
        if (after == null && conversationId.equals(cachedConversationId)
                && java.util.Objects.equals(firstItemId, cachedFirstItemId)
                && cachedBody != null) return cachedBody;

        JsonObject conversation = get(baseUrl);
        JsonArray items = page.getAsJsonArray("data");
        if (items == null) items = new JsonArray();
        boolean hasMore = page.has("has_more") && page.get("has_more").getAsBoolean();

        JsonObject payload = new JsonObject();
        payload.add("conversation", summary(conversation));
        payload.add("items", items);
        payload.addProperty("has_more", hasMore);
        if (hasMore && page.has("last_id") && !page.get("last_id").isJsonNull()) {
            payload.add("next_after", page.get("last_id"));
        }
        payload.addProperty("refreshed_at", Instant.now().toString());
        String body = payload.toString();
        if (after == null) {
            cachedConversationId = conversationId;
            cachedFirstItemId = firstItemId;
            cachedBody = body;
        }
        return body;
    }

    private JsonObject get(String url) throws InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            if (response.statusCode() / 100 != 2) {
                String message = body.has("error")
                        ? body.getAsJsonObject("error").get("message").getAsString()
                        : "OpenAI returned HTTP " + response.statusCode();
                throw new IllegalStateException(message);
            }
            return body;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not reach OpenAI", exception);
        }
    }

    private JsonObject summary(JsonObject conversation) {
        JsonObject summary = new JsonObject();
        for (String field : new String[]{"id", "created_at", "metadata"}) {
            if (conversation.has(field)) summary.add(field, conversation.get(field));
        }
        summary.addProperty("model", model);
        return summary;
    }

    private void asset(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) return;
        if (!"GET".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "text/plain; charset=utf-8", "method not allowed");
            return;
        }
        String path = switch (exchange.getRequestURI().getPath()) {
            case "/", "/index.html" -> "/admin/index.html";
            case "/styles.css" -> "/admin/styles.css";
            case "/app.js" -> "/admin/app.js";
            default -> null;
        };
        if (path == null) {
            send(exchange, 404, "text/plain; charset=utf-8", "not found");
            return;
        }
        try (InputStream input = AdminServer.class.getResourceAsStream(path)) {
            if (input == null) {
                send(exchange, 404, "text/plain; charset=utf-8", "not found");
                return;
            }
            String contentType = path.endsWith(".css") ? "text/css; charset=utf-8"
                    : path.endsWith(".js") ? "text/javascript; charset=utf-8"
                    : "text/html; charset=utf-8";
            send(exchange, 200, contentType, input.readAllBytes());
        }
    }

    private String empty() {
        return "{\"conversation\":null,\"items\":[],\"has_more\":false}";
    }

    private static String error(String message) {
        JsonObject body = new JsonObject();
        body.addProperty("error", message == null ? "unknown error" : message);
        return body.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String queryParameter(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query == null) return null;
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals(name)) {
                return pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            }
        }
        return null;
    }

    private boolean authorized(HttpExchange exchange) throws IOException {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header != null && header.regionMatches(true, 0, "Basic ", 0, 6)) {
            try {
                byte[] supplied = Base64.getDecoder().decode(header.substring(6).trim());
                if (MessageDigest.isEqual(expectedAuthorization, supplied)) return true;
            } catch (IllegalArgumentException ignored) {
                // Invalid Base64 is an ordinary failed login.
            }
        }
        exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"mcgodmod admin\", charset=\"UTF-8\"");
        send(exchange, 401, "text/plain; charset=utf-8", "authentication required");
        return false;
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        send(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }
}
