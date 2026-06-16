package com.leetcode.dashboard.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(indexes = {
    @Index(columnList = "userId, leetcodeNumber")
})
public class Problem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Integer leetcodeNumber;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    private Difficulty difficulty;

    private String topic;       // e.g. "Arrays", "Trees", "DP"
    private String pattern;     // e.g. "Sliding Window", "Two Pointers"

    private LocalDate solvedDate;
    private LocalDate firstAttempted;

    @Column(length = 5000)
    private String notes;

    private String leetcodeUrl;

    // how confident you felt: 1-5
    private Integer confidence;

    // stored from last accepted submission
    private Long   lastSubmissionId;
    private String lastLang;
    private String lastRuntime;
    private String lastMemory;

    @Column(nullable = false)
    private String userId;

    private LocalDate dueDate;

    @Transient
    private Long submissionTimestamp; // transient — carries raw LC timestamp through sync pipeline, not persisted
}
