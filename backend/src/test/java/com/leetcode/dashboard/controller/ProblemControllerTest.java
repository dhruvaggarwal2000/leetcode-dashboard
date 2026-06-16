package com.leetcode.dashboard.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leetcode.dashboard.model.Difficulty;
import com.leetcode.dashboard.model.Problem;
import com.leetcode.dashboard.service.ProblemService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProblemController.class)
class ProblemControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ProblemService service;

    @Test
    void getAll_noFilters_callsGetAll() throws Exception {
        when(service.getAll("alice")).thenReturn(List.of(problem(1L, 1, "Two Sum")));

        mvc.perform(get("/api/problems").header("X-User-Id", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Two Sum"));

        verify(service).getAll("alice");
    }

    @Test
    void getAll_withDifficulty_routesToDifficultyQuery() throws Exception {
        when(service.getByDifficulty("alice", Difficulty.MEDIUM)).thenReturn(List.of());

        mvc.perform(get("/api/problems").header("X-User-Id", "alice").param("difficulty", "medium"))
                .andExpect(status().isOk());

        verify(service).getByDifficulty("alice", Difficulty.MEDIUM);
    }

    @Test
    void getAll_withTopic_routesToTopicQuery() throws Exception {
        when(service.getByTopic("alice", "Array")).thenReturn(List.of());

        mvc.perform(get("/api/problems").header("X-User-Id", "alice").param("topic", "Array"))
                .andExpect(status().isOk());

        verify(service).getByTopic("alice", "Array");
    }

    @Test
    void getAll_withPattern_routesToPatternQuery() throws Exception {
        when(service.getByPattern("alice", "Hashing")).thenReturn(List.of());

        mvc.perform(get("/api/problems").header("X-User-Id", "alice").param("pattern", "Hashing"))
                .andExpect(status().isOk());

        verify(service).getByPattern("alice", "Hashing");
    }

    @Test
    void update_returnsUpdatedProblem() throws Exception {
        Problem updated = problem(42L, 1, "Two Sum");
        updated.setNotes("solved with hashmap");
        when(service.update(eq(42L), any(Problem.class))).thenReturn(updated);

        mvc.perform(put("/api/problems/42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new Problem())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.notes").value("solved with hashmap"));
    }

    @Test
    void delete_returnsNoContent() throws Exception {
        mvc.perform(delete("/api/problems/42")).andExpect(status().isNoContent());
        verify(service).delete(42L);
    }

    @Test
    void getNeedsReview_wrapsPageInExpectedShape() throws Exception {
        Problem p = problem(7L, 7, "Reverse Integer");
        when(service.getNeedsReviewPaged("alice", 0, 8, null))
                .thenReturn(new PageImpl<>(List.of(p), PageRequest.of(0, 8), 1));

        mvc.perform(get("/api/problems/review").header("X-User-Id", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(7))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.page").value(0));
    }

    @Test
    void getSimilar_returnsListFromService() throws Exception {
        when(service.getSimilarByPattern(42L)).thenReturn(List.of(problem(9L, 9, "Sibling")));

        mvc.perform(get("/api/problems/42/similar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(9));
    }

    private static Problem problem(Long id, int number, String title) {
        Problem p = new Problem();
        p.setId(id);
        p.setLeetcodeNumber(number);
        p.setTitle(title);
        p.setSolvedDate(LocalDate.of(2026, 1, 1));
        return p;
    }
}
