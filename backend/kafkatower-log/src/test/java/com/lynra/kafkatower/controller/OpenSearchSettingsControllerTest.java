package com.lynra.kafkatower.controller;

import com.lynra.kafkatower.config.AdminSettingsStore;
import com.lynra.kafkatower.config.ApplicationRestarter;
import com.lynra.kafkatower.model.OpenSearchSettings;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenSearchSettingsControllerTest {

    private final AdminSettingsStore store = mock(AdminSettingsStore.class);
    private final ApplicationRestarter restarter = mock(ApplicationRestarter.class);
    private String probeResult;

    private final OpenSearchSettingsController controller =
            new OpenSearchSettingsController(store, restarter, new MockEnvironment()) {
                @Override
                String probe(OpenSearchSettings.Cluster c) {
                    return probeResult;
                }
            };

    private static OpenSearchSettingsController.OpenSearchRequest request(boolean enabled, String name, String url) {
        return new OpenSearchSettingsController.OpenSearchRequest(enabled, name, List.of(
                new OpenSearchSettingsController.ClusterInput(name, null, url, "", "", false, "logs-*")));
    }

    @Test
    void anUnreachableClusterBlocksTheSave() {
        probeResult = "Connection refused";
        when(store.read(OpenSearchSettings.SECTION, OpenSearchSettings.class)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.save(request(true, "prod", "https://os:9200")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("prod").hasMessageContaining("Connection refused");

        verify(store, never()).write(any(), any());
        verify(restarter, never()).restart();
    }

    @Test
    void verifiedSettingsAreSavedAndRestartTheContext() {
        probeResult = null;
        when(store.read(OpenSearchSettings.SECTION, OpenSearchSettings.class)).thenReturn(Optional.empty());

        var response = controller.save(request(true, "prod", "https://os:9200"));

        verify(store).write(any(), any(OpenSearchSettings.class));
        verify(restarter).restart();
        assertThat(response.getBody().restarting()).isTrue();
    }

    @Test
    void aDisabledConfigurationIsSavedWithoutProbing() {
        probeResult = "would fail";
        when(store.read(OpenSearchSettings.SECTION, OpenSearchSettings.class)).thenReturn(Optional.empty());

        controller.save(request(false, "prod", "https://os:9200"));

        verify(store).write(any(), any(OpenSearchSettings.class));
    }

    @Test
    void aBlankPasswordKeepsTheStoredOne() {
        probeResult = null;
        OpenSearchSettings stored = new OpenSearchSettings(true, "prod", List.of(
                new OpenSearchSettings.Cluster("prod", "https://os:9200", "", "secret", "logs-*")));
        when(store.read(OpenSearchSettings.SECTION, OpenSearchSettings.class)).thenReturn(Optional.of(stored));

        var response = controller.save(request(true, "prod", "https://os:9200"));

        verify(store, never()).write(any(), any());
        assertThat(response.getBody().restarting()).isFalse();
    }

    @Test
    void aDefaultClusterOutsideTheListIsRejected() {
        var bad = new OpenSearchSettingsController.OpenSearchRequest(true, "ghost", List.of(
                new OpenSearchSettingsController.ClusterInput("prod", null, "https://os:9200", "", "", false, "")));

        assertThatThrownBy(() -> controller.save(bad)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("ghost");
    }
}
