package com.lynra.kafkatower.agents.specialists.log;

/** LLM-extracted target cluster for a {@link KafkaLogInspector} question. */
public record ClusterSelection(String clusterName) {}
