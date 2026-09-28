package com.lynra.kafkatower.agents.specialists.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class OpenSearchClientProviderTest {

    @Test
    void exposesTheClusterNameItWasConstructedWith() {
        OpenSearchClientProvider provider =
                new OpenSearchClientProvider("nonprod", "http://localhost:9200", "admin", "secret", "app-logs-*");

        assertThat(provider.clusterName()).isEqualTo("nonprod");
    }

    @Test
    void exposesTheConfiguredDefaultIndex() {
        OpenSearchClientProvider provider =
                new OpenSearchClientProvider("nonprod", "http://localhost:9200", "admin", "secret", "app-logs-*");

        assertThat(provider.defaultIndex()).isEqualTo("app-logs-*");
    }

    @Test
    void treatsNullUsernameAsNoAuth() {
        assertThatCode(() -> new OpenSearchClientProvider("nonprod", "http://localhost:9200", null, null, "app-logs-*"))
                .doesNotThrowAnyException();
    }

    @Test
    void treatsBlankUsernameAsNoAuth() {
        assertThatCode(() -> new OpenSearchClientProvider("nonprod", "http://localhost:9200", "", "", "app-logs-*"))
                .doesNotThrowAnyException();
    }

    @Test
    void isAvailableReturnsFalseWhenTheServerIsUnreachable() {
        OpenSearchClientProvider provider =
                new OpenSearchClientProvider("nonprod", "http://localhost:1", "admin", "secret", "app-logs-*");

        assertThat(provider.isAvailable()).isFalse();
    }

    @Test
    void trimsATrailingSlashFromTheConfiguredUrl() {
        OpenSearchClientProvider provider =
                new OpenSearchClientProvider("nonprod", "http://localhost:9200/", "admin", "secret", "app-logs-*");

        assertThatCode(provider::isAvailable).doesNotThrowAnyException();
    }
}
