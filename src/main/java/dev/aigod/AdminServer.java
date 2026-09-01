package dev.aigod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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

final class AdminServer implements AutoCloseable {
    private static final String CONVERSATIONS_URL = "https://api.openai.com/v1/conversations/";
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 5;

    private final HttpServer server;
    private final HttpClient http;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final String apiKey;
    private final String model;
    private final byte[] expectedAuthorization;
    private final ConversationStore conversationStore;
    private final Logger logger;
    private volatile String cachedConversationId;
    private volatile String cachedFirstItemId;
    private volatile String cachedBody;

    AdminServer(String apiKey, String model, ConversationStore conversationStore, int port,
                String password, Logger logger)
            throws IOException {
        this.apiKey = apiKey;
        this.model = model;
        this.expectedAuthorization = ("admin:" + password).getBytes(StandardCharsets.UTF_8);
        this.conversationStore = conversationStore;
        this.logger = logger;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(executor)
                .build();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/api/activity", this::activity);
        server.createContext("/", this::asset);
        server.setExecutor(executor);
        server.start();
        logger.info("AI God admin listening on http://127.0.0.1:{}", port);
    }

    private void activity(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) return;
        if (!"GET".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "text/plain; charset=utf-8", "method not allowed");
            return;
        }
        try {
            send(exchange, 200, "application/json; charset=utf-8", activity());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            send(exchange, 503, "application/json; charset=utf-8", error("interrupted"));
        } catch (RuntimeException exception) {
            logger.warn("Could not refresh AI God admin activity", exception);
            String body = cachedBody != null ? cachedBody : error(exception.getMessage());
            send(exchange, cachedBody != null ? 200 : 502, "application/json; charset=utf-8", body);
        }
    }

    private synchronized String activity() throws InterruptedException {
        String conversationId = conversationStore.load();
        if (conversationId == null) return empty();
        String baseUrl = CONVERSATIONS_URL + conversationId;
        JsonObject firstPage = get(baseUrl + "/items?limit=" + PAGE_SIZE + "&order=desc");
        String firstItemId = firstPage.has("first_id") && !firstPage.get("first_id").isJsonNull()
                ? firstPage.get("first_id").getAsString() : null;
        if (conversationId.equals(cachedConversationId)
                && java.util.Objects.equals(firstItemId, cachedFirstItemId)
                && cachedBody != null) return cachedBody;

        JsonObject conversation = get(baseUrl);
        JsonArray items = new JsonArray();
        String after = null;
        boolean hasMore = false;
        for (int pageNumber = 0; pageNumber < MAX_PAGES; pageNumber++) {
            JsonObject page = pageNumber == 0 ? firstPage : get(baseUrl + "/items?limit=" + PAGE_SIZE
                    + "&order=desc&after=" + encode(after));
            JsonArray data = page.getAsJsonArray("data");
            if (data != null) for (JsonElement item : data) items.add(item);
            hasMore = page.has("has_more") && page.get("has_more").getAsBoolean();
            if (!hasMore || !page.has("last_id") || page.get("last_id").isJsonNull()) break;
            after = page.get("last_id").getAsString();
        }

        JsonObject payload = new JsonObject();
        payload.add("conversation", summary(conversation));
        payload.add("items", items);
        payload.addProperty("has_more", hasMore);
        payload.addProperty("refreshed_at", Instant.now().toString());
        cachedConversationId = conversationId;
        cachedFirstItemId = firstItemId;
        cachedBody = payload.toString();
        return cachedBody;
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

    private static String empty() {
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
