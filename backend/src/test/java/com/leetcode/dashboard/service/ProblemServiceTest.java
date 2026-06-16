package com.leetcode.dashboard.service;

import com.leetcode.dashboard.dto.StatsDTO;
import com.leetcode.dashboard.model.Difficulty;
import com.leetcode.dashboard.model.Problem;
import com.leetcode.dashboard.repository.ProblemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProblemServiceTest {

    private static final String USER = "alice";

    @Mock
    private ProblemRepository repository;

    @InjectMocks
    private ProblemService service;

    @Test
    void getAll_returnsEmptyListForEmptyRepo() {
        when(repository.findByUserId(USER)).thenReturn(List.of());

        List<Problem> result = service.getAll(USER);

        assertThat(result).isEmpty();
    }

    @Test
    void getAll_dedupsByLeetcodeNumber() {
        Problem a = problem(1L, 1, "Two Sum", LocalDate.of(2026, 1, 1), 2);
        Problem b = problem(2L, 1, "Two Sum", LocalDate.of(2026, 5, 1), 4);
        when(repository.findByUserId(USER)).thenReturn(List.of(a, b));

        List<Problem> result = service.getAll(USER);

        assertThat(result).hasSize(1);
        // higher confidence wins
        assertThat(result.get(0).getId()).isEqualTo(2L);
    }

    @Test
    void getAll_dedupsByTitleWhenNumberIsNull() {
        Problem a = new Problem();
        a.setId(1L);
        a.setTitle("Custom Problem");
        a.setSolvedDate(LocalDate.of(2026, 1, 1));

        Problem b = new Problem();
        b.setId(2L);
        b.setTitle("Custom Problem");
        b.setSolvedDate(LocalDate.of(2026, 5, 1));

        when(repository.findByUserId(USER)).thenReturn(List.of(a, b));

        List<Problem> result = service.getAll(USER);

        assertThat(result).hasSize(1);
        // confidence is equal (both null), more recent solved_date wins
        assertThat(result.get(0).getId()).isEqualTo(2L);
    }

    @Test
    void getAll_confidenceBeatsRecency() {
        Problem old = problem(1L, 5, "Foo", LocalDate.of(2026, 1, 1), 4);
        Problem recent = problem(2L, 5, "Foo", LocalDate.of(2026, 6, 1), 0);
        when(repository.findByUserId(USER)).thenReturn(List.of(old, recent));

        List<Problem> result = service.getAll(USER);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(1L);
    }

    @Test
    void getAll_mergesMissingTopicAndPatternFromLoserIntoWinner() {
        Problem winner = problem(1L, 1, "Two Sum", LocalDate.of(2026, 5, 1), 4);
        winner.setTopic(null);
        winner.setPattern(null);

        Problem loser = problem(2L, 1, "Two Sum", LocalDate.of(2026, 1, 1), 1);
        loser.setTopic("Array");
        loser.setPattern("Hashing");

        when(repository.findByUserId(USER)).thenReturn(List.of(winner, loser));

        List<Problem> result = service.getAll(USER);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(1L);
        assertThat(result.get(0).getTopic()).isEqualTo("Array");
        assertThat(result.get(0).getPattern()).isEqualTo("Hashing");
    }

    @Test
    void getAll_fillsMissingFieldsOnExistingWhenCandidateLoses() {
        Problem existing = problem(1L, 1, "Two Sum", LocalDate.of(2026, 5, 1), 4);
        existing.setTopic(null);
        existing.setPattern(null);

        Problem candidate = problem(2L, 1, "Two Sum", LocalDate.of(2026, 1, 1), 1);
        candidate.setTopic("Array");
        candidate.setPattern("Hashing");

        when(repository.findByUserId(USER)).thenReturn(List.of(existing, candidate));

        List<Problem> result = service.getAll(USER);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(1L);
        assertThat(result.get(0).getTopic()).isEqualTo("Array");
        assertThat(result.get(0).getPattern()).isEqualTo("Hashing");
    }

    @Test
    void update_overwritesProblemPreservingIdAndUserId() {
        Problem existing = problem(42L, 1, "Two Sum", LocalDate.of(2026, 1, 1), 3);
        existing.setUserId(USER);

        Problem incoming = new Problem();
        incoming.setNotes("updated notes");
        incoming.setConfidence(5);

        when(repository.findById(42L)).thenReturn(Optional.of(existing));
        when(repository.save(any(Problem.class))).thenAnswer(inv -> inv.getArgument(0));

        Problem saved = service.update(42L, incoming);

        assertThat(saved.getId()).isEqualTo(42L);
        assertThat(saved.getUserId()).isEqualTo(USER);
        assertThat(saved.getNotes()).isEqualTo("updated notes");
        assertThat(saved.getConfidence()).isEqualTo(5);
    }

    @Test
    void update_throwsWhenMissing() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(99L, new Problem()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Problem not found");
    }

    @Test
    void getNeedsReviewPaged_usesLeetcodeNumberOverloadWhenProvided() {
        Page<Problem> page = new PageImpl<>(List.of());
        when(repository.findByUserIdAndConfidenceLessThanAndLeetcodeNumber(
                eq(USER), eq(3), eq(42), any(PageRequest.class))).thenReturn(page);

        Page<Problem> result = service.getNeedsReviewPaged(USER, 0, 10, 42);

        assertThat(result).isSameAs(page);
        verify(repository).findByUserIdAndConfidenceLessThanAndLeetcodeNumber(
                eq(USER), eq(3), eq(42), any(PageRequest.class));
    }

    @Test
    void getNeedsReviewPaged_fallsBackToPlainOverloadWhenNumberAbsent() {
        Page<Problem> page = new PageImpl<>(List.of());
        when(repository.findByUserIdAndConfidenceLessThan(
                eq(USER), eq(3), any(PageRequest.class))).thenReturn(page);

        Page<Problem> result = service.getNeedsReviewPaged(USER, 0, 10, null);

        assertThat(result).isSameAs(page);
    }

    @Test
    void getSimilarByPattern_returnsEmptyWhenPatternIsNull() {
        Problem p = problem(1L, 1, "Two Sum", LocalDate.of(2026, 1, 1), 3);
        p.setPattern(null);
        p.setUserId(USER);
        when(repository.findById(1L)).thenReturn(Optional.of(p));

        List<Problem> result = service.getSimilarByPattern(1L);

        assertThat(result).isEmpty();
    }

    @Test
    void getSimilarByPattern_excludesSelfAndDedups() {
        Problem source = problem(1L, 1, "Two Sum", LocalDate.of(2026, 1, 1), 3);
        source.setPattern("Hashing");
        source.setUserId(USER);

        Problem dupA = problem(2L, 2, "Other", LocalDate.of(2026, 1, 1), 3);
        Problem dupB = problem(3L, 2, "Other", LocalDate.of(2026, 1, 1), 3);
        Problem other = problem(4L, 3, "Another", LocalDate.of(2026, 1, 1), 3);

        when(repository.findById(1L)).thenReturn(Optional.of(source));
        when(repository.findByUserIdAndPattern(USER, "Hashing"))
                .thenReturn(List.of(source, dupA, dupB, other));

        List<Problem> result = service.getSimilarByPattern(1L);

        // source excluded; dupA/dupB deduped by leetcode_number
        assertThat(result).extracting(Problem::getId).containsExactlyInAnyOrder(2L, 4L);
    }

    @Test
    void getStats_aggregatesCountsByTopicPatternDifficultyAndReview() {
        Problem p1 = problem(1L, 1, "Two Sum", LocalDate.of(2026, 1, 1), 4);
        p1.setDifficulty(Difficulty.EASY);
        p1.setTopic("Array");
        p1.setPattern("Hashing");

        Problem p2 = problem(2L, 2, "Add Two Numbers", LocalDate.of(2026, 1, 1), 2);
        p2.setDifficulty(Difficulty.MEDIUM);
        p2.setTopic("Linked List");
        p2.setPattern("Two Pointers");

        Problem p3 = problem(3L, 3, "Median", LocalDate.of(2026, 1, 1), 1);
        p3.setDifficulty(Difficulty.HARD);
        p3.setTopic("Array");
        p3.setPattern("Hashing");

        when(repository.findByUserId(USER)).thenReturn(List.of(p1, p2, p3));

        StatsDTO stats = service.getStats(USER);

        assertThat(stats.getTotalSolved()).isEqualTo(3);
        assertThat(stats.getEasySolved()).isEqualTo(1);
        assertThat(stats.getMediumSolved()).isEqualTo(1);
        assertThat(stats.getHardSolved()).isEqualTo(1);
        assertThat(stats.getByTopic()).containsEntry("Array", 2L).containsEntry("Linked List", 1L);
        assertThat(stats.getByPattern()).containsEntry("Hashing", 2L).containsEntry("Two Pointers", 1L);
        // confidence < 3 → review queue: p2 (2) and p3 (1)
        assertThat(stats.getNeedsReview()).isEqualTo(2);
    }

    @Test
    void getStats_skipsProblemsWithNullTopicOrPattern() {
        Problem p = problem(1L, 1, "Two Sum", LocalDate.of(2026, 1, 1), 4);
        p.setDifficulty(Difficulty.EASY);
        p.setTopic(null);
        p.setPattern(null);

        when(repository.findByUserId(USER)).thenReturn(List.of(p));

        StatsDTO stats = service.getStats(USER);

        assertThat(stats.getByTopic()).isEmpty();
        assertThat(stats.getByPattern()).isEmpty();
        assertThat(stats.getTotalSolved()).isEqualTo(1);
    }

    private static Problem problem(Long id, int leetcodeNumber, String title, LocalDate solvedDate, Integer confidence) {
        Problem p = new Problem();
        p.setId(id);
        p.setLeetcodeNumber(leetcodeNumber);
        p.setTitle(title);
        p.setSolvedDate(solvedDate);
        p.setConfidence(confidence);
        p.setTopic("Array");
        p.setPattern("Hashing");
        p.setUserId(USER);
        return p;
    }
}
