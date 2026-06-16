package com.leetcode.dashboard.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface ChatProvider {
    void streamReply(String userId, String message, SseEmitter emitter);
    void clearSession(String userId);
}
