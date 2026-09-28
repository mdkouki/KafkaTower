package com.lynra.kafkatower.agents.specialists.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class VmMcpClientProviderTest {

    @Test
    void buildsClientAndToolFactoryForDefaultSseTransport() {
        VmMcpClientProvider provider = new VmMcpClientProvider("http://localhost:8083", "", "", "sse");

        assertThat(provider.client()).isNotNull();
        assertThat(provider.toolFactory()).isNotNull();
    }

    @Test
    void buildsClientForStreamableTransportWithTokenAndTenant() {
        VmMcpClientProvider provider = new VmMcpClientProvider("http://localhost:8083", "token-abc", "tenant-1", "streamable");

        assertThat(provider.client()).isNotNull();
    }

    @Test
    void fallsBackToSseForAnUnknownTransportWithoutThrowing() {
        assertThatCode(() -> new VmMcpClientProvider("http://localhost:8083", "", "", "carrier-pigeon"))
                .doesNotThrowAnyException();
    }
}
