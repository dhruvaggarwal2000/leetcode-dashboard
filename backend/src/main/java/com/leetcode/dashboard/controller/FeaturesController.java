package com.leetcode.dashboard.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/features")
public class FeaturesController {

    @Value("${app.chat.enabled:true}")
    private boolean chatEnabled;

    @Value("${app.chat.provider:cli}")
    private String chatProvider;

    @GetMapping
    public Map<String, Object> features() {
        return Map.of(
                "chatEnabled", chatEnabled,
                "chatProvider", chatProvider
        );
    }
}
