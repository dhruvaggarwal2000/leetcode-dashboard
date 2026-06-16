package com.leetcode.dashboard.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import jakarta.persistence.Column;
import java.time.LocalDateTime;

@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Account {

    @Id
    private String userId;       // LeetCode username

    private int totalSolved;
    private int easySolved;
    private int mediumSolved;
    private int hardSolved;
    private int ranking;
    private double acceptanceRate;
    private LocalDateTime lastSyncedAt;

}
