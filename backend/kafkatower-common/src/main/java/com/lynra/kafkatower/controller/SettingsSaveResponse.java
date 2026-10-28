package com.lynra.kafkatower.controller;

/** Answer of a settings save; {@code restarting} tells the UI to wait for the application to come back. */
public record SettingsSaveResponse(String status, String note, boolean restarting) {}
