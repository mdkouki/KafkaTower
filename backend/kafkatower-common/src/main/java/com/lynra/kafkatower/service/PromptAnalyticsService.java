package com.lynra.kafkatower.service;

import com.lynra.kafkatower.model.PromptAnalytics;
import com.lynra.kafkatower.repository.PromptAnalyticsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PromptAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(PromptAnalyticsService.class);

    private final PromptAnalyticsRepository repository;

    public PromptAnalyticsService(PromptAnalyticsRepository repository) {
        this.repository = repository;
    }

    // Fire-and-forget: runs on promptAnalyticsExecutor, off the request thread. userId is
    // captured by the caller on the request thread and passed in explicitly — SecurityContextHolder
    // is thread-local and wouldn't be populated on the async pool thread.
    @Async("promptAnalyticsExecutor")
    public void record(String theme, String keywords, String agentRouted, String userId) {
        try {
            repository.save(new PromptAnalytics(
                    Instant.now(),
                    userId,
                    theme != null ? theme : "unknown",
                    keywords,
                    agentRouted
            ));
        } catch (Exception e) {
            log.warn("Failed to record prompt analytics (theme={}, agent={})", theme, agentRouted, e);
        }
    }

    public AnalyticsSummary getSummary() {
        long total = repository.count();

        List<ThemeCount> themeDistribution = repository.countByTheme().stream()
                .map(row -> new ThemeCount(
                        (String) row[0],
                        (Long) row[1],
                        total > 0 ? Math.round((Long) row[1] * 1000.0 / total) / 10.0 : 0.0
                ))
                .toList();

        List<AgentCount> agentDistribution = repository.countByAgent().stream()
                .map(row -> new AgentCount((String) row[0], (Long) row[1]))
                .toList();

        List<KeywordCount> topKeywords = computeTopKeywords(20);

        List<DailyCount> dailyVolume = repository.countByDay().stream()
                .map(row -> new DailyCount(row[0].toString(), ((Number) row[1]).longValue()))
                .toList();

        List<RecentEntry> recentActivity = repository.findTop20ByOrderByTimestampDesc().stream()
                .map(p -> new RecentEntry(
                        p.getTimestamp().toString(),
                        p.getUserId(),
                        p.getTheme(),
                        p.getAgentRouted()
                ))
                .toList();

        List<UserCount> topUsers = repository.countByUser(PageRequest.of(0, 10)).stream()
                .map(row -> new UserCount((String) row[0], (Long) row[1]))
                .toList();

        return new AnalyticsSummary(total, themeDistribution, agentDistribution,
                topKeywords, dailyVolume, recentActivity, topUsers);
    }

    private List<KeywordCount> computeTopKeywords(int limit) {
        Map<String, Long> freq = new HashMap<>();
        repository.findAll().forEach(p -> {
            if (p.getKeywords() != null && !p.getKeywords().isBlank()) {
                Arrays.stream(p.getKeywords().split(","))
                        .map(String::trim)
                        .filter(k -> !k.isEmpty())
                        .forEach(k -> freq.merge(k, 1L, Long::sum));
            }
        });
        return freq.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit)
                .map(e -> new KeywordCount(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }

    // ── Response records ─────────────────────────────────────────────────────

    public record AnalyticsSummary(
            long totalPrompts,
            List<ThemeCount> themeDistribution,
            List<AgentCount> agentDistribution,
            List<KeywordCount> topKeywords,
            List<DailyCount> dailyVolume,
            List<RecentEntry> recentActivity,
            List<UserCount> topUsers
    ) {}

    public record ThemeCount(String theme, long count, double percentage) {}
    public record AgentCount(String agent, long count) {}
    public record KeywordCount(String keyword, long count) {}
    public record DailyCount(String date, long count) {}
    public record UserCount(String userId, long count) {}

    public record RecentEntry(String timestamp, String userId, String theme, String agentRouted) {}
}
