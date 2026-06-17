package com.leetcode.dashboard.controller;

import com.leetcode.dashboard.dto.LeetCodeProfileDTO;
import com.leetcode.dashboard.dto.StatsDTO;
import com.leetcode.dashboard.model.Account;
import com.leetcode.dashboard.repository.AccountRepository;
import com.leetcode.dashboard.repository.SubmissionRepository;
import com.leetcode.dashboard.service.ProblemService;
import com.leetcode.dashboard.service.SyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class StatsController {

    private final ProblemService problemService;
    private final SyncService syncService;
    private final AccountRepository accountRepository;
    private final SubmissionRepository submissionRepository;

    @GetMapping("/api/stats")
    public StatsDTO getStats(@RequestHeader("X-User-Id") String userId) {
        return problemService.getStats(userId);
    }

    @GetMapping("/api/stats/heatmap-data")
    public Map<String, Object> getHeatmapData(
            @RequestHeader("X-User-Id") String userId,
            @RequestParam(required = false) Integer year
    ) {
        int targetYear = (year != null && year > 0) ? year : LocalDate.now().getYear();
        LocalDate start = LocalDate.of(targetYear, 1, 1);
        LocalDate end   = LocalDate.of(targetYear, 12, 31);

        Map<String, Long> lcActivity = new LinkedHashMap<>();
        for (Object[] row : submissionRepository.getCountByDay(userId, start, end)) {
            LocalDate date = ((java.sql.Date) row[0]).toLocalDate();
            long count = ((Number) row[1]).longValue();
            if (count > 0) lcActivity.put(date.toString(), count);
        }

        Map<String, Map<String, Long>> problemActivity = new LinkedHashMap<>();
        for (Object[] row : submissionRepository.getActivityByDay(userId, start, end)) {
            LocalDate date     = ((java.sql.Date) row[0]).toLocalDate();
            long newCount      = row[1] != null ? ((Number) row[1]).longValue() : 0L;
            long repeatedCount = row[2] != null ? ((Number) row[2]).longValue() : 0L;
            if (newCount > 0 || repeatedCount > 0)
                problemActivity.put(date.toString(), Map.of("new", newCount, "repeated", repeatedCount));
        }

        return Map.of("lcActivity", lcActivity, "problemActivity", problemActivity);
    }

    @GetMapping("/api/leaderboard")
    public List<Account> getLeaderboard() {
        return accountRepository.findAllByOrderByRankingAsc();
    }

    @PostMapping("/api/leaderboard/refresh")
    public Mono<List<Account>> refreshLeaderboard() {
        return syncService.refreshLeaderboard();
    }

    @GetMapping("/api/account/{userId}")
    public ResponseEntity<Account> getAccount(@PathVariable String userId) {
        return accountRepository.findById(userId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/api/leetcode/{username}")
    public Mono<LeetCodeProfileDTO> getLeetCodeProfile(@PathVariable String username) {
        return syncService.fetchAndSaveProfile(username);
    }

    @PostMapping("/api/leetcode/{username}/sync")
    public Mono<Map<String, Object>> syncSolvedProblems(
            @PathVariable String username,
            @RequestParam(defaultValue = "500") int limit,
            @RequestHeader(value = "X-LC-Session", required = false) String sessionToken
    ) {
        return syncService.syncSolvedProblems(username, limit, sessionToken);
    }
}
