package com.lynra.kafkatower.kafka;

import com.lynra.kafkatower.model.KafkaSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KafkaClusterVerifierTest {

    private final KafkaClusterVerifier verifier = new KafkaClusterVerifier();

    @Test
    void mtlsWithoutAnySslBlockNeedsAKeystore() {
        var cluster = new KafkaSettings.Cluster("a", "localhost:1", KafkaSettings.MTLS, null, null);

        assertThatThrownBy(() -> verifier.verify(cluster))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("mTLS needs a client keystore.");
    }

    @Test
    void aDirectoryIsNotAcceptedAsKeystoreFile(@TempDir Path dir) {
        var ssl = new KafkaSettings.Ssl(dir.toString(), "p", "PKCS12", "p", "", "", "PKCS12");
        var cluster = new KafkaSettings.Cluster("a", "localhost:1", KafkaSettings.MTLS, ssl, null);

        assertThatThrownBy(() -> verifier.verify(cluster))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Keystore file not found on the server");
    }

    @Test
    void saslOverTlsChecksTheTruststoreFile(@TempDir Path dir) {
        var ssl = new KafkaSettings.Ssl("", "", "PKCS12", "", dir.resolve("missing.p12").toString(), "", "PKCS12");
        var sasl = new KafkaSettings.Sasl("PLAIN", "u", "p", true);
        var cluster = new KafkaSettings.Cluster("a", "localhost:1", KafkaSettings.SASL, ssl, sasl);

        assertThatThrownBy(() -> verifier.verify(cluster))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Truststore file not found");
    }
}
