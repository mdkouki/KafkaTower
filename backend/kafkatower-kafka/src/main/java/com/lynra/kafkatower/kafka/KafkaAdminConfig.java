package com.lynra.kafkatower.kafka;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Configuration
@EnableConfigurationProperties(KafkaAdminConfig.KafkaClustersProperties.class)
public class KafkaAdminConfig {

    // NOTE: Map<String, AdminClient> injection first resolves as "every bean of type
    // AdminClient, keyed by bean name"; it only falls back to this bean itself because no
    // individual AdminClient bean currently exists. Adding one anywhere in the context would
    // silently break every kafkaAdminMap.get(clusterName) lookup in KafkaAdminClientTools and
    // SubAgentTools with no compile-time warning — keep it that way, or introduce a dedicated
    // holder type (e.g. a KafkaAdminClients record) if that ever needs to change.
    //
    // AdminClient.close() has no destroyMethod hook here because Spring can't call a destroy
    // method on a returned Map's values — closeOnShutdown() below does that explicitly so
    // sockets/metrics threads don't leak across a context refresh (devtools restarts, tests).
    @Bean(destroyMethod = "")
    public Map<String, AdminClient> kafkaAdminMap(KafkaClustersProperties properties) {
        Map<String, AdminClient> clients = properties.getClusters().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> AdminClient.create(buildConfig(e.getValue()))
                ));
        return clients;
    }

    @Bean
    public AdminClientsCloser adminClientsCloser(Map<String, AdminClient> kafkaAdminMap) {
        return new AdminClientsCloser(kafkaAdminMap);
    }

    /** Closes every AdminClient in {@code kafkaAdminMap} when the application context shuts down. */
    public static final class AdminClientsCloser {
        private final Map<String, AdminClient> clients;

        AdminClientsCloser(Map<String, AdminClient> clients) {
            this.clients = clients;
        }

        @jakarta.annotation.PreDestroy
        void close() {
            clients.values().forEach(AdminClient::close);
        }
    }

    private Map<String, Object> buildConfig(KafkaClustersProperties.ClusterConfig cluster) {
        Map<String, Object> config = new HashMap<>();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, cluster.getBootstrapServers());
        config.put(AdminClientConfig.SECURITY_PROTOCOL_CONFIG, cluster.getSecurityProtocol());
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 8000);

        SslConfig ssl = cluster.getSsl();
        if (ssl != null) {
            putIfSet(config, SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, ssl.getKeystoreLocation());
            putIfSet(config, SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, ssl.getKeystorePassword());
            putIfSet(config, SslConfigs.SSL_KEYSTORE_TYPE_CONFIG, ssl.getKeystoreType());
            putIfSet(config, SslConfigs.SSL_KEY_PASSWORD_CONFIG, ssl.getKeyPassword());
            putIfSet(config, SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, ssl.getTruststoreLocation());
            putIfSet(config, SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, ssl.getTruststorePassword());
            putIfSet(config, SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, ssl.getTruststoreType());
        }

        SaslConfig sasl = cluster.getSasl();
        if (sasl != null) {
            putIfSet(config, SaslConfigs.SASL_MECHANISM, sasl.getMechanism());
            putIfSet(config, SaslConfigs.SASL_JAAS_CONFIG, sasl.resolveJaasConfig());
        }
        return config;
    }

    private void putIfSet(Map<String, Object> config, String key, String value) {
        if (value != null && !value.isBlank()) {
            config.put(key, value);
        }
    }

    @ConfigurationProperties(prefix = "kafka")
    public static class KafkaClustersProperties {

        private Map<String, ClusterConfig> clusters = new HashMap<>();

        public Map<String, ClusterConfig> getClusters() {
            return clusters;
        }

        public void setClusters(Map<String, ClusterConfig> clusters) {
            this.clusters = clusters;
        }

        public static class ClusterConfig {
            private String bootstrapServers;
            private String securityProtocol = "PLAINTEXT";
            private SslConfig ssl;
            private SaslConfig sasl;

            public String getBootstrapServers() {
                return bootstrapServers;
            }

            public void setBootstrapServers(String bootstrapServers) {
                this.bootstrapServers = bootstrapServers;
            }

            public String getSecurityProtocol() {
                return securityProtocol;
            }

            public void setSecurityProtocol(String securityProtocol) {
                this.securityProtocol = securityProtocol;
            }

            public SslConfig getSsl() {
                return ssl;
            }

            public void setSsl(SslConfig ssl) {
                this.ssl = ssl;
            }

            public SaslConfig getSasl() {
                return sasl;
            }

            public void setSasl(SaslConfig sasl) {
                this.sasl = sasl;
            }
        }
    }

    public static class SslConfig {
        private String keystoreLocation;
        private String keystorePassword;
        private String keystoreType = "PKCS12";
        private String keyPassword;
        private String truststoreLocation;
        private String truststorePassword;
        private String truststoreType = "PKCS12";

        public String getKeystoreLocation() {
            return keystoreLocation;
        }

        public void setKeystoreLocation(String v) {
            this.keystoreLocation = v;
        }

        public String getKeystorePassword() {
            return keystorePassword;
        }

        public void setKeystorePassword(String v) {
            this.keystorePassword = v;
        }

        public String getKeystoreType() {
            return keystoreType;
        }

        public void setKeystoreType(String v) {
            this.keystoreType = v;
        }

        public String getKeyPassword() {
            return keyPassword;
        }

        public void setKeyPassword(String v) {
            this.keyPassword = v;
        }

        public String getTruststoreLocation() {
            return truststoreLocation;
        }

        public void setTruststoreLocation(String v) {
            this.truststoreLocation = v;
        }

        public String getTruststorePassword() {
            return truststorePassword;
        }

        public void setTruststorePassword(String v) {
            this.truststorePassword = v;
        }

        public String getTruststoreType() {
            return truststoreType;
        }

        public void setTruststoreType(String v) {
            this.truststoreType = v;
        }
    }

    /**
     * SASL/PLAIN (or SCRAM) client credentials for a cluster. Either set {@code jaasConfig}
     * directly, or set {@code username}/{@code password} and a PLAIN-mechanism JAAS config
     * string is built for you — the common case, and avoids hand-quoting the JAAS module string
     * in YAML.
     */
    public static class SaslConfig {
        private String mechanism = "PLAIN";
        private String username;
        private String password;
        private String jaasConfig;

        public String getMechanism() {
            return mechanism;
        }

        public void setMechanism(String mechanism) {
            this.mechanism = mechanism;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getJaasConfig() {
            return jaasConfig;
        }

        public void setJaasConfig(String jaasConfig) {
            this.jaasConfig = jaasConfig;
        }

        /** Returns {@code jaasConfig} verbatim if set, else builds a PlainLoginModule config from username/password. */
        String resolveJaasConfig() {
            if (jaasConfig != null && !jaasConfig.isBlank()) {
                return jaasConfig;
            }
            if (username == null || username.isBlank()) {
                return null;
            }
            return "org.apache.kafka.common.security.plain.PlainLoginModule required username=\""
                    + username + "\" password=\"" + password + "\";";
        }
    }
}