package com.leetcode.dashboard.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leetcode.dashboard.dto.LeetCodeProfileDTO;
import com.leetcode.dashboard.model.Difficulty;
import com.leetcode.dashboard.model.Problem;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LeetCodeService {

    @Value("${leetcode.api.base-url}")
    private String leetcodeBaseUrl;

    @Value("${leetcode.api.problems-base-url}")
    private String leetcodeProblemsBaseUrl;

    // Used for authenticated calls — browser-like headers so LeetCode doesn't block pagination
    private WebClient webClient;
    private WebClient browserClient;

    private record TufEntry(String topic, String pattern) {}
    private final Map<String, TufEntry> tufMapping = new HashMap<>();

    @PostConstruct
    void init() {
        webClient = WebClient.builder()
                .baseUrl(leetcodeBaseUrl)
                .defaultHeader("Content-Type", "application/json")
                .build();
        browserClient = WebClient.builder()
                .baseUrl(leetcodeBaseUrl)
                .defaultHeader(HttpHeaders.USER_AGENT, "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .defaultHeader(HttpHeaders.ACCEPT, "application/json, text/javascript, */*; q=0.01")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .defaultHeader("X-Requested-With", "XMLHttpRequest")
                .build();
        loadTufMapping();
    }

    private void loadTufMapping() {
        try (InputStream is = getClass().getResourceAsStream("/tuf-mapping.json")) {
            if (is == null) return;
            List<Map<String, Object>> entries = new ObjectMapper().readValue(is, new TypeReference<>() {});
            for (Map<String, Object> entry : entries) {
                String slug = (String) entry.get("slug");
                String topic = (String) entry.get("topic");
                String pattern = (String) entry.get("pattern");
                if (slug != null) tufMapping.put(slug, new TufEntry(topic, pattern));
            }
        } catch (Exception ignored) {}
    }

    private static final String PROFILE_QUERY = """
            {
              "query": "query getUserProfile($username: String!) { matchedUser(username: $username) { submitStats { acSubmissionNum { difficulty count submissions } totalSubmissionNum { difficulty count submissions } } profile { ranking } tagProblemCounts { advanced { tagName problemsSolved } intermediate { tagName problemsSolved } fundamental { tagName problemsSolved } } } }",
              "variables": { "username": "%s" }
            }
            """;

    public Mono<LeetCodeProfileDTO> getProfile(String username) {
        return webClient.post()
                .uri("/graphql")
                .bodyValue(PROFILE_QUERY.formatted(username))
                .retrieve()
                .bodyToMono(Map.class)
                .map(response -> parseProfile(username, response));
    }

    private static final String RECENT_SUBMISSIONS_QUERY = """
            {
              "query": "query recentAcSubmissions($username: String!, $limit: Int!) { recentAcSubmissionList(username: $username, limit: $limit) { id title titleSlug timestamp } }",
              "variables": { "username": "%s", "limit": %d }
            }
            """;

    private static final String QUESTION_QUERY = """
            {
              "query": "query questionData($titleSlug: String!) { question(titleSlug: $titleSlug) { questionFrontendId title difficulty topicTags { name slug } } }",
              "variables": { "titleSlug": "%s" }
            }
            """;

    public Flux<Problem> getRecentSolvedProblems(String username, int limit) {
        return browserClient.post()
                .uri("/graphql")
                .bodyValue(RECENT_SUBMISSIONS_QUERY.formatted(username, limit))
                .retrieve()
                .bodyToMono(Map.class)
                .flatMapMany(response -> {
                    List<Map<String, Object>> submissions = extractRecentSubmissions(response);
                    return Flux.fromIterable(submissions)
                            .flatMap(sub -> enrichWithQuestionData(sub), 3);
                });
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractRecentSubmissions(Map<String, Object> response) {
        try {
            Map<String, Object> data = (Map<String, Object>) response.get("data");
            return (List<Map<String, Object>>) data.get("recentAcSubmissionList");
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Mono<Problem> enrichWithQuestionData(Map<String, Object> submission) {
        String titleSlug = (String) submission.get("titleSlug");
        String title     = (String) submission.get("title");
        long   timestamp = Long.parseLong(submission.get("timestamp").toString());
        LocalDate solvedDate = Instant.ofEpochSecond(timestamp).atZone(ZoneId.systemDefault()).toLocalDate();
        return enrichProblem(titleSlug, title, solvedDate, null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private Mono<Problem> enrichProblem(String titleSlug, String title, LocalDate solvedDate,
                                        Long submissionId, String lang, String runtime, String memory) {
        TufEntry tuf = tufMapping.get(titleSlug);

        return browserClient.post()
                .uri("/graphql")
                .bodyValue(QUESTION_QUERY.formatted(titleSlug))
                .retrieve()
                .bodyToMono(Map.class)
                .map(qResponse -> {
                    Problem problem = new Problem();
                    problem.setTitle(title);
                    problem.setSolvedDate(solvedDate);
                    problem.setLeetcodeUrl(leetcodeBaseUrl + "/problems/" + titleSlug);
                    try {
                        Map<String, Object> data     = (Map<String, Object>) qResponse.get("data");
                        Map<String, Object> question = (Map<String, Object>) data.get("question");
                        String frontendId = (String) question.get("questionFrontendId");
                        if (frontendId != null) problem.setLeetcodeNumber(Integer.parseInt(frontendId));
                        String diff = (String) question.get("difficulty");
                        if (diff != null) problem.setDifficulty(Difficulty.valueOf(diff.toUpperCase()));
                        // topic always from LeetCode tags
                        List<Map<String, Object>> tags = (List<Map<String, Object>>) question.get("topicTags");
                        if (tags != null && !tags.isEmpty()) {
                            problem.setTopic((String) tags.get(0).get("name"));
                        }
                        // pattern only from TUF mapping
                        if (tuf != null) problem.setPattern(tuf.pattern());
                    } catch (Exception ignored) {}
                    problem.setLastSubmissionId(submissionId);
                    problem.setLastLang(lang);
                    problem.setLastRuntime(runtime);
                    problem.setLastMemory(memory);
                    return problem;
                })
                .onErrorReturn(buildMinimalProblem(title, solvedDate, titleSlug, submissionId, lang, runtime, memory));
    }

    private Mono<String> fetchCsrf(String sessionToken, String cfClearance) {
        String cookie = "LEETCODE_SESSION=" + sessionToken
                + (cfClearance != null && !cfClearance.isBlank() ? "; cf_clearance=" + cfClearance : "");
        return browserClient.get()
                .uri("/")
                .header(HttpHeaders.COOKIE, cookie)
                .exchangeToMono(resp -> {
                    ResponseCookie c = resp.cookies().getFirst("csrftoken");
                    return Mono.just(c != null ? c.getValue() : "");
                });
    }

    private static final String SUBMISSION_LIST_QUERY = """
            {"operationName":"submissionList","query":"query submissionList($offset: Int!, $limit: Int!, $questionSlug: String) { submissionList(offset: $offset, limit: $limit, questionSlug: $questionSlug) { lastKey hasNext submissions { id statusDisplay lang runtime timestamp url title memory titleSlug } } }","variables":{"offset":%d,"limit":20,"questionSlug":"%s"}}
            """;

    public Flux<Problem> getAllSolvedProblems(String sessionToken, String cfClearance, java.util.Set<Long> knownIds) {
        return fetchCsrf(sessionToken, cfClearance).flatMapMany(csrf -> {
            String cookie = "LEETCODE_SESSION=" + sessionToken + "; csrftoken=" + csrf
                    + (cfClearance != null && !cfClearance.isBlank() ? "; cf_clearance=" + cfClearance : "");
            return fetchSubmissionsPage(cookie, csrf, 0)
                    .filter(sub -> "Accepted".equals(sub.get("statusDisplay")))
                    .collectList()
                    .flatMapMany(allSubs -> {
                        // Enrich once per unique slug (most recent submission per slug for metadata)
                        Map<String, Map<String, Object>> firstBySlug = new java.util.LinkedHashMap<>();
                        for (Map<String, Object> sub : allSubs) {
                            String slug = (String) sub.get("titleSlug");
                            if (slug != null) firstBySlug.putIfAbsent(slug, sub);
                        }
                        return Flux.fromIterable(firstBySlug.entrySet())
                                .flatMap(e -> {
                                    Map<String, Object> rep = e.getValue();
                                    long ts = Long.parseLong(rep.get("timestamp").toString());
                                    return enrichProblem(
                                            e.getKey(), (String) rep.get("title"),
                                            Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault()).toLocalDate(),
                                            rep.get("id") != null ? Long.parseLong(rep.get("id").toString()) : null,
                                            (String) rep.get("lang"), (String) rep.get("runtime"), (String) rep.get("memory")
                                    ).map(p -> Map.entry(e.getKey(), p));
                                }, 8)
                                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                                .flatMapMany(enrichedBySlug -> {
                                    // Fan out: one Problem per raw submission using cached enrichment
                                    List<Problem> result = new java.util.ArrayList<>();
                                    for (Map<String, Object> sub : allSubs) {
                                        String slug = (String) sub.get("titleSlug");
                                        Problem template = enrichedBySlug.get(slug);
                                        if (template == null) continue;
                                        long ts = Long.parseLong(sub.get("timestamp").toString());
                                        Problem p = new Problem();
                                        p.setTitle(template.getTitle());
                                        p.setLeetcodeNumber(template.getLeetcodeNumber());
                                        p.setDifficulty(template.getDifficulty());
                                        p.setTopic(template.getTopic());
                                        p.setPattern(template.getPattern());
                                        p.setLeetcodeUrl(template.getLeetcodeUrl());
                                        p.setSolvedDate(Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault()).toLocalDate());
                                        p.setSubmissionTimestamp(ts);
                                        p.setLastSubmissionId(sub.get("id") != null ? Long.parseLong(sub.get("id").toString()) : null);
                                        p.setLastLang((String) sub.get("lang"));
                                        p.setLastRuntime((String) sub.get("runtime"));
                                        p.setLastMemory((String) sub.get("memory"));
                                        result.add(p);
                                    }
                                    return Flux.fromIterable(result);
                                });
                    });
        });
    }

    @SuppressWarnings("unchecked")
    private Flux<Map<String, Object>> fetchSubmissionsPage(String cookie, String csrf, int offset) {
        return browserClient.post()
                .uri("/graphql/")
                .header(HttpHeaders.COOKIE, cookie)
                .header("x-csrftoken", csrf)
                .header("Referer", leetcodeBaseUrl + "/submissions/")
                .bodyValue(SUBMISSION_LIST_QUERY.formatted(offset, ""))
                .retrieve()
                .bodyToMono(Map.class)
                .flatMapMany(body -> {
                    List<Map<String, Object>> subs = extractSubmissions(body);
                    Map<String, Object> list = extractSubmissionList(body);
                    Flux<Map<String, Object>> current = Flux.fromIterable(subs);
                    if (Boolean.TRUE.equals(list != null ? list.get("hasNext") : false)) {
                        return current.concatWith(fetchSubmissionsPage(cookie, csrf, offset + 20));
                    }
                    return current;
                });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractSubmissionList(Map<String, Object> body) {
        try {
            return (Map<String, Object>) ((Map<String, Object>) body.get("data")).get("submissionList");
        } catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractSubmissions(Map<String, Object> body) {
        try {
            Map<String, Object> list = extractSubmissionList(body);
            List<Map<String, Object>> subs = (List<Map<String, Object>>) list.get("submissions");
            return subs != null ? subs : List.of();
        } catch (Exception e) { return List.of(); }
    }

    private Problem buildMinimalProblem(String title, LocalDate solvedDate, String titleSlug,
                                        Long submissionId, String lang, String runtime, String memory) {
        Problem p = new Problem();
        p.setTitle(title);
        p.setSolvedDate(solvedDate);
        p.setLeetcodeUrl(leetcodeProblemsBaseUrl + titleSlug);
        p.setLastSubmissionId(submissionId);
        p.setLastLang(lang);
        p.setLastRuntime(runtime);
        p.setLastMemory(memory);
        return p;
    }

    @SuppressWarnings("unchecked")
    private LeetCodeProfileDTO parseProfile(String username, Map<String, Object> response) {
        LeetCodeProfileDTO dto = new LeetCodeProfileDTO();
        dto.setUsername(username);
        try {
            Map<String, Object> data = (Map<String, Object>) response.get("data");
            Map<String, Object> matchedUser = (Map<String, Object>) data.get("matchedUser");
            Map<String, Object> profile = (Map<String, Object>) matchedUser.get("profile");
            Map<String, Object> submitStats = (Map<String, Object>) matchedUser.get("submitStats");
            java.util.List<Map<String, Object>> acSubmissions =
                    (java.util.List<Map<String, Object>>) submitStats.get("acSubmissionNum");

            dto.setRanking(((Number) profile.get("ranking")).intValue());

            int acSubmits = 0, totalSubmit = 0;
            for (Map<String, Object> entry : acSubmissions) {
                String difficulty = (String) entry.get("difficulty");
                int count = ((Number) entry.get("count")).intValue();
                switch (difficulty) {
                    case "All"    -> { dto.setTotalSolved(count); acSubmits = ((Number) entry.get("submissions")).intValue(); }
                    case "Easy"   -> dto.setEasySolved(count);
                    case "Medium" -> dto.setMediumSolved(count);
                    case "Hard"   -> dto.setHardSolved(count);
                }
            }
            List<Map<String, Object>> totalSubmissions =
                    (List<Map<String, Object>>) submitStats.get("totalSubmissionNum");
            if (totalSubmissions != null) {
                for (Map<String, Object> entry : totalSubmissions) {
                    if ("All".equals(entry.get("difficulty")))
                        totalSubmit = ((Number) entry.get("submissions")).intValue();
                }
            }
            if (totalSubmit > 0)
                dto.setAcceptanceRate(Math.round(acSubmits * 1000.0 / totalSubmit) / 10.0);

            Map<String, Object> tagCounts = (Map<String, Object>) matchedUser.get("tagProblemCounts");
            if (tagCounts != null) {
                Map<String, Integer> byTopic = new HashMap<>();
                for (String tier : List.of("advanced", "intermediate", "fundamental")) {
                    List<Map<String, Object>> tags = (List<Map<String, Object>>) tagCounts.get(tier);
                    if (tags != null) {
                        for (Map<String, Object> tag : tags) {
                            String tagName = (String) tag.get("tagName");
                            int solved = ((Number) tag.get("problemsSolved")).intValue();
                            if (solved > 0) byTopic.put(tagName, solved);
                        }
                    }
                }
                dto.setByTopic(byTopic);
            }
        } catch (Exception e) {
            // Return partial DTO if LeetCode response shape changes
        }
        return dto;
    }
}
