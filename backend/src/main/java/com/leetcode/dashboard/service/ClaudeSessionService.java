package com.leetcode.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
@ConditionalOnExpression("${app.chat.enabled:true} and '${app.chat.provider:cli}' == 'cli'")
public class ClaudeSessionService implements ChatProvider {

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

    private static final long PROCESS_TIMEOUT_SECONDS = 300;

    @Value("${app.chat.cli.model:sonnet}")
    private String model;

    private final ConcurrentHashMap<String, String> sessionIdByUser = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "claude-chat");
        t.setDaemon(true);
        return t;
    });

    private String claudeBinary;

    @PostConstruct
    void resolveClaudeBinary() {
        // Resolve once at boot so we have a working path even when launched from
        // an IDE that strips the env. Candidate list differs per OS.
        for (String candidate : binaryCandidates()) {
            try {
                Process p = new ProcessBuilder(candidate, "--version").redirectErrorStream(true).start();
                if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    claudeBinary = candidate;
                    System.out.println("[claude] using binary: " + candidate);
                    return;
                }
            } catch (Exception ignored) {}
        }
        System.err.println("[claude] WARNING: claude CLI not found on PATH or common install locations. Chat endpoint will return errors.");
    }

    private List<String> binaryCandidates() {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
        if (isWindows) {
            // npm-global install: %APPDATA%\npm\claude.cmd ; PATH lookup picks up both .cmd and .exe.
            List<String> out = new ArrayList<>();
            String appData      = System.getenv("APPDATA");
            String localAppData = System.getenv("LOCALAPPDATA");
            if (appData != null)      out.add(appData      + "\\npm\\claude.cmd");
            if (localAppData != null) out.add(localAppData + "\\Programs\\claude\\claude.exe");
            out.add("claude.cmd");
            out.add("claude.exe");
            out.add("claude");
            return out;
        }
        return List.of("/opt/homebrew/bin/claude", "/usr/local/bin/claude", "claude");
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public void clearSession(String userId) {
        sessionIdByUser.remove(userId);
    }

    public void streamReply(String userId, String message, SseEmitter emitter) {
        if (claudeBinary == null) {
            failEmitter(emitter, new IllegalStateException("claude CLI is not available on the server"));
            return;
        }
        executor.submit(() -> runClaude(userId, message, emitter));
    }

    private void runClaude(String userId, String message, SseEmitter emitter) {
        List<String> command = new ArrayList<>();
        command.add(claudeBinary);
        command.add("-p");
        command.add("--model");
        command.add(model);
        command.add("--output-format");
        command.add("stream-json");
        command.add("--verbose");
        command.add("--include-partial-messages");
        command.add("--append-system-prompt");
        command.add(SYSTEM_PROMPT);

        String existingSessionId = sessionIdByUser.get(userId);
        if (existingSessionId != null) {
            command.add("--resume");
            command.add(existingSessionId);
        }
        command.add(message);

        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(false)
                    .start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    handleEvent(userId, line, emitter);
                }
            }

            boolean exited = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                failEmitter(emitter, new RuntimeException("claude CLI timed out after " + PROCESS_TIMEOUT_SECONDS + "s"));
                return;
            }
            emitter.complete();
        } catch (Exception e) {
            if (process != null && process.isAlive()) process.destroyForcibly();
            failEmitter(emitter, e);
        }
    }

    private void handleEvent(String userId, String line, SseEmitter emitter) {
        JsonNode event;
        try {
            event = mapper.readTree(line);
        } catch (Exception e) {
            return; // skip non-JSON lines defensively
        }
        String type = event.path("type").asText();

        switch (type) {
            case "system" -> {
                String subtype = event.path("subtype").asText();
                if ("init".equals(subtype)) {
                    String sid = event.path("session_id").asText(null);
                    if (sid != null) sessionIdByUser.put(userId, sid);
                    sendStatus(emitter, "thinking");
                } else if ("status".equals(subtype)) {
                    // claude emits status=requesting while waiting on the model
                    sendStatus(emitter, event.path("status").asText("thinking"));
                }
            }
            case "stream_event" -> {
                JsonNode inner = event.path("event");
                String innerType = inner.path("type").asText();
                if ("content_block_delta".equals(innerType)) {
                    JsonNode delta = inner.path("delta");
                    if ("text_delta".equals(delta.path("type").asText())) {
                        String chunk = delta.path("text").asText("");
                        if (!chunk.isEmpty()) sendDelta(emitter, chunk);
                    }
                }
            }
            // The 'assistant' event delivers the full final text — we already streamed
            // token deltas via stream_event, so ignore it to avoid duplication.
            case "result" -> {
                if (event.path("is_error").asBoolean(false)) {
                    String errText = event.path("result").asText("claude returned an error");
                    sendError(emitter, errText);
                }
            }
            default -> { /* ignore rate_limit_event, user, etc */ }
        }
    }

    private void sendDelta(SseEmitter emitter, String text) {
        try {
            emitter.send(SseEmitter.event().name("delta").data(Map.of("text", text)));
        } catch (Exception e) {
            // emitter closed by client; nothing to do
        }
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
            emitter.completeWithError(t);
        }
    }
}
