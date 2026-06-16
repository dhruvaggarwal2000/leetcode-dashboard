package com.leetcode.dashboard.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class StatsDTO {
    private long totalSolved;
    private long easySolved;
    private long mediumSolved;
    private long hardSolved;
    private Map<String, Long> byTopic;
    private Map<String, Long> byPattern;
    // problems with confidence <= 2 — flagged for re-practice
    private long needsReview;
}
