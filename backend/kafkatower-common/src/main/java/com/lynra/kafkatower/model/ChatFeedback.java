package com.lynra.kafkatower.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "chat_feedback")
public class ChatFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String sessionId;

    @Column(nullable = false)
    private String userId;

    @Lob
    @Column(nullable = false, columnDefinition = "CLOB")
    private String userPrompt;

    @Lob
    @Column(nullable = false, columnDefinition = "CLOB")
    private String agentAnswer;

    @Column(nullable = false)
    private String feedback; // UP or DOWN

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected ChatFeedback() {}

    public ChatFeedback(String sessionId, String userId, String userPrompt, String agentAnswer, String feedback) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.userPrompt = userPrompt;
        this.agentAnswer = agentAnswer;
        this.feedback = feedback;
    }

    public Long getId() { return id; }
    public String getSessionId() { return sessionId; }
    public String getUserId() { return userId; }
    public String getUserPrompt() { return userPrompt; }
    public String getAgentAnswer() { return agentAnswer; }
    public String getFeedback() { return feedback; }
    public Instant getCreatedAt() { return createdAt; }
}
