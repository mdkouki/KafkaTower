package com.lynra.kafkatower.controller;

import com.lynra.kafkatower.service.PromptAnalyticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final PromptAnalyticsService analyticsService;

    public AnalyticsController(PromptAnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/prompts")
    public PromptAnalyticsService.AnalyticsSummary getSummary() {
        return analyticsService.getSummary();
    }
}
