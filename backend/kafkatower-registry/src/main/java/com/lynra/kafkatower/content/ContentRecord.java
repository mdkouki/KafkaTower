package com.lynra.kafkatower.content;

import java.util.List;

/** One Kafka record as shown in the UI; {@code value} is null for a tombstone. */
public record ContentRecord(
        int partition,
        long offset,
        long timestamp,
        String key,
        List<Header> headers,
        String value,
        boolean decoded,
        boolean truncated) {

    public record Header(String key, String value) {
    }
}
