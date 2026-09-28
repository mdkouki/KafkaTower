package com.lynra.kafkatower.model;

/** One consumer group's lag on a single topic, resolved live (not cached) when a topic's detail is opened. */
public record TopicGroupLag(String groupId, String state, long lag) {
}
