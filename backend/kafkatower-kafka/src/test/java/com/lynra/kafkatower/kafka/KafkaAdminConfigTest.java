package com.lynra.kafkatower.kafka;

import org.apache.kafka.clients.admin.AdminClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KafkaAdminConfigTest {

    private KafkaAdminConfig kafkaAdminConfig;
    private KafkaAdminConfig.KafkaClustersProperties properties;

    @BeforeEach
    void setUp() {
        kafkaAdminConfig = new KafkaAdminConfig();
        properties = new KafkaAdminConfig.KafkaClustersProperties();
    }

    @Test
    void testKafkaAdminMapWithSingleCluster() {
        KafkaAdminConfig.KafkaClustersProperties.ClusterConfig clusterConfig =
                new KafkaAdminConfig.KafkaClustersProperties.ClusterConfig();
        clusterConfig.setBootstrapServers("localhost:9092");
        clusterConfig.setSecurityProtocol("PLAINTEXT");

        Map<String, KafkaAdminConfig.KafkaClustersProperties.ClusterConfig> clusters = new HashMap<>();
        clusters.put("cluster1", clusterConfig);
        properties.setClusters(clusters);

        Map<String, AdminClient> adminMap = kafkaAdminConfig.kafkaAdminMap(properties);

        assertNotNull(adminMap);
        assertTrue(adminMap.containsKey("cluster1"));
    }

    @Test
    void testKafkaAdminMapWithMultipleClusters() {
        KafkaAdminConfig.KafkaClustersProperties.ClusterConfig cluster1 =
                new KafkaAdminConfig.KafkaClustersProperties.ClusterConfig();
        cluster1.setBootstrapServers("localhost:9092");
        cluster1.setSecurityProtocol("PLAINTEXT");

        KafkaAdminConfig.KafkaClustersProperties.ClusterConfig cluster2 =
                new KafkaAdminConfig.KafkaClustersProperties.ClusterConfig();
        cluster2.setBootstrapServers("localhost:9093");
        cluster2.setSecurityProtocol("PLAINTEXT");

        Map<String, KafkaAdminConfig.KafkaClustersProperties.ClusterConfig> clusters = new HashMap<>();
        clusters.put("cluster1", cluster1);
        clusters.put("cluster2", cluster2);
        properties.setClusters(clusters);

        Map<String, AdminClient> adminMap = kafkaAdminConfig.kafkaAdminMap(properties);

        assertNotNull(adminMap);
        assertEquals(2, adminMap.size());
        assertTrue(adminMap.containsKey("cluster1"));
        assertTrue(adminMap.containsKey("cluster2"));
    }

    @Test
    void testKafkaAdminMapWithEmptyClusters() {
        properties.setClusters(new HashMap<>());

        Map<String, AdminClient> adminMap = kafkaAdminConfig.kafkaAdminMap(properties);

        assertNotNull(adminMap);
        assertTrue(adminMap.isEmpty());
    }

    @Test
    void testClusterConfigGettersSetters() {
        KafkaAdminConfig.KafkaClustersProperties.ClusterConfig config =
                new KafkaAdminConfig.KafkaClustersProperties.ClusterConfig();

        config.setBootstrapServers("localhost:9092");
        config.setSecurityProtocol("SSL");

        assertEquals("localhost:9092", config.getBootstrapServers());
        assertEquals("SSL", config.getSecurityProtocol());
    }

    @Test
    void testClusterConfigDefaultSecurityProtocol() {
        KafkaAdminConfig.KafkaClustersProperties.ClusterConfig config =
                new KafkaAdminConfig.KafkaClustersProperties.ClusterConfig();

        assertEquals("PLAINTEXT", config.getSecurityProtocol());
    }

    @Test
    void testClusterConfigWithSslConfig() {
        KafkaAdminConfig.KafkaClustersProperties.ClusterConfig config =
                new KafkaAdminConfig.KafkaClustersProperties.ClusterConfig();
        KafkaAdminConfig.SslConfig sslConfig = new KafkaAdminConfig.SslConfig();

        config.setBootstrapServers("localhost:9092");
        config.setSecurityProtocol("SSL");
        config.setSsl(sslConfig);

        assertNotNull(config.getSsl());
    }

    @Test
    void testSslConfigGettersSetters() {
        KafkaAdminConfig.SslConfig sslConfig = new KafkaAdminConfig.SslConfig();

        sslConfig.setKeystoreLocation("/path/to/keystore");
        sslConfig.setKeystorePassword("password");
        sslConfig.setKeystoreType("PKCS12");
        sslConfig.setKeyPassword("keypass");
        sslConfig.setTruststoreLocation("/path/to/truststore");
        sslConfig.setTruststorePassword("trustpass");
        sslConfig.setTruststoreType("JKS");

        assertEquals("/path/to/keystore", sslConfig.getKeystoreLocation());
        assertEquals("password", sslConfig.getKeystorePassword());
        assertEquals("PKCS12", sslConfig.getKeystoreType());
        assertEquals("keypass", sslConfig.getKeyPassword());
        assertEquals("/path/to/truststore", sslConfig.getTruststoreLocation());
        assertEquals("trustpass", sslConfig.getTruststorePassword());
        assertEquals("JKS", sslConfig.getTruststoreType());
    }

    @Test
    void testSslConfigDefaultTypes() {
        KafkaAdminConfig.SslConfig sslConfig = new KafkaAdminConfig.SslConfig();

        assertEquals("PKCS12", sslConfig.getKeystoreType());
        assertEquals("PKCS12", sslConfig.getTruststoreType());
    }

    @Test
    void testKafkaClustersPropertiesGettersSetters() {
        KafkaAdminConfig.KafkaClustersProperties props = new KafkaAdminConfig.KafkaClustersProperties();
        Map<String, KafkaAdminConfig.KafkaClustersProperties.ClusterConfig> clusters = new HashMap<>();

        props.setClusters(clusters);

        assertNotNull(props.getClusters());
        assertTrue(props.getClusters().isEmpty());
    }

    @Test
    void testKafkaAdminMapWithSslCluster() {
        KafkaAdminConfig.SslConfig sslConfig = new KafkaAdminConfig.SslConfig();
        sslConfig.setKeystoreLocation("/path/to/keystore.p12");
        sslConfig.setKeystorePassword("password");

        KafkaAdminConfig.KafkaClustersProperties.ClusterConfig clusterConfig =
                new KafkaAdminConfig.KafkaClustersProperties.ClusterConfig();
        clusterConfig.setBootstrapServers("localhost:9092");
        clusterConfig.setSecurityProtocol("PLAINTEXT");
        clusterConfig.setSsl(sslConfig);

        Map<String, KafkaAdminConfig.KafkaClustersProperties.ClusterConfig> clusters = new HashMap<>();
        clusters.put("ssl-cluster", clusterConfig);
        properties.setClusters(clusters);

        Map<String, AdminClient> adminMap = kafkaAdminConfig.kafkaAdminMap(properties);

        assertNotNull(adminMap);
        assertTrue(adminMap.containsKey("ssl-cluster"));
    }

    @Test
    void testClusterConfigNullSsl() {
        KafkaAdminConfig.KafkaClustersProperties.ClusterConfig config =
                new KafkaAdminConfig.KafkaClustersProperties.ClusterConfig();

        assertNull(config.getSsl());
    }

    @Test
    void testSslConfigNullValues() {
        KafkaAdminConfig.SslConfig sslConfig = new KafkaAdminConfig.SslConfig();

        assertNull(sslConfig.getKeystoreLocation());
        assertNull(sslConfig.getKeystorePassword());
        assertNull(sslConfig.getKeyPassword());
        assertNull(sslConfig.getTruststoreLocation());
        assertNull(sslConfig.getTruststorePassword());
    }

    @Test
    void testKafkaAdminMapWithBlankSslValues() {
        KafkaAdminConfig.SslConfig sslConfig = new KafkaAdminConfig.SslConfig();
        sslConfig.setKeystoreLocation("");  // blank value should be ignored
        sslConfig.setKeystorePassword("password");

        KafkaAdminConfig.KafkaClustersProperties.ClusterConfig clusterConfig =
                new KafkaAdminConfig.KafkaClustersProperties.ClusterConfig();
        clusterConfig.setBootstrapServers("localhost:9092");
        clusterConfig.setSecurityProtocol("PLAINTEXT");
        clusterConfig.setSsl(sslConfig);

        Map<String, KafkaAdminConfig.KafkaClustersProperties.ClusterConfig> clusters = new HashMap<>();
        clusters.put("cluster", clusterConfig);
        properties.setClusters(clusters);

        Map<String, AdminClient> adminMap = kafkaAdminConfig.kafkaAdminMap(properties);

        assertNotNull(adminMap);
    }

    @Test
    void testMultipleClusterConfigs() {
        for (int i = 1; i <= 5; i++) {
            KafkaAdminConfig.KafkaClustersProperties.ClusterConfig config =
                    new KafkaAdminConfig.KafkaClustersProperties.ClusterConfig();
            config.setBootstrapServers("cluster" + i + ":9092");
            config.setSecurityProtocol("PLAINTEXT");
        }
    }

    @Test
    void testClusterConfigBootstrapServersNull() {
        KafkaAdminConfig.KafkaClustersProperties.ClusterConfig config =
                new KafkaAdminConfig.KafkaClustersProperties.ClusterConfig();

        assertNull(config.getBootstrapServers());
    }

    @Test
    void testSslConfigChaining() {
        KafkaAdminConfig.SslConfig sslConfig = new KafkaAdminConfig.SslConfig();

        sslConfig.setKeystoreLocation("/keystore");
        sslConfig.setKeystorePassword("pass");
        sslConfig.setTruststoreLocation("/truststore");

        assertEquals("/keystore", sslConfig.getKeystoreLocation());
        assertEquals("pass", sslConfig.getKeystorePassword());
        assertEquals("/truststore", sslConfig.getTruststoreLocation());
    }

    @Test
    void testSaslConfigDefaultMechanism() {
        KafkaAdminConfig.SaslConfig sasl = new KafkaAdminConfig.SaslConfig();

        assertEquals("PLAIN", sasl.getMechanism());
    }

    @Test
    void testSaslConfigResolveJaasConfigFromUsernamePassword() {
        KafkaAdminConfig.SaslConfig sasl = new KafkaAdminConfig.SaslConfig();
        sasl.setUsername("kafkatower");
        sasl.setPassword("kafkatower-secret");

        assertEquals("org.apache.kafka.common.security.plain.PlainLoginModule required "
                + "username=\"kafkatower\" password=\"kafkatower-secret\";", sasl.resolveJaasConfig());
    }

    @Test
    void testSaslConfigResolveJaasConfigPrefersExplicitJaasConfig() {
        KafkaAdminConfig.SaslConfig sasl = new KafkaAdminConfig.SaslConfig();
        sasl.setUsername("kafkatower");
        sasl.setPassword("kafkatower-secret");
        sasl.setJaasConfig("custom.LoginModule required;");

        assertEquals("custom.LoginModule required;", sasl.resolveJaasConfig());
    }

    @Test
    void testSaslConfigResolveJaasConfigNullWhenNothingSet() {
        KafkaAdminConfig.SaslConfig sasl = new KafkaAdminConfig.SaslConfig();

        assertNull(sasl.resolveJaasConfig());
    }

    @Test
    void testClusterConfigWithSaslConfig() {
        KafkaAdminConfig.KafkaClustersProperties.ClusterConfig config =
                new KafkaAdminConfig.KafkaClustersProperties.ClusterConfig();
        KafkaAdminConfig.SaslConfig sasl = new KafkaAdminConfig.SaslConfig();
        sasl.setUsername("kafkatower");
        sasl.setPassword("kafkatower-secret");

        config.setBootstrapServers("localhost:9092");
        config.setSecurityProtocol("SASL_PLAINTEXT");
        config.setSasl(sasl);

        assertNotNull(config.getSasl());
        assertEquals("kafkatower", config.getSasl().getUsername());
    }

    @Test
    void testKafkaAdminMapWithSaslConfig() {
        KafkaAdminConfig.KafkaClustersProperties.ClusterConfig clusterConfig =
                new KafkaAdminConfig.KafkaClustersProperties.ClusterConfig();
        clusterConfig.setBootstrapServers("localhost:9092");
        clusterConfig.setSecurityProtocol("SASL_PLAINTEXT");
        KafkaAdminConfig.SaslConfig sasl = new KafkaAdminConfig.SaslConfig();
        sasl.setUsername("kafkatower");
        sasl.setPassword("kafkatower-secret");
        clusterConfig.setSasl(sasl);

        Map<String, KafkaAdminConfig.KafkaClustersProperties.ClusterConfig> clusters = new HashMap<>();
        clusters.put("cluster1", clusterConfig);
        properties.setClusters(clusters);

        Map<String, AdminClient> adminMap = kafkaAdminConfig.kafkaAdminMap(properties);

        assertNotNull(adminMap);
        assertTrue(adminMap.containsKey("cluster1"));
    }
}
