package com.leetcode.dashboard.service;

import com.leetcode.dashboard.dto.LeetCodeProfileDTO;
import com.leetcode.dashboard.model.Account;
import com.leetcode.dashboard.model.Problem;
import com.leetcode.dashboard.model.Submission;
import com.leetcode.dashboard.repository.AccountRepository;
import com.leetcode.dashboard.repository.ProblemRepository;
import com.leetcode.dashboard.repository.SubmissionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SyncServiceTest {

    private static final String USER = "alice";

    @Mock
    private LeetCodeService leetCodeService;

    @Mock
    private ProblemRepository problemRepository;

    @Mock
    private SubmissionRepository submissionRepository;

    @Mock
    private AccountRepository accountRepository;

    @InjectMocks
    private SyncService syncService;

    @Test
    void anonymousSync_writesProblemOnly_noSubmissionWrites_noOrphanDelete() {
        Problem incoming = newIncoming(1, "Two Sum", LocalDate.of(2026, 6, 1), 1001L);
        when(problemRepository.findByUserId(USER)).thenReturn(List.of());
        when(leetCodeService.getRecentSolvedProblems(USER, 500)).thenReturn(Flux.just(incoming));
        when(leetCodeService.getProfile(USER)).thenReturn(Mono.just(profile()));

        Map<String, Object> result = syncService.syncSolvedProblems(USER, 500, null).block();

        assertThat(result).containsEntry("imported", 1).containsEntry("updated", 0);

        ArgumentCaptor<List<Problem>> saved = problemSaveCaptor();
        verify(problemRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(1);
        assertThat(saved.getValue().get(0).getLeetcodeNumber()).isEqualTo(1);
        assertThat(saved.getValue().get(0).getFirstAttempted()).isEqualTo(LocalDate.of(2026, 6, 1));

        // No Submission writes in anonymous mode
        ArgumentCaptor<List<Submission>> subs = submissionSaveCaptor();
        verify(submissionRepository).saveAll(subs.capture());
        assertThat(subs.getValue()).isEmpty();

        // No orphan delete in anonymous mode
        verify(problemRepository, never())
                .deleteByUserIdAndLeetcodeNumberIsNotNullAndLastSubmissionIdIsNull(anyString());

        // Anonymous mode should NOT query known submission IDs
        verify(submissionRepository, never()).findSubmissionIdsByUserId(anyString());
    }

    @Test
    void authenticatedSync_writesProblemAndSubmission_callsOrphanDelete() {
        Problem incoming = newIncoming(1, "Two Sum", LocalDate.of(2026, 6, 1), 1001L);
        incoming.setSubmissionTimestamp(1717200000L);

        when(problemRepository.findByUserId(USER)).thenReturn(List.of());
        when(submissionRepository.findSubmissionIdsByUserId(USER)).thenReturn(Set.of());
        when(leetCodeService.getAllSolvedProblems(eq("session-token"), any()))
                .thenReturn(Flux.just(incoming));
        when(leetCodeService.getProfile(USER)).thenReturn(Mono.just(profile()));

        Map<String, Object> result = syncService.syncSolvedProblems(USER, 500, "session-token").block();

        assertThat(result).containsEntry("imported", 1).containsEntry("updated", 0);

        ArgumentCaptor<List<Submission>> subs = submissionSaveCaptor();
        verify(submissionRepository).saveAll(subs.capture());
        assertThat(subs.getValue()).hasSize(1);
        assertThat(subs.getValue().get(0).getId()).isEqualTo(1001L);
        assertThat(subs.getValue().get(0).getUserId()).isEqualTo(USER);
        assertThat(subs.getValue().get(0).getLeetcodeNumber()).isEqualTo(1);
        assertThat(subs.getValue().get(0).getSubmissionTimestamp()).isEqualTo(1717200000L);

        verify(problemRepository).deleteByUserIdAndLeetcodeNumberIsNotNullAndLastSubmissionIdIsNull(USER);
    }

    @Test
    void authenticatedSync_skipsAlreadyKnownSubmissionIds() {
        Problem subAlreadyKnown = newIncoming(1, "Two Sum", LocalDate.of(2026, 6, 1), 1001L);
        Problem subNew          = newIncoming(2, "Add Two Numbers", LocalDate.of(2026, 6, 2), 2002L);

        when(problemRepository.findByUserId(USER)).thenReturn(List.of());
        when(submissionRepository.findSubmissionIdsByUserId(USER)).thenReturn(Set.of(1001L));
        when(leetCodeService.getAllSolvedProblems(anyString(), any()))
                .thenReturn(Flux.just(subAlreadyKnown, subNew));
        when(leetCodeService.getProfile(USER)).thenReturn(Mono.just(profile()));

        syncService.syncSolvedProblems(USER, 500, "session-token").block();

        ArgumentCaptor<List<Submission>> subs = submissionSaveCaptor();
        verify(submissionRepository).saveAll(subs.capture());
        assertThat(subs.getValue()).hasSize(1);
        assertThat(subs.getValue().get(0).getId()).isEqualTo(2002L);
    }

    @Test
    void sync_updatesExistingProblemInPlaceInsteadOfInserting() {
        Problem existing = new Problem();
        existing.setId(42L);
        existing.setLeetcodeNumber(1);
        existing.setTitle("Two Sum");
        existing.setUserId(USER);
        existing.setSolvedDate(LocalDate.of(2026, 1, 1));
        existing.setConfidence(3);
        existing.setNotes("keep me");

        Problem incoming = newIncoming(1, "Two Sum", LocalDate.of(2026, 6, 1), 9999L);
        incoming.setLastLang("java");

        when(problemRepository.findByUserId(USER)).thenReturn(List.of(existing));
        when(leetCodeService.getRecentSolvedProblems(USER, 500)).thenReturn(Flux.just(incoming));
        when(leetCodeService.getProfile(USER)).thenReturn(Mono.just(profile()));

        Map<String, Object> result = syncService.syncSolvedProblems(USER, 500, null).block();

        assertThat(result).containsEntry("imported", 0).containsEntry("updated", 1);

        ArgumentCaptor<List<Problem>> saved = problemSaveCaptor();
        verify(problemRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(1);
        Problem persisted = saved.getValue().get(0);
        // Same row (identity by id)
        assertThat(persisted.getId()).isEqualTo(42L);
        // User-edited fields preserved
        assertThat(persisted.getConfidence()).isEqualTo(3);
        assertThat(persisted.getNotes()).isEqualTo("keep me");
        // Sync fields updated
        assertThat(persisted.getLastSubmissionId()).isEqualTo(9999L);
        assertThat(persisted.getLastLang()).isEqualTo("java");
        assertThat(persisted.getSolvedDate()).isEqualTo(LocalDate.of(2026, 6, 1));
    }

    @Test
    void sync_keepsLatestSolvedDateWhenMultipleIncomingForSameNumber() {
        Problem older  = newIncoming(1, "Two Sum", LocalDate.of(2026, 3, 1), 1001L);
        Problem newer  = newIncoming(1, "Two Sum", LocalDate.of(2026, 6, 1), 2002L);
        Problem latest = newIncoming(1, "Two Sum", LocalDate.of(2026, 5, 1), 3003L);

        when(problemRepository.findByUserId(USER)).thenReturn(List.of());
        when(submissionRepository.findSubmissionIdsByUserId(USER)).thenReturn(Set.of());
        when(leetCodeService.getAllSolvedProblems(anyString(), any()))
                .thenReturn(Flux.just(older, newer, latest));
        when(leetCodeService.getProfile(USER)).thenReturn(Mono.just(profile()));

        syncService.syncSolvedProblems(USER, 500, "session-token").block();

        ArgumentCaptor<List<Problem>> saved = problemSaveCaptor();
        verify(problemRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(1);
        assertThat(saved.getValue().get(0).getSolvedDate()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(saved.getValue().get(0).getLastSubmissionId()).isEqualTo(2002L);

        // All 3 Submission rows still recorded individually
        ArgumentCaptor<List<Submission>> subs = submissionSaveCaptor();
        verify(submissionRepository).saveAll(subs.capture());
        assertThat(subs.getValue()).hasSize(3);
    }

    @Test
    void sync_returnsCountsEvenWhenProfileFetchFails() {
        Problem incoming = newIncoming(1, "Two Sum", LocalDate.of(2026, 6, 1), 1001L);
        when(problemRepository.findByUserId(USER)).thenReturn(List.of());
        when(leetCodeService.getRecentSolvedProblems(USER, 500)).thenReturn(Flux.just(incoming));
        when(leetCodeService.getProfile(USER)).thenReturn(Mono.error(new RuntimeException("boom")));

        Map<String, Object> result = syncService.syncSolvedProblems(USER, 500, null).block();

        assertThat(result).containsEntry("imported", 1).containsEntry("updated", 0);
        // Profile failed → no account save
        verify(accountRepository, never()).save(any());
    }

    @Test
    void sync_savesAccountAfterSuccessfulProfileFetch() {
        Problem incoming = newIncoming(1, "Two Sum", LocalDate.of(2026, 6, 1), 1001L);
        when(problemRepository.findByUserId(USER)).thenReturn(List.of());
        when(leetCodeService.getRecentSolvedProblems(USER, 500)).thenReturn(Flux.just(incoming));
        when(leetCodeService.getProfile(USER)).thenReturn(Mono.just(profile()));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        syncService.syncSolvedProblems(USER, 500, null).block();

        ArgumentCaptor<Account> account = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(account.capture());
        assertThat(account.getValue().getUserId()).isEqualTo(USER);
        assertThat(account.getValue().getTotalSolved()).isEqualTo(42);
        assertThat(account.getValue().getRanking()).isEqualTo(12345);
        assertThat(account.getValue().getLastSyncedAt()).isNotNull();
    }

    @Test
    void refreshLeaderboard_swallowsPerAccountErrorsAndReturnsRanked() {
        Account ok   = Account.builder().userId("alice").build();
        Account fail = Account.builder().userId("bob").build();
        Account ranked = Account.builder().userId("alice").totalSolved(10).ranking(100).build();

        when(accountRepository.findAll()).thenReturn(List.of(ok, fail));
        when(leetCodeService.getProfile("alice")).thenReturn(Mono.just(profile()));
        when(leetCodeService.getProfile("bob")).thenReturn(Mono.error(new RuntimeException("LC down")));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.findAllByOrderByRankingAsc()).thenReturn(List.of(ranked));

        List<Account> result = syncService.refreshLeaderboard().block();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getUserId()).isEqualTo("alice");
        // Only the successful one was persisted
        verify(accountRepository, never()).save(argThatUserIdIs("bob"));
    }

    @Test
    void fetchAndSaveProfile_returnsProfileAndPersistsAccount() {
        when(leetCodeService.getProfile(USER)).thenReturn(Mono.just(profile()));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        LeetCodeProfileDTO result = syncService.fetchAndSaveProfile(USER).block();

        assertThat(result).isNotNull();
        assertThat(result.getTotalSolved()).isEqualTo(42);
        verify(accountRepository).save(any(Account.class));
    }

    // ---------- helpers ----------

    private static Problem newIncoming(int number, String title, LocalDate solvedDate, Long submissionId) {
        Problem p = new Problem();
        p.setLeetcodeNumber(number);
        p.setTitle(title);
        p.setSolvedDate(solvedDate);
        p.setLastSubmissionId(submissionId);
        return p;
    }

    private static LeetCodeProfileDTO profile() {
        LeetCodeProfileDTO dto = new LeetCodeProfileDTO();
        dto.setUsername(USER);
        dto.setTotalSolved(42);
        dto.setEasySolved(20);
        dto.setMediumSolved(18);
        dto.setHardSolved(4);
        dto.setRanking(12345);
        dto.setAcceptanceRate(67.8);
        return dto;
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<Problem>> problemSaveCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<Submission>> submissionSaveCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    private static Account argThatUserIdIs(String userId) {
        return org.mockito.ArgumentMatchers.argThat(a -> a != null && userId.equals(a.getUserId()));
    }
}
