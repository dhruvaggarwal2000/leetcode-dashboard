package com.leetcode.dashboard.controller;

import com.leetcode.dashboard.dto.ChatRequest;
import com.leetcode.dashboard.service.ChatProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.chat.enabled", havingValue = "true", matchIfMissing = true)
public class ChatController {

    private static final long EMITTER_TIMEOUT_MS = 5 * 60 * 1000L;

    private final ChatProvider chatService;

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestHeader("X-User-Id") String userId,
            @RequestBody ChatRequest request
    ) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        chatService.streamReply(userId, request.getMessage(), emitter);
        return emitter;
    }

    @DeleteMapping("/session")
    public ResponseEntity<Void> clearSession(@RequestHeader("X-User-Id") String userId) {
        chatService.clearSession(userId);
        return ResponseEntity.noContent().build();
    }
}
