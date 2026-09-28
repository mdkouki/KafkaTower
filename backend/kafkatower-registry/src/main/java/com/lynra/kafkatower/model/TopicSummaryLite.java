package com.lynra.kafkatower.model;

/** Row shape for the topic picker list — name and partition count only, full detail is a separate call. */
public record TopicSummaryLite(String name, int partitionCount) {

    public static TopicSummaryLite from(TopicDetail t) {
        return new TopicSummaryLite(t.name(), t.partitionCount());
    }
}
