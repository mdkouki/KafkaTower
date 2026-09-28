package com.lynra.kafkatower.repository;

import com.lynra.kafkatower.model.ChatFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatFeedbackRepository extends JpaRepository<ChatFeedback, Long> {
    List<ChatFeedback> findAllByOrderByCreatedAtDesc();
}
