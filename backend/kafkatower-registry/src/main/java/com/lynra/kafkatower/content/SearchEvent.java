package com.lynra.kafkatower.content;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** One streamed message: {@code records} (matches so far), {@code progress}, {@code done} or {@code error}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SearchEvent(String type, List<ContentRecord> records, Long scanned, Integer matched, String message) {

    public static SearchEvent records(List<ContentRecord> records) {
        return new SearchEvent("records", records, null, null, null);
    }

    public static SearchEvent progress(long scanned, int matched) {
        return new SearchEvent("progress", null, scanned, matched, null);
    }

    public static SearchEvent done(long scanned, int matched, String message) {
        return new SearchEvent("done", null, scanned, matched, message);
    }

    public static SearchEvent error(String message) {
        return new SearchEvent("error", null, null, null, message);
    }
}
