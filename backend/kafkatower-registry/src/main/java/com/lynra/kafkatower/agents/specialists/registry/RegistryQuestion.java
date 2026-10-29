package com.lynra.kafkatower.agents.specialists.registry;

/** Input to KafkaRegistryInspector: a question about dependencies between Kafka resources or who uses what. */
public record RegistryQuestion(String text) {}
