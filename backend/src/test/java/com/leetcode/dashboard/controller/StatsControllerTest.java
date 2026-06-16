package com.leetcode.dashboard.controller;

import com.leetcode.dashboard.dto.LeetCodeProfileDTO;
import com.leetcode.dashboard.dto.StatsDTO;
import com.leetcode.dashboard.model.Account;
import com.leetcode.dashboard.repository.AccountRepository;
import com.leetcode.dashboard.repository.SubmissionRepository;
import com.leetcode.dashboard.service.ProblemService;
import com.leetcode.dashboard.service.SyncService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Mono;

import java.sql.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StatsController.class)
class StatsControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ProblemService problemService;

    @MockBean
    private SyncService syncService;

    @MockBean
    private AccountRepository accountRepository;

    @MockBean
    private SubmissionRepository submissionRepository;

    @Test
    void getStats_returnsDtoFromService() throws Exception {
        StatsDTO dto = StatsDTO.builder()
                .totalSolved(10).easySolved(4).mediumSolved(5).hardSolved(1)
                .byTopic(Map.of("Array", 3L))
                .byPattern(Map.of("Hashing", 2L))
                .needsReview(1)
                .build();
        when(problemService.getStats("alice")).thenReturn(dto);

        mvc.perform(get("/api/stats").header("X-User-Id", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSolved").value(10))
                .andExpect(jsonPath("$.byTopic.Array").value(3))
                .andExpect(jsonPath("$.needsReview").value(1));
    }

    @Test
    void getHeatmapData_shapesRowsIntoMaps() throws Exception {
        // lcActivity: total per day
        List<Object[]> countRows = List.of(
                new Object[]{Date.valueOf("2026-05-10"), 3L},
                new Object[]{Date.valueOf("2026-05-11"), 1L},
                // a zero-count day must be filtered out
                new Object[]{Date.valueOf("2026-05-12"), 0L}
        );
        // problemActivity: new + repeated per day
        List<Object[]> activityRows = List.of(
                new Object[]{Date.valueOf("2026-05-10"), 2L, 1L},
                new Object[]{Date.valueOf("2026-05-11"), 0L, 0L} // both zero → filtered
        );
        when(submissionRepository.getCountByDay(eq("alice"), any(), any())).thenReturn(countRows);
        when(submissionRepository.getActivityByDay(eq("alice"), any(), any())).thenReturn(activityRows);

        mvc.perform(get("/api/stats/heatmap-data").header("X-User-Id", "alice").param("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lcActivity['2026-05-10']").value(3))
                .andExpect(jsonPath("$.lcActivity['2026-05-11']").value(1))
                .andExpect(jsonPath("$.lcActivity['2026-05-12']").doesNotExist())
                .andExpect(jsonPath("$.problemActivity['2026-05-10'].new").value(2))
                .andExpect(jsonPath("$.problemActivity['2026-05-10'].repeated").value(1))
                .andExpect(jsonPath("$.problemActivity['2026-05-11']").doesNotExist());
    }

    @Test
    void getHeatmapData_handlesNullsInActivityRow() throws Exception {
        List<Object[]> activityRows = List.<Object[]>of(
                new Object[]{Date.valueOf("2026-05-10"), null, 4L}
        );
        when(submissionRepository.getCountByDay(eq("alice"), any(), any())).thenReturn(List.of());
        when(submissionRepository.getActivityByDay(eq("alice"), any(), any())).thenReturn(activityRows);

        mvc.perform(get("/api/stats/heatmap-data").header("X-User-Id", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.problemActivity['2026-05-10'].new").value(0))
                .andExpect(jsonPath("$.problemActivity['2026-05-10'].repeated").value(4));
    }

    @Test
    void getLeaderboard_returnsRankedAccounts() throws Exception {
        Account a = Account.builder().userId("alice").totalSolved(50).ranking(100).build();
        when(accountRepository.findAllByOrderByRankingAsc()).thenReturn(List.of(a));

        mvc.perform(get("/api/leaderboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value("alice"))
                .andExpect(jsonPath("$[0].ranking").value(100));
    }

    @Test
    void refreshLeaderboard_delegatesToSyncService() throws Exception {
        Account a = Account.builder().userId("alice").build();
        when(syncService.refreshLeaderboard()).thenReturn(Mono.just(List.of(a)));

        MvcResult result = mvc.perform(post("/api/leaderboard/refresh"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value("alice"));
    }

    @Test
    void getAccount_returns404WhenMissing() throws Exception {
        when(accountRepository.findById("ghost")).thenReturn(Optional.empty());

        mvc.perform(get("/api/account/ghost")).andExpect(status().isNotFound());
    }

    @Test
    void getAccount_returnsAccountWhenPresent() throws Exception {
        Account a = Account.builder().userId("alice").totalSolved(7).build();
        when(accountRepository.findById("alice")).thenReturn(Optional.of(a));

        mvc.perform(get("/api/account/alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("alice"))
                .andExpect(jsonPath("$.totalSolved").value(7));
    }

    @Test
    void getLeetCodeProfile_delegatesToSyncService() throws Exception {
        LeetCodeProfileDTO dto = new LeetCodeProfileDTO();
        dto.setUsername("alice");
        dto.setTotalSolved(42);
        when(syncService.fetchAndSaveProfile("alice")).thenReturn(Mono.just(dto));

        MvcResult result = mvc.perform(get("/api/leetcode/alice"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.totalSolved").value(42));
    }

    @Test
    void syncSolvedProblems_delegatesToSyncService() throws Exception {
        when(syncService.syncSolvedProblems(eq("alice"), eq(500), anyString(), anyString()))
                .thenReturn(Mono.just(Map.of("imported", 3, "updated", 5)));

        MvcResult result = mvc.perform(post("/api/leetcode/alice/sync")
                        .header("X-LC-Session", "session-token")
                        .header("X-CF-Clearance", "cf"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(3))
                .andExpect(jsonPath("$.updated").value(5));
    }
}
