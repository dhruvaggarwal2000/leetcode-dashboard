package com.leetcode.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@ConditionalOnExpression("${app.chat.enabled:true} and '${app.chat.provider:cli}' == 'api'")
public class AnthropicApiChatService implements ChatProvider {

    private static final String SYSTEM_PROMPT = """
            You are a LeetCode practice coach operating in a strict Socratic role.
            Your job is to guide the user to the solution themselves, NOT to give it to them.

            Hard rules:
            1. Do NOT write full solution code in your responses. No Java, no Python, no pseudocode listings.
               Pattern names, single-line conceptual hints, and small invariants are okay.
            2. Do NOT state the algorithm outright. If the user describes a problem, your first reply
               must be questions and hints only — never the answer.
            3. When the user proposes an approach, restate it in one short line and then probe it:
               ask about edge cases, complexity, or a counterexample. Never simply validate ("yes that works").
            4. Use pointed questions: "what are you tracking as you scan?", "what does each iteration buy you?",
               "what's the brute force, and where is it wasteful?", "what invariant holds at index i?".
            5. Override only if the user explicitly asks twice in the same turn (e.g. "just show me the code",
               "give me the solution") — then give a minimal implementation with a one-line complexity note.
            6. Be terse. One short paragraph OR 2–4 bullet questions per turn. No encouragement,
               no recapping what the user said, no filler phrases.
            """;

    private static final String ANTHROPIC_VERSION = "2023-06-01";

    @Value("${app.chat.anthropic.api-key:}")
    private String apiKey;

    @Value("${app.chat.anthropic.model:claude-sonnet-4-6}")
    private String model;

    @Value("${app.chat.anthropic.max-tokens:4096}")
    private int maxTokens;

    @Value("${app.chat.anthropic.base-url:https://api.anthropic.com}")
    private String baseUrl;

    private WebClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, List<Map<String, String>>> historyByUser = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("[anthropic] WARNING: app.chat.anthropic.api-key is not set. Chat endpoint will return errors.");
        }
        client = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("anthropic-version", ANTHROPIC_VERSION)
                .defaultHeader("content-type", "application/json")
                .build();
    }

    @Override
    public void clearSession(String userId) {
        historyByUser.remove(userId);
    }

    @Override
    public void streamReply(String userId, String message, SseEmitter emitter) {
        if (apiKey == null || apiKey.isBlank()) {
            failEmitter(emitter, new IllegalStateException("Anthropic API key is not configured (app.chat.anthropic.api-key)"));
            return;
        }

        List<Map<String, String>> history = historyByUser.computeIfAbsent(userId, k -> new ArrayList<>());
        synchronized (history) {
            history.add(Map.of("role", "user", "content", message));
        }

        Map<String, Object> body = buildRequestBody(history);
        StringBuilder assistantBuffer = new StringBuilder();

        sendStatus(emitter, "thinking");

        ParameterizedTypeReference<ServerSentEvent<String>> sseType = new ParameterizedTypeReference<>() {};
        client.post()
                .uri("/v1/messages")
                .header("x-api-key", apiKey)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(sseType)
                .subscribe(
                        sse -> handleSse(sse, emitter, assistantBuffer),
                        err -> {
                            persistAssistant(userId, assistantBuffer);
                            failEmitter(emitter, err);
                        },
                        () -> {
                            persistAssistant(userId, assistantBuffer);
                            try { emitter.complete(); } catch (Exception ignored) {}
                        }
                );
    }

    private Map<String, Object> buildRequestBody(List<Map<String, String>> history) {
        List<Map<String, String>> messages;
        synchronized (history) {
            messages = new ArrayList<>(history);
        }
        Map<String, Object> systemBlock = new HashMap<>();
        systemBlock.put("type", "text");
        systemBlock.put("text", SYSTEM_PROMPT);
        systemBlock.put("cache_control", Map.of("type", "ephemeral"));

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("stream", true);
        body.put("system", List.of(systemBlock));
        body.put("messages", messages);
        return body;
    }

    private void handleSse(ServerSentEvent<String> sse, SseEmitter emitter, StringBuilder assistantBuffer) {
        String data = sse.data();
        if (data == null || data.isBlank()) return;
        JsonNode node;
        try {
            node = mapper.readTree(data);
        } catch (Exception e) {
            return;
        }
        String type = node.path("type").asText();
        switch (type) {
            case "content_block_delta" -> {
                JsonNode delta = node.path("delta");
                if ("text_delta".equals(delta.path("type").asText())) {
                    String chunk = delta.path("text").asText("");
                    if (!chunk.isEmpty()) {
                        assistantBuffer.append(chunk);
                        sendDelta(emitter, chunk);
                    }
                }
            }
            case "error" -> {
                String msg = node.path("error").path("message").asText("anthropic api error");
                sendError(emitter, msg);
            }
            default -> { /* message_start, content_block_start, message_delta, message_stop, ping — ignore */ }
        }
    }

    private void persistAssistant(String userId, StringBuilder assistantBuffer) {
        String text = assistantBuffer.toString();
        if (text.isEmpty()) return;
        List<Map<String, String>> history = historyByUser.get(userId);
        if (history == null) return;
        synchronized (history) {
            history.add(Map.of("role", "assistant", "content", text));
        }
    }

    private void sendDelta(SseEmitter emitter, String text) {
        try {
            emitter.send(SseEmitter.event().name("delta").data(Map.of("text", text)));
        } catch (Exception ignored) {}
    }

    private void sendStatus(SseEmitter emitter, String status) {
        try {
            emitter.send(SseEmitter.event().name("status").data(Map.of("status", status)));
        } catch (Exception ignored) {}
    }

    private void sendError(SseEmitter emitter, String text) {
        try {
            emitter.send(SseEmitter.event().name("error").data(Map.of("text", text)));
        } catch (Exception ignored) {}
    }

    private void failEmitter(SseEmitter emitter, Throwable t) {
        try {
            sendError(emitter, t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
        } finally {
            try { emitter.completeWithError(t); } catch (Exception ignored) {}
        }
    }
}
