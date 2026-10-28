package com.lynra.kafkatower.repository;

import com.lynra.kafkatower.model.AdminSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminSettingRepository extends JpaRepository<AdminSetting, String> {
}
