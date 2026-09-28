package com.lynra.kafkatower.kafka;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Shared AdminClient error-message formatting. TimeoutException carries no message, so a raw
 * e.getMessage() renders as the literal string "Error: null" — callers need the distinction
 * between "broker didn't respond" and any other AdminClient failure. Used by both GroupService
 * and KafkaAdminClientTools so the two don't drift into reporting the same failure differently.
 */
public final class AdminClientErrors {

    private AdminClientErrors() {
    }

    public static String describe(Exception e, int timeoutSec) {
        Throwable cause = (e instanceof ExecutionException && e.getCause() != null) ? e.getCause() : e;
        if (cause instanceof TimeoutException) {
            return "Error: the broker did not respond within " + timeoutSec + "s — the cluster may be unreachable.";
        }
        String msg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        return "Error during operation: " + cause.getClass().getSimpleName() + ": " + msg;
    }
}
