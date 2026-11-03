package io.github.xiaod.deepseekcompanion.chat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import io.github.xiaod.deepseekcompanion.config.CompanionConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal DeepSeek (OpenAI-compatible) chat client. Runs off-thread; callers
 * hop back to the server thread themselves.
 */
public final class LlmClient {
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "xiaod-llm");
        t.setDaemon(true);
        return t;
    });

    public record Msg(String role, String content) {
    }

    private LlmClient() {
    }

    public static CompletableFuture<String> complete(String system, List<Msg> history, String userText) {
        CompanionConfig cfg = CompanionConfig.get();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return call(cfg, system, history, userText);
            } catch (Exception e) {
                DeepseekCompanionClient.LOGGER.warn("[XiaoD] LLM call failed: {}", e.toString());
                throw new RuntimeException(e);
            }
        }, POOL);
    }

    private static String call(CompanionConfig cfg, String system, List<Msg> history, String userText)
            throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.model);
        body.addProperty("temperature", cfg.temperature);
        body.addProperty("max_tokens", cfg.maxTokens);
        body.addProperty("stream", false);

        JsonArray messages = new JsonArray();
        messages.add(msg("system", system));
        if (history != null) {
            for (Msg m : history) {
                messages.add(msg(m.role(), m.content()));
            }
        }
        messages.add(msg("user", userText));
        body.add("messages", messages);

        HttpRequest request = HttpRequest.newBuilder(URI.create(cfg.chatEndpoint()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + cfg.apiKey)
                .timeout(Duration.ofSeconds(cfg.timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (response.statusCode() != 200) {
            String message = json.has("error")
                    ? json.getAsJsonObject("error").getAsJsonObject().get("message").getAsString()
                    : response.body();
            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + message);
        }
        JsonArray choices = json.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IllegalStateException("empty choices");
        }
        return choices.get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString().trim();
    }

    private static JsonObject msg(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    /** Trim the rolling history so we never blow the context window. */
    public static List<Msg> trim(List<Msg> history, int maxMessages) {
        if (history == null || history.size() <= maxMessages) {
            return history == null ? new ArrayList<>() : history;
        }
        return new ArrayList<>(history.subList(history.size() - maxMessages, history.size()));
    }
}
