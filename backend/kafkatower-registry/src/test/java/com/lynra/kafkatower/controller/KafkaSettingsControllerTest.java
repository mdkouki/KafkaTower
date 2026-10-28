package com.lynra.kafkatower.controller;

import com.lynra.kafkatower.config.AdminSettingsStore;
import com.lynra.kafkatower.config.ApplicationRestarter;
import com.lynra.kafkatower.content.TopicSchemaRepository;
import com.lynra.kafkatower.kafka.KafkaClusterVerifier;
import com.lynra.kafkatower.model.KafkaSettings;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaSettingsControllerTest {

    private final AdminSettingsStore store = mock(AdminSettingsStore.class);
    private final ApplicationRestarter restarter = mock(ApplicationRestarter.class);
    private final KafkaClusterVerifier verifier = mock(KafkaClusterVerifier.class);
    private final TopicSchemaRepository schemas = mock(TopicSchemaRepository.class);
    private final KafkaSettingsController controller =
            new KafkaSettingsController(store, restarter, verifier, schemas);

    private static KafkaSettingsController.ClusterInput plain(String name, String previous, String boot) {
        return new KafkaSettingsController.ClusterInput(name, previous, boot, "NONE", null, null);
    }

    private static KafkaSettingsController.ClusterInput sasl(String name, String previous, String password) {
        return new KafkaSettingsController.ClusterInput(name, previous, "b:9092", "SASL", null,
                new KafkaSettingsController.SaslInput("SCRAM-SHA-256", "u", password, false));
    }

    private void saved(KafkaSettings settings) {
        when(store.read(KafkaSettings.SECTION, KafkaSettings.class)).thenReturn(Optional.of(settings));
    }

    @Test
    void aClusterThatFailsVerificationBlocksTheSave() {
        when(store.read(KafkaSettings.SECTION, KafkaSettings.class)).thenReturn(Optional.empty());
        when(verifier.verify(any())).thenThrow(new IllegalArgumentException("Authentication failed"));

        assertThatThrownBy(() -> controller.save(new KafkaSettingsController.KafkaRequest(
                List.of(plain("prod", null, "b:9092")), null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("prod").hasMessageContaining("Authentication failed");

        verify(store, never()).write(any(), any());
        verify(restarter, never()).restart();
    }

    @Test
    void verifiedClustersAreSavedAndRestartTheContext() {
        when(store.read(KafkaSettings.SECTION, KafkaSettings.class)).thenReturn(Optional.empty());
        when(verifier.verify(any())).thenReturn(3);

        var response = controller.save(new KafkaSettingsController.KafkaRequest(
                List.of(plain("prod", null, "b:9092")), null));

        verify(store).write(any(), any(KafkaSettings.class));
        verify(restarter).restart();
        assertThat(response.getBody().restarting()).isTrue();
        assertThat(response.getBody().note()).contains("3 broker");
    }

    @Test
    void removingAClusterDeletesItsData() {
        saved(new KafkaSettings(List.of(
                new KafkaSettings.Cluster("prod", "b:9092", "NONE", null, null),
                new KafkaSettings.Cluster("old", "b:9092", "NONE", null, null))));
        when(verifier.verify(any())).thenReturn(1);

        controller.save(new KafkaSettingsController.KafkaRequest(
                List.of(plain("prod", "prod", "b:9092")), List.of("old")));

        verify(schemas).deleteByClusterName("old");
        verify(schemas, never()).deleteByClusterName("prod");
    }

    @Test
    void renamingAClusterKeepsTheOldNamesData() {
        saved(new KafkaSettings(List.of(new KafkaSettings.Cluster("old", "b:9092", "NONE", null, null))));
        when(verifier.verify(any())).thenReturn(1);

        controller.save(new KafkaSettingsController.KafkaRequest(List.of(plain("new", "old", "b:9092")), List.of()));

        verify(schemas, never()).deleteByClusterName(anyString());
        verify(store).write(any(), any(KafkaSettings.class));
    }

    @Test
    void aRenamedClusterKeepsItsStoredPassword() {
        saved(new KafkaSettings(List.of(new KafkaSettings.Cluster("old", "b:9092", "SASL", null,
                new KafkaSettings.Sasl("SCRAM-SHA-256", "u", "secret", false)))));
        when(verifier.verify(any())).thenReturn(1);

        controller.save(new KafkaSettingsController.KafkaRequest(List.of(sasl("new", "old", "")), List.of()));

        ArgumentCaptor<KafkaSettings> saved = ArgumentCaptor.forClass(KafkaSettings.class);
        verify(store).write(any(), saved.capture());
        assertThat(saved.getValue().clusters().get(0).sasl().password()).isEqualTo("secret");
    }

    @Test
    void unchangedClustersAreOnlyVerified() {
        saved(new KafkaSettings(List.of(new KafkaSettings.Cluster("prod", "b:9092", "NONE", null, null))));
        when(verifier.verify(any())).thenReturn(1);

        var response = controller.save(new KafkaSettingsController.KafkaRequest(
                List.of(plain("prod", "prod", "b:9092")), List.of()));

        verify(store, never()).write(any(), any());
        verify(restarter, never()).restart();
        assertThat(response.getBody().restarting()).isFalse();
    }

    @Test
    void saslWithoutAPasswordIsRejected() {
        when(store.read(KafkaSettings.SECTION, KafkaSettings.class)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.save(new KafkaSettingsController.KafkaRequest(
                List.of(sasl("prod", null, "")), null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("username and a password");
    }

    @Test
    void duplicateNamesAndUnknownMechanismsAreRejected() {
        when(store.read(KafkaSettings.SECTION, KafkaSettings.class)).thenReturn(Optional.empty());
        when(verifier.verify(any())).thenReturn(1);

        assertThatThrownBy(() -> controller.save(new KafkaSettingsController.KafkaRequest(
                List.of(plain("a", null, "b:1"), plain("a", null, "b:2")), null)))
                .hasMessageContaining("used twice");

        var badMechanism = new KafkaSettingsController.ClusterInput("m", null, "b:1", "SASL", null,
                new KafkaSettingsController.SaslInput("GSSAPI", "u", "p", false));
        assertThatThrownBy(() -> controller.save(new KafkaSettingsController.KafkaRequest(List.of(badMechanism), null)))
                .hasMessageContaining("mechanism");
    }
}
