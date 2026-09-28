package com.lynra.kafkatower.agents.specialists.kafka;

/**
 * Input to KafkaInvestigator: a live-state investigation or root-cause question about Kafka.
 */
public record InvestigationQuestion(String text) {}
