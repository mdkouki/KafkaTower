package com.lynra.kafkatower.agents.specialists.metrics;

/**
 * Input to KafkaMetricsInspector: a historical/trend question about Kafka metrics.
 */
public record MetricsQuestion(String text) {}
