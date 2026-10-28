package com.lynra.kafkatower.controller;

import com.lynra.kafkatower.chatbot.ChatRequest;
import com.lynra.kafkatower.chatbot.ChatService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    // Absent while the assistant is disabled (no LLM settings saved yet) — see ConditionalOnAssistantEnabled.
    private final ObjectProvider<ChatService> chatServiceProvider;

    public ChatController(ObjectProvider<ChatService> chatServiceProvider) {
        this.chatServiceProvider = chatServiceProvider;
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest request) {
        ChatService chatService = chatServiceProvider.getIfAvailable();
        if (chatService == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The assistant is not configured. An admin can set the LLM in the settings.");
        }
        // Worst case is up to 3 orchestration rounds, each potentially
        // calling several specialist sub-agents (their own multi-turn tool-calling LLM loops)
        // plus a completeness review — comfortably exceeds 120s under load.
        SseEmitter emitter = new SseEmitter(600_000L);
        AtomicReference<Disposable> subscription = new AtomicReference<>();

        emitter.onCompletion(() -> dispose(subscription));
        emitter.onTimeout(() -> {
            emitter.complete();
            dispose(subscription);
        });
        emitter.onError(e -> dispose(subscription));

        Disposable d = chatService.askAgent(request.sessionId(), request.message())
                .filter(chunk -> chunk != null && !chunk.isEmpty())
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        chunk -> {
                            try {
                                emitter.send(SseEmitter.event().data(chunk));
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        },
                        emitter::completeWithError,
                        emitter::complete
                );
        subscription.set(d);
        return emitter;
    }

    private void dispose(AtomicReference<Disposable> ref) {
        Disposable d = ref.get();
        if (d != null && !d.isDisposed()) {
            d.dispose();
        }
    }
}