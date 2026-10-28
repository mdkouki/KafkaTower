package com.lynra.kafkatower.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lynra.kafkatower.model.AdminSetting;
import com.lynra.kafkatower.repository.AdminSettingRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Reads and writes the JSON-backed admin settings sections. */
@Component
public class AdminSettingsStore {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AdminSettingsStore.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final AdminSettingRepository repo;

    public AdminSettingsStore(AdminSettingRepository repo) {
        this.repo = repo;
    }

    /** The saved section, or empty when it was never saved or its stored payload cannot be read. */
    public <T> Optional<T> read(String section, Class<T> type) {
        return repo.findById(section).map(AdminSetting::getPayload).flatMap(json -> {
            try {
                return Optional.of(parse(json, type));
            } catch (IllegalStateException e) {
                log.warn("Ignoring unreadable admin settings '{}': {}", section, e.getMessage());
                return Optional.empty();
            }
        });
    }

    public void write(String section, Object value) {
        repo.save(new AdminSetting(section, toJson(value)));
    }

    public static <T> T parse(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable admin settings: " + e.getMessage(), e);
        }
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
