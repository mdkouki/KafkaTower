package com.lynra.kafkatower.agents.specialists.log;

import com.embabel.agent.api.annotation.SpecialReturnException;
import com.embabel.agent.api.common.Ai;
import com.lynra.kafkatower.agents.specialists.config.OpenSearchClientProvider;
import com.lynra.kafkatower.agents.specialists.config.OpenSearchClientRegistry;
import com.lynra.kafkatower.tools.OpenSearchQueryTools;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers {@link KafkaLogInspector#resolveLogCluster} in isolation — the part of the Phase 1
 * split (EMBABEL_GOAP_ALIGNMENT_PLAN.md) that doesn't touch {@link Ai}, so it needs no LLM
 * mocking. {@code searchLogs}'s LLM call is a verbatim move of the pre-existing, previously
 * untested inline call and is exercised only via manual/trace verification, matching this
 * codebase's existing pattern of not mocking the Ai fluent chain (see KafkaInspectorTest).
 */
class KafkaLogInspectorTest {

    private static KafkaLogInspector inspector(OpenSearchClientRegistry registry) {
        return new KafkaLogInspector(registry, mock(OpenSearchQueryTools.class), 15000L);
    }

    @Test
    void resolveLogClusterShortCircuitsWithLogAnswerWhenRegistryIsEmpty() {
        OpenSearchClientRegistry registry = mock(OpenSearchClientRegistry.class);
        when(registry.isEmpty()).thenReturn(true);

        SpecialReturnException thrown = catchSpecialReturn(() ->
                inspector(registry).resolveLogCluster(new LogQuestion("any errors in svc-x?"), null));

        assertThat(thrown.getType()).isEqualTo(LogAnswer.class);
        LogAnswer answer = (LogAnswer) thrown.handle(null);
        assertThat(answer.text()).contains("no OpenSearch clusters are configured");
    }

    @Test
    void resolveLogClusterShortCircuitsWithLogAnswerWhenClusterUnavailable() {
        OpenSearchClientRegistry registry = mock(OpenSearchClientRegistry.class);
        OpenSearchClientProvider provider = mock(OpenSearchClientProvider.class);
        when(registry.isEmpty()).thenReturn(false);
        when(registry.clusterNames()).thenReturn(new TreeSet<>(java.util.Set.of("prod")));
        when(registry.forCluster("prod")).thenReturn(Optional.of(provider));
        when(provider.clusterName()).thenReturn("prod");
        when(provider.isAvailable()).thenReturn(false);

        SpecialReturnException thrown = catchSpecialReturn(() ->
                inspector(registry).resolveLogCluster(new LogQuestion("any errors in svc-x?"), null));

        assertThat(thrown.getType()).isEqualTo(LogAnswer.class);
        LogAnswer answer = (LogAnswer) thrown.handle(null);
        assertThat(answer.text()).contains("prod").contains("currently unreachable");
    }

    @Test
    void resolveLogClusterReturnsResolvedClusterWithoutCallingAiWhenOnlyOneClusterConfigured() {
        OpenSearchClientRegistry registry = mock(OpenSearchClientRegistry.class);
        OpenSearchClientProvider provider = mock(OpenSearchClientProvider.class);
        when(registry.isEmpty()).thenReturn(false);
        when(registry.clusterNames()).thenReturn(new TreeSet<>(java.util.Set.of("prod")));
        when(registry.forCluster("prod")).thenReturn(Optional.of(provider));
        when(provider.clusterName()).thenReturn("prod");
        when(provider.isAvailable()).thenReturn(true);

        // ai is deliberately null: the single-cluster shortcut must not touch it.
        ResolvedCluster resolved = inspector(registry).resolveLogCluster(new LogQuestion("any errors?"), null);

        assertThat(resolved.provider()).isSameAs(provider);
        assertThat(resolved.note()).isNull();
    }

    private static SpecialReturnException catchSpecialReturn(Runnable action) {
        try {
            action.run();
        } catch (SpecialReturnException e) {
            return e;
        }
        throw new AssertionError("expected a SpecialReturnException to be thrown");
    }
}
