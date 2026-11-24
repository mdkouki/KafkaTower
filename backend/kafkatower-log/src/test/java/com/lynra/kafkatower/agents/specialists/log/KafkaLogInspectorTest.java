package com.lynra.kafkatower.agents.specialists.log;

import com.embabel.agent.api.annotation.SpecialReturnException;
import com.embabel.agent.api.common.Ai;
import com.lynra.kafkatower.agents.specialists.config.OpenSearchClientProvider;
import com.lynra.kafkatower.agents.specialists.config.OpenSearchClientRegistry;
import com.lynra.kafkatower.tools.BulkCollectionTools;
import com.lynra.kafkatower.tools.OpenSearchQueryTools;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
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
        return new KafkaLogInspector(registry, mock(OpenSearchQueryTools.class), mock(BulkCollectionTools.class), 15000L);
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

    @Test
    void resolveLogClusterFallsBackToFirstClusterWithNoteWhenSelectedClusterIsUnknown() {
        OpenSearchClientRegistry registry = mock(OpenSearchClientRegistry.class);
        OpenSearchClientProvider fallback = mock(OpenSearchClientProvider.class);
        Ai ai = mock(Ai.class, RETURNS_DEEP_STUBS);
        when(registry.isEmpty()).thenReturn(false);
        when(registry.clusterNames()).thenReturn(new TreeSet<>(java.util.Set.of("alpha", "beta")));
        when(registry.defaultCluster()).thenReturn(Optional.empty());
        when(registry.forCluster("ghost")).thenReturn(Optional.empty());
        when(registry.forCluster("alpha")).thenReturn(Optional.of(fallback));
        when(fallback.clusterName()).thenReturn("alpha");
        when(fallback.isAvailable()).thenReturn(true);
        when(ai.withAutoLlm().withSystemPrompt(anyString()).createObject(anyString(), eq(ClusterSelection.class)))
                .thenReturn(new ClusterSelection("ghost"));

        ResolvedCluster resolved = inspector(registry).resolveLogCluster(new LogQuestion("errors?"), ai);

        assertThat(resolved.provider()).isSameAs(fallback);
        assertThat(resolved.note()).contains("ghost").contains("alpha");
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
