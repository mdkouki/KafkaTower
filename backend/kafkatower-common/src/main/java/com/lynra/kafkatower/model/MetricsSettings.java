package com.lynra.kafkatower.model;

/** The Prometheus-compatible metrics backend saved from the admin console. */
public record MetricsSettings(String url, String token, String username, String password, String tenant) {

    public static final String SECTION = "metrics";
}
