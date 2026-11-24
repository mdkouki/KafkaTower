package com.lynra.kafkatower.kafka;

import com.lynra.kafkatower.model.KafkaSettings;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaClusterSettingsMapperTest {

    private static KafkaSettings.Ssl ssl(String keystoreType, String truststoreType) {
        return new KafkaSettings.Ssl("/k.jks", "ks-pass", keystoreType, "key-pass", "/t.jks", "ts-pass", truststoreType);
    }

    @Test
    void copiesBootstrapServersAndProtocol() {
        var cluster = new KafkaSettings.Cluster("a", "b:9092", KafkaSettings.NONE, null, null);

        var config = KafkaClusterSettingsMapper.toClusterConfig(cluster);

        assertThat(config.getBootstrapServers()).isEqualTo("b:9092");
        assertThat(config.getSecurityProtocol()).isEqualTo("PLAINTEXT");
        assertThat(config.getSsl()).isNull();
        assertThat(config.getSasl()).isNull();
    }

    @Test
    void mapsTlsStoresIncludingExplicitTypes() {
        var cluster = new KafkaSettings.Cluster("a", "b:9093", KafkaSettings.MTLS, ssl("JKS", "JKS"), null);

        var ssl = KafkaClusterSettingsMapper.toClusterConfig(cluster).getSsl();

        assertThat(ssl.getKeystoreLocation()).isEqualTo("/k.jks");
        assertThat(ssl.getKeystorePassword()).isEqualTo("ks-pass");
        assertThat(ssl.getKeyPassword()).isEqualTo("key-pass");
        assertThat(ssl.getTruststoreLocation()).isEqualTo("/t.jks");
        assertThat(ssl.getTruststorePassword()).isEqualTo("ts-pass");
        assertThat(ssl.getKeystoreType()).isEqualTo("JKS");
        assertThat(ssl.getTruststoreType()).isEqualTo("JKS");
    }

    @Test
    void blankStoreTypesKeepTheDefaults() {
        var cluster = new KafkaSettings.Cluster("a", "b:9093", KafkaSettings.MTLS, ssl(" ", null), null);

        var ssl = KafkaClusterSettingsMapper.toClusterConfig(cluster).getSsl();

        assertThat(ssl.getKeystoreType()).isEqualTo("PKCS12");
        assertThat(ssl.getTruststoreType()).isEqualTo("PKCS12");
    }

    @Test
    void tlsBlockIsIgnoredWhenTheSecurityTypeDoesNotUseTls() {
        var cluster = new KafkaSettings.Cluster("a", "b:9092", KafkaSettings.NONE, ssl("JKS", "JKS"), null);

        assertThat(KafkaClusterSettingsMapper.toClusterConfig(cluster).getSsl()).isNull();
    }

    @Test
    void mapsSaslCredentialsAndMechanism() {
        var cluster = new KafkaSettings.Cluster("a", "b:9092", KafkaSettings.SASL, null,
                new KafkaSettings.Sasl("SCRAM-SHA-256", "user", "secret", false));

        var sasl = KafkaClusterSettingsMapper.toClusterConfig(cluster).getSasl();

        assertThat(sasl.getMechanism()).isEqualTo("SCRAM-SHA-256");
        assertThat(sasl.getUsername()).isEqualTo("user");
        assertThat(sasl.getPassword()).isEqualTo("secret");
    }

    @Test
    void blankSaslMechanismKeepsThePlainDefault() {
        var cluster = new KafkaSettings.Cluster("a", "b:9092", KafkaSettings.SASL, null,
                new KafkaSettings.Sasl("", "user", "secret", false));

        assertThat(KafkaClusterSettingsMapper.toClusterConfig(cluster).getSasl().getMechanism()).isEqualTo("PLAIN");
    }

    @Test
    void saslBlockIsIgnoredWhenSecurityTypeIsNotSasl() {
        var cluster = new KafkaSettings.Cluster("a", "b:9092", KafkaSettings.NONE, null,
                new KafkaSettings.Sasl("PLAIN", "user", "secret", false));

        assertThat(KafkaClusterSettingsMapper.toClusterConfig(cluster).getSasl()).isNull();
    }
}
