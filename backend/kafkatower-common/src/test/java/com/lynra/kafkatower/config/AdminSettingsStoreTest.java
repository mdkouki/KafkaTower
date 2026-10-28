package com.lynra.kafkatower.config;

import com.lynra.kafkatower.model.AdminSetting;
import com.lynra.kafkatower.model.MetricsSettings;
import com.lynra.kafkatower.repository.AdminSettingRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminSettingsStoreTest {

    private final AdminSettingRepository repo = mock(AdminSettingRepository.class);
    private final AdminSettingsStore store = new AdminSettingsStore(repo);

    @Test
    void aStoredPayloadIsParsed() {
        when(repo.findById("metrics")).thenReturn(Optional.of(new AdminSetting("metrics",
                "{\"url\":\"http://vm:8428\",\"token\":\"\",\"username\":\"\",\"password\":\"\",\"tenant\":\"\"}")));

        assertThat(store.read("metrics", MetricsSettings.class)).map(MetricsSettings::url).contains("http://vm:8428");
    }

    @Test
    void anUnreadablePayloadReadsAsNotSavedInsteadOfFailing() {
        when(repo.findById("metrics")).thenReturn(Optional.of(new AdminSetting("metrics", "16455")));

        assertThat(store.read("metrics", MetricsSettings.class)).isEmpty();
    }
}
