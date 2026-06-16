package com.leetcode.dashboard.controller;

import com.leetcode.dashboard.model.Difficulty;
import com.leetcode.dashboard.model.Problem;
import com.leetcode.dashboard.service.ProblemService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/problems")
@RequiredArgsConstructor
public class ProblemController {

    private final ProblemService service;

    @GetMapping
    public List<Problem> getAll(
            @RequestHeader("X-User-Id") String userId,
            @RequestParam(required = false) String difficulty,
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String pattern
    ) {
        if (difficulty != null) return service.getByDifficulty(userId, Difficulty.valueOf(difficulty.toUpperCase()));
        if (topic != null)      return service.getByTopic(userId, topic);
        if (pattern != null)    return service.getByPattern(userId, pattern);
        return service.getAll(userId);
    }

    @PutMapping("/{id}")
    public Problem update(@PathVariable Long id, @RequestBody Problem problem) {
        return service.update(id, problem);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/review")
    public Map<String, Object> getNeedsReview(
            @RequestHeader("X-User-Id") String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "8") int size,
            @RequestParam(required = false) Integer q
    ) {
        Page<Problem> result = service.getNeedsReviewPaged(userId, page, size, q);
        return Map.of(
            "content",      result.getContent(),
            "totalElements", result.getTotalElements(),
            "totalPages",   result.getTotalPages(),
            "page",         result.getNumber()
        );
    }

    @GetMapping("/{id}/similar")
    public List<Problem> getSimilar(@PathVariable Long id) {
        return service.getSimilarByPattern(id);
    }
}
