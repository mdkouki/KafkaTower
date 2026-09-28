package com.lynra.kafkatower.repository;

import com.lynra.kafkatower.model.LlmSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmSettingsRepository extends JpaRepository<LlmSettings, Long> {
}