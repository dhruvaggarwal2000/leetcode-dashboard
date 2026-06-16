package com.leetcode.dashboard.dto;

import lombok.Data;

import java.util.Map;

@Data
public class LeetCodeProfileDTO {
    private String username;
    private int totalSolved;
    private int easySolved;
    private int mediumSolved;
    private int hardSolved;
    private int ranking;
    private double acceptanceRate;
    // tag name -> problems solved, merged across all skill tiers
    private Map<String, Integer> byTopic;
}
