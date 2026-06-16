package com.leetcode.dashboard.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(indexes = {
    @Index(columnList = "userId, submissionDate"),
    @Index(columnList = "userId, leetcodeNumber")
})
public class Submission {

    @Id
    private Long id; // LeetCode submission ID

    private String userId;
    private Integer leetcodeNumber;
    private LocalDate submissionDate;
    private Long submissionTimestamp; // Unix epoch seconds from LeetCode
}
