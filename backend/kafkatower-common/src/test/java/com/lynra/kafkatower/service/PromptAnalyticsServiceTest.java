package com.lynra.kafkatower.service;

import com.lynra.kafkatower.model.PromptAnalytics;
import com.lynra.kafkatower.repository.PromptAnalyticsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromptAnalyticsServiceTest {

    @Mock
    private PromptAnalyticsRepository repository;

    private PromptAnalyticsService service;

    private void init() {
        service = new PromptAnalyticsService(repository);
    }

    // record() is @Async in production; outside a Spring context (plain `new`, as here) the
    // annotation has no effect, so these calls run synchronously and can be asserted directly.

    @Test
    void recordPersistsGivenUserIdAndFields() {
        init();

        service.record("consumer_lag", "lag,group", "KafkaInvestigator", "alice");

        ArgumentCaptor<PromptAnalytics> captor = ArgumentCaptor.forClass(PromptAnalytics.class);
        verify(repository).save(captor.capture());
        PromptAnalytics saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo("alice");
        assertThat(saved.getTheme()).isEqualTo("consumer_lag");
        assertThat(saved.getKeywords()).isEqualTo("lag,group");
        assertThat(saved.getAgentRouted()).isEqualTo("KafkaInvestigator");
    }

    @Test
    void recordDefaultsThemeToUnknownWhenNull() {
        init();

        service.record(null, "kw", "Agent", "alice");

        ArgumentCaptor<PromptAnalytics> captor = ArgumentCaptor.forClass(PromptAnalytics.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getTheme()).isEqualTo("unknown");
    }

    @Test
    void recordSwallowsRepositoryFailuresSinceItIsFireAndForget() {
        init();
        when(repository.save(any())).thenThrow(new RuntimeException("db down"));

        service.record("consumer_lag", "lag", "Agent", "alice");

        verify(repository).save(any());
    }

    @Test
    void getSummaryComputesThemePercentagesAndTopKeywords() {
        init();
        when(repository.count()).thenReturn(4L);
        when(repository.countByTheme()).thenReturn(List.of(
                new Object[]{"consumer_lag", 3L},
                new Object[]{"topic_ownership", 1L}
        ));
        when(repository.countByAgent()).thenReturn(List.<Object[]>of(new Object[]{"KafkaInvestigator", 3L}));
        when(repository.countByDay()).thenReturn(List.of());
        when(repository.findTop20ByOrderByTimestampDesc()).thenReturn(List.of());
        when(repository.countByUser(any(PageRequest.class))).thenReturn(List.of());
        when(repository.findAll()).thenReturn(List.of(
                new PromptAnalytics(Instant.now(), "alice", "consumer_lag", "lag, group", "KafkaInvestigator"),
                new PromptAnalytics(Instant.now(), "bob", "consumer_lag", "lag, spike", "KafkaInvestigator")
        ));

        PromptAnalyticsService.AnalyticsSummary summary = service.getSummary();

        assertThat(summary.totalPrompts()).isEqualTo(4L);
        assertThat(summary.themeDistribution()).extracting("theme", "count", "percentage")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("consumer_lag", 3L, 75.0),
                        org.assertj.core.groups.Tuple.tuple("topic_ownership", 1L, 25.0));
        assertThat(summary.topKeywords()).extracting("keyword", "count")
                .contains(org.assertj.core.groups.Tuple.tuple("lag", 2L));
    }

    @Test
    void getSummaryHandlesEmptyRepositoryWithoutDivisionByZero() {
        init();
        when(repository.count()).thenReturn(0L);
        when(repository.countByTheme()).thenReturn(List.of());
        when(repository.countByAgent()).thenReturn(List.of());
        when(repository.countByDay()).thenReturn(List.of());
        when(repository.findTop20ByOrderByTimestampDesc()).thenReturn(List.of());
        when(repository.countByUser(any(PageRequest.class))).thenReturn(List.of());
        when(repository.findAll()).thenReturn(List.of());

        PromptAnalyticsService.AnalyticsSummary summary = service.getSummary();

        assertThat(summary.totalPrompts()).isZero();
        assertThat(summary.themeDistribution()).isEmpty();
        assertThat(summary.topKeywords()).isEmpty();
    }
}
