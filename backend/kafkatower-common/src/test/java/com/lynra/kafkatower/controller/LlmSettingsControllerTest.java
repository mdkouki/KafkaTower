package com.lynra.kafkatower.controller;

import com.lynra.kafkatower.config.ApplicationRestarter;
import com.lynra.kafkatower.model.LlmSettings;
import com.lynra.kafkatower.repository.LlmSettingsRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmSettingsControllerTest {

    private final LlmSettingsRepository repo = mock(LlmSettingsRepository.class);
    private final ApplicationRestarter restarter = mock(ApplicationRestarter.class);
    private final LlmSettingsController controller = new LlmSettingsController(repo, restarter);

    private static LlmSettings settings(String key, String baseUrl, String model) {
        LlmSettings s = new LlmSettings();
        s.setApiKey(key);
        s.setBaseUrl(baseUrl);
        s.setModel(model);
        return s;
    }

    @Test
    void changedSettingsAreSavedAndRestartTheContext() {
        when(repo.findById(1L)).thenReturn(Optional.of(settings("old-key", "http://a", "gpt-4o")));

        var response = controller.save(new LlmSettingsController.LlmSettingsRequest("new-key", "http://a", "gpt-4o"));

        verify(repo).save(any(LlmSettings.class));
        verify(restarter).restart();
        assertThat(response.getBody().restarting()).isTrue();
    }

    @Test
    void firstTimeSettingsRestartTheContext() {
        when(repo.findById(1L)).thenReturn(Optional.empty());

        var response = controller.save(new LlmSettingsController.LlmSettingsRequest("key", "http://a", "gpt-4o"));

        verify(restarter).restart();
        assertThat(response.getBody().restarting()).isTrue();
    }

    @Test
    void unchangedSettingsDoNotRestart() {
        when(repo.findById(1L)).thenReturn(Optional.of(settings("key", "http://a", "gpt-4o")));

        var response = controller.save(new LlmSettingsController.LlmSettingsRequest("", " http://a ", "gpt-4o"));

        verify(restarter, never()).restart();
        assertThat(response.getBody().restarting()).isFalse();
    }
}
