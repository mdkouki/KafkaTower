package com.lynra.kafkatower.kafka;

import com.lynra.kafkatower.model.KafkaSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KafkaClusterSettingsTest {

    private static KafkaSettings.Ssl ssl(String keystore, String truststore) {
        return new KafkaSettings.Ssl(keystore, "ks-pass", "PKCS12", "key-pass", truststore, "ts-pass", "PKCS12");
    }

    private static Map<String, Object> config(KafkaSettings.Cluster c) {
        return KafkaAdminConfig.clientConfig(KafkaClusterSettingsMapper.toClusterConfig(c));
    }

    @Test
    void noSecurityIsPlaintext() {
        var c = new KafkaSettings.Cluster("a", "b:9092", KafkaSettings.NONE, null, null);

        assertThat(config(c)).containsEntry("security.protocol", "PLAINTEXT").doesNotContainKey("sasl.mechanism");
    }

    @Test
    void mtlsUsesSslWithBothStores() {
        var c = new KafkaSettings.Cluster("a", "b:9093", KafkaSettings.MTLS, ssl("/k.p12", "/t.p12"), null);

        assertThat(config(c)).containsEntry("security.protocol", "SSL")
                .containsEntry("ssl.keystore.location", "/k.p12")
                .containsEntry("ssl.keystore.password", "ks-pass")
                .containsEntry("ssl.key.password", "key-pass")
                .containsEntry("ssl.truststore.location", "/t.p12");
    }

    @Test
    void saslPlainOverPlaintext() {
        var c = new KafkaSettings.Cluster("a", "b:9092", KafkaSettings.SASL, null,
                new KafkaSettings.Sasl("PLAIN", "u", "p", false));

        Map<String, Object> config = config(c);
        assertThat(config).containsEntry("security.protocol", "SASL_PLAINTEXT").containsEntry("sasl.mechanism", "PLAIN");
        assertThat((String) config.get("sasl.jaas.config")).contains("PlainLoginModule").contains("username=\"u\"");
    }

    @Test
    void scramUsesTheScramLoginModule() {
        var c = new KafkaSettings.Cluster("a", "b:9092", KafkaSettings.SASL, null,
                new KafkaSettings.Sasl("SCRAM-SHA-512", "u", "p", false));

        assertThat((String) config(c).get("sasl.jaas.config")).contains("ScramLoginModule");
    }

    @Test
    void saslOverTlsIsSaslSslWithTheTruststoreOnly() {
        var c = new KafkaSettings.Cluster("a", "b:9094", KafkaSettings.SASL, ssl("/ignored.p12", "/t.p12"),
                new KafkaSettings.Sasl("PLAIN", "u", "p", true));

        assertThat(config(c)).containsEntry("security.protocol", "SASL_SSL").containsEntry("ssl.truststore.location", "/t.p12");
    }

    @Test
    void passwordsWithQuotesAreEscapedInTheJaasConfig() {
        var c = new KafkaSettings.Cluster("a", "b:9092", KafkaSettings.SASL, null,
                new KafkaSettings.Sasl("PLAIN", "u", "pa\"ss", false));

        assertThat((String) config(c).get("sasl.jaas.config")).contains("password=\"pa\\\"ss\"");
    }

    @Test
    void aMissingKeystoreFileIsReportedBeforeAnyConnection(@TempDir Path dir) {
        var c = new KafkaSettings.Cluster("a", "localhost:1", KafkaSettings.MTLS,
                ssl(dir.resolve("nope.p12").toString(), ""), null);

        assertThatThrownBy(() -> new KafkaClusterVerifier().verify(c))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Keystore file not found");
    }

    @Test
    void mtlsWithoutAKeystorePathIsRejected() {
        var c = new KafkaSettings.Cluster("a", "localhost:1", KafkaSettings.MTLS, ssl("", ""), null);

        assertThatThrownBy(() -> new KafkaClusterVerifier().verify(c))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Keystore path is required");
    }

    @Test
    void anExistingTruststoreMissingFileIsReported(@TempDir Path dir) throws Exception {
        Path keystore = Files.createFile(dir.resolve("client.p12"));
        var c = new KafkaSettings.Cluster("a", "localhost:1", KafkaSettings.MTLS,
                ssl(keystore.toString(), dir.resolve("missing.p12").toString()), null);

        assertThatThrownBy(() -> new KafkaClusterVerifier().verify(c))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Truststore file not found");
    }
}
