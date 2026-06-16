package com.leetcode.dashboard.repository;

import com.leetcode.dashboard.model.Difficulty;
import com.leetcode.dashboard.model.Problem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProblemRepository extends JpaRepository<Problem, Long> {

    List<Problem> findByUserId(String userId);

    List<Problem> findByUserIdAndDifficulty(String userId, Difficulty difficulty);

    List<Problem> findByUserIdAndTopic(String userId, String topic);

    List<Problem> findByUserIdAndPattern(String userId, String pattern);

    Page<Problem> findByUserIdAndConfidenceLessThan(String userId, int confidence, Pageable pageable);

    Page<Problem> findByUserIdAndConfidenceLessThanAndLeetcodeNumber(String userId, int confidence, Integer leetcodeNumber, Pageable pageable);

    void deleteByUserIdAndLeetcodeNumberIsNotNullAndLastSubmissionIdIsNull(String userId);
}
