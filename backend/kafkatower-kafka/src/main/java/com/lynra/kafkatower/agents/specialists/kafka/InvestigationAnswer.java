package com.lynra.kafkatower.agents.specialists.kafka;

/**
 * Output of KafkaInvestigator: the agent's root-cause analysis or investigation result.
 */
public record InvestigationAnswer(String text) {}
