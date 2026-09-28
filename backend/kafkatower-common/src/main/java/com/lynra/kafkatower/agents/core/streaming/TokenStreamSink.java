package com.lynra.kafkatower.agents.core.streaming;

import com.embabel.agent.api.common.streaming.StreamingPromptRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Side-channel that lets a leaf agent forward LLM tokens to the chat SSE response as they
 * are generated, independent of the {@code @Action} method's typed return value.
 * <p>
 * {@link com.embabel.agent.core.AgentProcess#run()} executes an agent's actions synchronously
 * on the calling thread, so a {@link ChatSink} is registered per-request via a {@link ThreadLocal}
 * before {@code run()} is invoked (see {@code ChatService.askAgent}) and every leaf agent that
 * streams its answer through {@link #collect} on that same thread reaches the same sink.
 * If no sink is registered (e.g. a test, or code invoked outside the chat flow), streaming is
 * skipped and the full text is simply collected and returned.
 */
public final class TokenStreamSink {

    private static final Logger log = LoggerFactory.getLogger(TokenStreamSink.class);
    private static final ThreadLocal<ChatSink> CURRENT = new ThreadLocal<>();
    // Defaults to forwarding; set false only for the scope of a suppressed(...) call.
    private static final ThreadLocal<Boolean> ENABLED = ThreadLocal.withInitial(() -> true);

    private TokenStreamSink() {
    }

    /** A per-request callback that receives each token as it is generated. */
    public interface ChatSink extends Consumer<String> {
    }

    public static void register(ChatSink sink) {
        CURRENT.set(sink);
    }

    public static void clear() {
        CURRENT.remove();
        ENABLED.remove();
    }

    /**
     * Runs {@code body} with token forwarding disabled on this thread, so any {@link #collect}
     * call inside it still assembles and returns its full text but does not push tokens to the
     * registered {@link ChatSink}. Used to keep sub-agent and intermediate-orchestration-round
     * output from streaming to the browser raw and out of order — see
     * {@code SubAgentTools.run} and {@code KafkaInspector.answer}.
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

    /**
     * Streams {@code prompt} through {@code streaming}, forwarding each token to the
     * thread's registered {@link ChatSink} (if any and if not currently {@link #suppressed}),
     * and returns the fully assembled text.
     */
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
