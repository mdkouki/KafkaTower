package com.lynra.kafkatower.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "prompt_analytics")
public class PromptAnalytics {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false)
    private Instant timestamp;

    @Column(name = "user_id")
    private String userId;

    @Column(nullable = false)
    private String theme;

    private String keywords;

    @Column(name = "agent_routed")
    private String agentRouted;

    protected PromptAnalytics() {}

    public PromptAnalytics(Instant timestamp, String userId, String theme, String keywords, String agentRouted) {
        this.timestamp   = timestamp;
        this.userId      = userId;
        this.theme       = theme;
        this.keywords    = keywords;
        this.agentRouted = agentRouted;
    }

    public Long getId()            { return id; }
    public Instant getTimestamp()  { return timestamp; }
    public String getUserId()      { return userId; }
    public String getTheme()       { return theme; }
    public String getKeywords()    { return keywords; }
    public String getAgentRouted() { return agentRouted; }
}
