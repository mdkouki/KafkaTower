package com.lynra.kafkatower.agents.core.streaming;

import com.embabel.agent.api.common.streaming.StreamingPromptRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;
import java.util.function.Supplier;

/*
 * Side-channel forwarding LLM tokens to the chat SSE response. AgentProcess.run() executes actions
 * synchronously on the calling thread, so a per-request ThreadLocal sink registered before run()
 * is reached by every leaf agent streaming through collect() on that thread. Without a sink the
 * full text is just collected and returned.
 */
public final class TokenStreamSink {

    private static final Logger log = LoggerFactory.getLogger(TokenStreamSink.class);
    private static final ThreadLocal<ChatSink> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> ENABLED = ThreadLocal.withInitial(() -> true);

    private TokenStreamSink() {
    }

    public interface ChatSink extends Consumer<String> {
    }

    public static void register(ChatSink sink) {
        CURRENT.set(sink);
    }

    public static void clear() {
        CURRENT.remove();
        ENABLED.remove();
    }

    /*
     * Keeps sub-agent and intermediate-orchestration-round output from streaming raw and out of
     * order to the browser (see SubAgentTools.run and KafkaInspector.answer).
     */
    public static <T> T suppressed(Supplier<T> body) {
        boolean previous = ENABLED.get();
        ENABLED.set(false);
        try {
            return body.get();
        } finally {
            ENABLED.set(previous);
        }
    }

    public static String collect(StreamingPromptRunner.Streaming streaming, String prompt) {
        ChatSink sink = ENABLED.get() ? CURRENT.get() : null;
        StringBuilder full = new StringBuilder();
        streaming.withPrompt(prompt)
                .generateStream()
                .doOnNext(token -> {
                    full.append(token);
                    if (sink != null) {
                        try {
                            sink.accept(token);
                        } catch (Exception e) {
                            log.warn("Error forwarding streamed token to chat sink: {}", e.getMessage());
                        }
                    }
                })
                .blockLast();
        return full.toString();
    }
}
