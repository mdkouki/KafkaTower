package com.lynra.kafkatower.kafka;

/**
 * Raised by {@link GroupService#getGroupState} when the AdminClient calls needed to resolve a
 * consumer group's state fail (timeout, unreachable broker, ...) — as opposed to returning
 * {@code null}, which means the group genuinely has no state to report.
 */
public class GroupLookupException extends RuntimeException {
    public GroupLookupException(String message, Throwable cause) {
        super(message, cause);
    }
}
