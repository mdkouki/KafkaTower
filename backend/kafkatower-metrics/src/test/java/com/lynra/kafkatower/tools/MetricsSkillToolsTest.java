package com.lynra.kafkatower.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsSkillToolsTest {

    private final MetricsSkillTools tools = new MetricsSkillTools();

    @ParameterizedTest
    @ValueSource(strings = {"cluster", "broker", "topic", "consumer-group", "principal", "failure-patterns"})
    void returnsPlaybookForEveryKnownScope(String scope) {
        assertThat(tools.getInvestigationPlaybook(scope)).isNotBlank().doesNotStartWith("Unknown");
    }

    @ParameterizedTest
    @ValueSource(strings = {"kminion", "kafka-core", "kafka-extended"})
    void returnsCatalogForEveryKnownCategory(String category) {
        assertThat(tools.getMetricsCatalog(category)).isNotBlank().doesNotStartWith("Unknown");
    }

    @Test
    void unknownScopeListsValidScopes() {
        assertThat(tools.getInvestigationPlaybook("nope"))
                .startsWith("Unknown scope 'nope'. Valid values: ")
                .contains("cluster", "broker", "topic", "consumer-group", "principal", "failure-patterns");
    }

    @Test
    void unknownCategoryListsValidCategories() {
        assertThat(tools.getMetricsCatalog("nope"))
                .startsWith("Unknown category 'nope'. Valid values: ")
                .contains("kminion", "kafka-core", "kafka-extended");
    }

    @Test
    void nullKeyIsReportedAsUnknown() {
        assertThat(tools.getInvestigationPlaybook(null)).startsWith("Unknown scope 'null'");
    }

    @Test
    void differentScopesReturnDifferentContent() {
        assertThat(tools.getInvestigationPlaybook("broker")).isNotEqualTo(tools.getInvestigationPlaybook("topic"));
    }
}
