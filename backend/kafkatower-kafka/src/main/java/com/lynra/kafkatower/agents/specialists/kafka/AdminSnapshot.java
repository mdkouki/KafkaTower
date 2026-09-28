package com.lynra.kafkatower.agents.specialists.kafka;

/**
 * Faithful summary of {@code gatherAdminState}'s AdminClient findings — the only view
 * {@code correlateMetrics} and {@code synthesizeInvestigation} get of the admin step, since
 * actions share no LLM conversation/session (each is a fresh call). Must not be lossy: dropping
 * a finding here means it's gone for the rest of the investigation.
 */
public record AdminSnapshot(String summary) {}
