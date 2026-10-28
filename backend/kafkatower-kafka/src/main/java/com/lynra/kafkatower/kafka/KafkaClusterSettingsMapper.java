package com.lynra.kafkatower.kafka;

import com.lynra.kafkatower.model.KafkaSettings;

/** Turns a cluster saved in the admin console into the config {@link KafkaAdminConfig#clientConfig} reads. */
public final class KafkaClusterSettingsMapper {

    private KafkaClusterSettingsMapper() {
    }

    public static KafkaAdminConfig.KafkaClustersProperties.ClusterConfig toClusterConfig(KafkaSettings.Cluster c) {
        var config = new KafkaAdminConfig.KafkaClustersProperties.ClusterConfig();
        config.setBootstrapServers(c.bootstrapServers());
        config.setSecurityProtocol(c.securityProtocol());
        if (c.usesTls() && c.ssl() != null) {
            KafkaSettings.Ssl s = c.ssl();
            var ssl = new KafkaAdminConfig.SslConfig();
            ssl.setKeystoreLocation(s.keystoreLocation());
            ssl.setKeystorePassword(s.keystorePassword());
            if (hasText(s.keystoreType())) ssl.setKeystoreType(s.keystoreType());
            ssl.setKeyPassword(s.keyPassword());
            ssl.setTruststoreLocation(s.truststoreLocation());
            ssl.setTruststorePassword(s.truststorePassword());
            if (hasText(s.truststoreType())) ssl.setTruststoreType(s.truststoreType());
            config.setSsl(ssl);
        }
        if (KafkaSettings.SASL.equals(c.security()) && c.sasl() != null) {
            var sasl = new KafkaAdminConfig.SaslConfig();
            if (hasText(c.sasl().mechanism())) sasl.setMechanism(c.sasl().mechanism());
            sasl.setUsername(c.sasl().username());
            sasl.setPassword(c.sasl().password());
            config.setSasl(sasl);
        }
        return config;
    }

    private static boolean hasText(String v) {
        return v != null && !v.isBlank();
    }
}
