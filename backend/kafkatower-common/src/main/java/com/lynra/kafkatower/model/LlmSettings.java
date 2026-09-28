package com.lynra.kafkatower.model;

import jakarta.persistence.*;

@Entity
@Table(name = "llm_settings")
public class LlmSettings {

    @Id
    private Long id = 1L;

    @Column(name = "api_key",length = 1000)
    private String apiKey;

    @Column(name = "base_url")
    private String baseUrl;

    @Column(name = "model")
    private String model;

    public LlmSettings() {}

    public Long getId() { return id; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
}