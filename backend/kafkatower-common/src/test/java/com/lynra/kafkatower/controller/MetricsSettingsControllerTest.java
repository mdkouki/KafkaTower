package com.lynra.kafkatower.controller;

import com.lynra.kafkatower.config.AdminSettingsStore;
import com.lynra.kafkatower.config.ApplicationRestarter;
import com.lynra.kafkatower.model.MetricsSettings;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetricsSettingsControllerTest {

    private final AdminSettingsStore store = mock(AdminSettingsStore.class);
    private final ApplicationRestarter restarter = mock(ApplicationRestarter.class);
    private String probeResult;

    private final MetricsSettingsController controller = new MetricsSettingsController(store, restarter,
            new MockEnvironment().withProperty("agent.metrics.url", "http://yml:8428")) {
        @Override
        String probe(MetricsSettings m) {
            return probeResult;
        }
    };

    private static MetricsSettingsController.MetricsRequest request(String url, String token) {
        return new MetricsSettingsController.MetricsRequest(url, token, false, "", "", false, "");
    }

    @Test
    void settingsThatFailTheCheckAreNotSavedNorApplied() {
        probeResult = "HTTP 401 from /api/v1/labels";

        assertThatThrownBy(() -> controller.save(request("http://vm:8428", "")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("http://vm:8428").hasMessageContaining("401");

        verify(store, never()).write(any(), any());
        verify(restarter, never()).restart();
    }

    @Test
    void verifiedSettingsAreSavedAndRestartTheContext() {
        probeResult = null;
        when(store.read(MetricsSettings.SECTION, MetricsSettings.class)).thenReturn(Optional.empty());

        var response = controller.save(request("http://vm:8428", "tok"));

        verify(store).write(eq(MetricsSettings.SECTION), any(MetricsSettings.class));
        verify(restarter).restart();
        assertThat(response.getBody().restarting()).isTrue();
    }

    @Test
    void aBlankTokenKeepsTheStoredOne() {
        probeResult = null;
        when(store.read(MetricsSettings.SECTION, MetricsSettings.class))
                .thenReturn(Optional.of(new MetricsSettings("http://vm:8428", "secret", "", "", "")));

        var response = controller.save(request("http://vm:8428", ""));

        verify(store, never()).write(any(), any());
        assertThat(response.getBody().restarting()).isFalse();
    }

    @Test
    void withoutSavedSettingsTheYmlValuesAreShown() {
        when(store.read(MetricsSettings.SECTION, MetricsSettings.class)).thenReturn(Optional.empty());

        assertThat(controller.get().url()).isEqualTo("http://yml:8428");
    }
}
