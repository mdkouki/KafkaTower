package com.lynra.kafkatower.repository;

import com.lynra.kafkatower.model.PromptAnalytics;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PromptAnalyticsRepository extends JpaRepository<PromptAnalytics, Long> {

    @Query("SELECT p.theme, COUNT(p) FROM PromptAnalytics p GROUP BY p.theme ORDER BY COUNT(p) DESC")
    List<Object[]> countByTheme();

    @Query("SELECT p.agentRouted, COUNT(p) FROM PromptAnalytics p GROUP BY p.agentRouted ORDER BY COUNT(p) DESC")
    List<Object[]> countByAgent();

    @Query(value = "SELECT CAST(created_at AS DATE) as record_date, COUNT(*) as cnt " +
                   "FROM prompt_analytics " +
                   "GROUP BY CAST(created_at AS DATE) " +
                   "ORDER BY record_date DESC " +
                   "LIMIT 30",
           nativeQuery = true)
    List<Object[]> countByDay();

    List<PromptAnalytics> findTop20ByOrderByTimestampDesc();

    @Query("SELECT p.userId, COUNT(p) FROM PromptAnalytics p GROUP BY p.userId ORDER BY COUNT(p) DESC")
    List<Object[]> countByUser(Pageable pageable);
}
