package com.lynra.kafkatower.agents.specialists.kafka;

/**
 * Result of correlating {@link AdminSnapshot} findings with VictoriaMetrics trends (and, where
 * the Entity-Scale Discipline narrowing pattern applies, any follow-up AdminClient calls made
 * against the worst-N entities metrics surfaced). Fed to {@code synthesizeInvestigation} as the
 * second and final piece of evidence.
 */
public record MetricsCorrelation(String summary) {}
