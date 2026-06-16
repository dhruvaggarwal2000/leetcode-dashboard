package com.leetcode.dashboard.service;

import com.leetcode.dashboard.dto.StatsDTO;
import com.leetcode.dashboard.model.Difficulty;
import com.leetcode.dashboard.model.Problem;
import com.leetcode.dashboard.repository.ProblemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProblemService {

    private final ProblemRepository repository;

    public List<Problem> getAll(String userId) {
        List<Problem> all = repository.findByUserId(userId);
        Map<String, Problem> unique = new LinkedHashMap<>();
        for (Problem p : all) {
            String key = p.getLeetcodeNumber() != null ? "n:" + p.getLeetcodeNumber() : "t:" + p.getTitle();
            Problem existing = unique.get(key);
            if (existing == null) {
                unique.put(key, p);
            } else if (isBetter(p, existing)) {
                // carry over topic/pattern from the displaced record if the winner lacks them
                if (p.getTopic() == null)   p.setTopic(existing.getTopic());
                if (p.getPattern() == null) p.setPattern(existing.getPattern());
                unique.put(key, p);
            } else {
                // keep existing but fill in any missing fields from the candidate
                if (existing.getTopic() == null)   existing.setTopic(p.getTopic());
                if (existing.getPattern() == null) existing.setPattern(p.getPattern());
            }
        }
        return new ArrayList<>(unique.values());
    }

    private boolean isBetter(Problem candidate, Problem existing) {
        boolean cHasConf = candidate.getConfidence() != null && candidate.getConfidence() > 0;
        boolean eHasConf = existing.getConfidence()  != null && existing.getConfidence()  > 0;
        if (cHasConf && !eHasConf) return true;
        if (!cHasConf && eHasConf) return false;
        if (candidate.getSolvedDate() != null && existing.getSolvedDate() != null)
            return candidate.getSolvedDate().isAfter(existing.getSolvedDate());
        return false;
    }

    public Problem update(Long id, Problem updated) {
        Problem existing = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Problem not found: " + id));
        updated.setId(existing.getId());
        updated.setUserId(existing.getUserId());
        return repository.save(updated);
    }

    public void delete(Long id) {
        repository.deleteById(id);
    }

    public List<Problem> getByDifficulty(String userId, Difficulty difficulty) {
        return repository.findByUserIdAndDifficulty(userId, difficulty);
    }

    public List<Problem> getByTopic(String userId, String topic) {
        return repository.findByUserIdAndTopic(userId, topic);
    }

    public List<Problem> getByPattern(String userId, String pattern) {
        return repository.findByUserIdAndPattern(userId, pattern);
    }

    public Page<Problem> getNeedsReviewPaged(String userId, int page, int size, Integer lcNumber) {
        PageRequest pr = PageRequest.of(page, size);
        if (lcNumber != null)
            return repository.findByUserIdAndConfidenceLessThanAndLeetcodeNumber(userId, 3, lcNumber, pr);
        return repository.findByUserIdAndConfidenceLessThan(userId, 3, pr);
    }

    public List<Problem> getSimilarByPattern(Long id) {
        Problem p = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Problem not found: " + id));
        if (p.getPattern() == null) return List.of();
        Map<String, Problem> seen = new java.util.LinkedHashMap<>();
        for (Problem other : repository.findByUserIdAndPattern(p.getUserId(), p.getPattern())) {
            if (other.getId().equals(id)) continue;
            String key = other.getLeetcodeNumber() != null
                    ? "n:" + other.getLeetcodeNumber()
                    : "t:" + other.getTitle();
            seen.putIfAbsent(key, other);
        }
        return new ArrayList<>(seen.values());
    }

    public StatsDTO getStats(String userId) {
        List<Problem> all = getAll(userId);

        Map<String, Long> byTopic = all.stream()
                .filter(p -> p.getTopic() != null)
                .collect(Collectors.groupingBy(Problem::getTopic, Collectors.counting()));

        Map<String, Long> byPattern = all.stream()
                .filter(p -> p.getPattern() != null)
                .collect(Collectors.groupingBy(Problem::getPattern, Collectors.counting()));

        long easy   = all.stream().filter(p -> p.getDifficulty() == Difficulty.EASY).count();
        long medium = all.stream().filter(p -> p.getDifficulty() == Difficulty.MEDIUM).count();
        long hard   = all.stream().filter(p -> p.getDifficulty() == Difficulty.HARD).count();
        long review = all.stream().filter(p -> p.getConfidence() != null && p.getConfidence() < 3).count();

        return StatsDTO.builder()
                .totalSolved(all.size())
                .easySolved(easy)
                .mediumSolved(medium)
                .hardSolved(hard)
                .byTopic(byTopic)
                .byPattern(byPattern)
                .needsReview(review)
                .build();
    }
}
