package com.leetcode.dashboard.service;

import com.leetcode.dashboard.dto.LeetCodeProfileDTO;
import com.leetcode.dashboard.model.Account;
import com.leetcode.dashboard.model.Problem;
import com.leetcode.dashboard.model.Submission;
import com.leetcode.dashboard.repository.AccountRepository;
import com.leetcode.dashboard.repository.ProblemRepository;
import com.leetcode.dashboard.repository.SubmissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SyncService {

    private final LeetCodeService leetCodeService;
    private final ProblemRepository problemRepository;
    private final SubmissionRepository submissionRepository;
    private final AccountRepository accountRepository;

    public Mono<Map<String, Object>> syncSolvedProblems(
            String username, int limit, String sessionToken, String cfClearance
    ) {
        boolean authenticated = sessionToken != null && !sessionToken.isBlank();

        return loadExistingState(username, authenticated)
                .flatMap(state -> {
                    Flux<Problem> source = authenticated
                            ? leetCodeService.getAllSolvedProblems(sessionToken, cfClearance, state.knownSubIds())
                            : leetCodeService.getRecentSolvedProblems(username, limit);

                    return source
                            .map(p -> { p.setUserId(username); return p; })
                            .collectList()
                            .flatMap(incoming -> persist(username, incoming, state, authenticated));
                })
                .flatMap(counts -> finalizeWithProfile(username, counts));
    }

    public Mono<List<Account>> refreshLeaderboard() {
        return Mono.fromCallable(accountRepository::findAll)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable)
                .flatMap(a -> leetCodeService.getProfile(a.getUserId())
                        .flatMap(profile -> saveAccount(a.getUserId(), profile))
                        .onErrorResume(e -> Mono.empty()))
                .then(Mono.fromCallable(accountRepository::findAllByOrderByRankingAsc)
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    public Mono<LeetCodeProfileDTO> fetchAndSaveProfile(String username) {
        return leetCodeService.getProfile(username)
                .flatMap(profile -> saveAccount(username, profile).thenReturn(profile));
    }

    private Mono<SyncState> loadExistingState(String username, boolean authenticated) {
        return Mono.fromCallable(() -> {
            Map<Integer, Problem> existingByNum = problemRepository.findByUserId(username).stream()
                    .filter(p -> p.getLeetcodeNumber() != null)
                    .collect(Collectors.toMap(Problem::getLeetcodeNumber, p -> p, (a, b) -> a));
            Set<Long> knownSubIds = authenticated
                    ? submissionRepository.findSubmissionIdsByUserId(username)
                    : Set.of();
            return new SyncState(existingByNum, knownSubIds);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<int[]> persist(String username, List<Problem> incoming, SyncState state, boolean authenticated) {
        return Mono.fromCallable(() -> {
            int imported = 0, updated = 0;
            List<Problem> toSave = new ArrayList<>();
            List<Submission> submissions = new ArrayList<>();

            // Group by number: latest solved date wins for Problem metadata
            Map<Integer, Problem> latestByNum = new LinkedHashMap<>();
            for (Problem p : incoming) {
                if (p.getLeetcodeNumber() == null) continue;
                Problem cur = latestByNum.get(p.getLeetcodeNumber());
                if (cur == null || (p.getSolvedDate() != null &&
                        (cur.getSolvedDate() == null || p.getSolvedDate().isAfter(cur.getSolvedDate()))))
                    latestByNum.put(p.getLeetcodeNumber(), p);
            }

            for (Problem p : latestByNum.values()) {
                Problem ex = state.existingByNum().get(p.getLeetcodeNumber());
                LocalDate subDate = p.getSolvedDate() != null ? p.getSolvedDate() : LocalDate.now();
                if (ex == null) {
                    if (p.getFirstAttempted() == null) p.setFirstAttempted(subDate);
                    toSave.add(p);
                    imported++;
                } else {
                    ex.setLastSubmissionId(p.getLastSubmissionId());
                    ex.setLastLang(p.getLastLang());
                    ex.setLastRuntime(p.getLastRuntime());
                    ex.setLastMemory(p.getLastMemory());
                    if (p.getSolvedDate() != null &&
                            (ex.getSolvedDate() == null || !p.getSolvedDate().isBefore(ex.getSolvedDate())))
                        ex.setSolvedDate(p.getSolvedDate());
                    toSave.add(ex);
                    updated++;
                }
            }

            // One Submission row per individual submission event
            if (authenticated) {
                for (Problem p : incoming) {
                    if (p.getLeetcodeNumber() != null && p.getLastSubmissionId() != null
                            && !state.knownSubIds().contains(p.getLastSubmissionId())) {
                        Submission sub = new Submission();
                        sub.setId(p.getLastSubmissionId());
                        sub.setUserId(username);
                        sub.setLeetcodeNumber(p.getLeetcodeNumber());
                        sub.setSubmissionDate(p.getSolvedDate() != null ? p.getSolvedDate() : LocalDate.now());
                        sub.setSubmissionTimestamp(p.getSubmissionTimestamp());
                        submissions.add(sub);
                    }
                }
            }

            problemRepository.saveAll(toSave);
            submissionRepository.saveAll(submissions);
            if (authenticated)
                problemRepository.deleteByUserIdAndLeetcodeNumberIsNotNullAndLastSubmissionIdIsNull(username);

            return new int[]{imported, updated};
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Map<String, Object>> finalizeWithProfile(String username, int[] counts) {
        int imp = counts[0], upd = counts[1];
        Map<String, Object> result = Map.of("imported", imp, "updated", upd);
        return leetCodeService.getProfile(username)
                .flatMap(profile -> saveAccount(username, profile).thenReturn(profile))
                .thenReturn(result)
                .onErrorReturn(result);
    }

    private Mono<Account> saveAccount(String userId, LeetCodeProfileDTO profile) {
        return Mono.fromCallable(() -> {
            Account account = Account.builder()
                    .userId(userId)
                    .totalSolved(profile.getTotalSolved())
                    .easySolved(profile.getEasySolved())
                    .mediumSolved(profile.getMediumSolved())
                    .hardSolved(profile.getHardSolved())
                    .ranking(profile.getRanking())
                    .acceptanceRate(profile.getAcceptanceRate())
                    .lastSyncedAt(LocalDateTime.now())
                    .build();
            return accountRepository.save(account);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private record SyncState(Map<Integer, Problem> existingByNum, Set<Long> knownSubIds) {}
}
