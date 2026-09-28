package com.lynra.kafkatower.controller;

import com.lynra.kafkatower.chatbot.FeedbackRequest;
import com.lynra.kafkatower.model.ChatFeedback;
import com.lynra.kafkatower.repository.ChatFeedbackRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/feedback")
public class ChatFeedbackController {

    private final ChatFeedbackRepository repository;

    public ChatFeedbackController(ChatFeedbackRepository repository) {
        this.repository = repository;
    }

    @PostMapping
    public ResponseEntity<Void> submit(@RequestBody FeedbackRequest req, Authentication auth) {
        if (req.feedback() == null || (!req.feedback().equals("UP") && !req.feedback().equals("DOWN"))) {
            return ResponseEntity.badRequest().build();
        }
        String userId = auth != null ? auth.getName() : "anonymous";
        repository.save(new ChatFeedback(
                req.sessionId(), userId, req.userPrompt(), req.agentAnswer(), req.feedback()));
        return ResponseEntity.ok().build();
    }

    @GetMapping
    public List<ChatFeedback> list() {
        return repository.findAllByOrderByCreatedAtDesc();
    }
}
