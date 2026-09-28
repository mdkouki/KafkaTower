package com.lynra.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "demo")
public class DemoProperties {

    private String appName = "demo";
    private String consumerGroup = "demo-group";
    private List<String> consumes = List.of();
    private List<String> produces = List.of();
    private String transactionalId;
    private long produceIntervalMs = 5000;

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }

    public List<String> getConsumes() {
        return consumes;
    }

    // compose.yml's DEMO_CONSUMES/DEMO_PRODUCES are YAML folded block scalars — newlines become
    // spaces, so each comma-split entry can carry leading/trailing whitespace; trim defensively.
    public void setConsumes(List<String> consumes) {
        this.consumes = trimAll(consumes);
    }

    public List<String> getProduces() {
        return produces;
    }

    public void setProduces(List<String> produces) {
        this.produces = trimAll(produces);
    }

    public String getTransactionalId() {
        return transactionalId;
    }

    public void setTransactionalId(String transactionalId) {
        this.transactionalId = transactionalId;
    }

    public boolean isTransactional() {
        return transactionalId != null && !transactionalId.isBlank();
    }

    public long getProduceIntervalMs() {
        return produceIntervalMs;
    }

    public void setProduceIntervalMs(long produceIntervalMs) {
        this.produceIntervalMs = produceIntervalMs;
    }

    private static List<String> trimAll(List<String> values) {
        return values.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
