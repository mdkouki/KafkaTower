package com.lynra.kafkatower.model;

import java.util.List;

/**
 * The Kafka clusters saved from the admin console. Certificates are referenced by file path on
 * the server, never stored.
 */
public record KafkaSettings(List<Cluster> clusters) {

    public static final String SECTION = "kafka";

    public static final String NONE = "NONE";
    public static final String MTLS = "MTLS";
    public static final String SASL = "SASL";

    public KafkaSettings {
        clusters = clusters == null ? List.of() : List.copyOf(clusters);
    }

    /** {@code security} is one of {@link #NONE}, {@link #MTLS}, {@link #SASL}. */
    public record Cluster(String name, String bootstrapServers, String security, Ssl ssl, Sasl sasl) {

        /** The Kafka {@code security.protocol} this combination means. */
        public String securityProtocol() {
            if (MTLS.equals(security)) return "SSL";
            if (SASL.equals(security)) return sasl != null && sasl.tls() ? "SASL_SSL" : "SASL_PLAINTEXT";
            return "PLAINTEXT";
        }

        /** Whether the TLS block (stores) applies: mTLS, or SASL over TLS. */
        public boolean usesTls() {
            return MTLS.equals(security) || (SASL.equals(security) && sasl != null && sasl.tls());
        }
    }

    public record Ssl(String keystoreLocation, String keystorePassword, String keystoreType, String keyPassword,
                      String truststoreLocation, String truststorePassword, String truststoreType) {}

    /** {@code mechanism}: PLAIN, SCRAM-SHA-256 or SCRAM-SHA-512. */
    public record Sasl(String mechanism, String username, String password, boolean tls) {}
}
