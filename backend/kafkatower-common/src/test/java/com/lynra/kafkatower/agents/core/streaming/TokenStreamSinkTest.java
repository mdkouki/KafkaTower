package com.lynra.kafkatower.agents.core.streaming;

import com.embabel.agent.api.common.streaming.StreamingPromptRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TokenStreamSinkTest {

    @AfterEach
    void clearSink() {
        TokenStreamSink.clear();
    }

    @Test
    void collectAssemblesFullTextAndForwardsEachTokenToTheRegisteredSink() {
        StreamingPromptRunner.Streaming streaming = mock(StreamingPromptRunner.Streaming.class);
        when(streaming.withPrompt(anyString())).thenReturn(streaming);
        when(streaming.generateStream()).thenReturn(Flux.just("Hello", ", ", "world", "!"));

        List<String> forwarded = new ArrayList<>();
        TokenStreamSink.register(forwarded::add);

        String result = TokenStreamSink.collect(streaming, "say hello");

        assertThat(result).isEqualTo("Hello, world!");
        assertThat(forwarded).containsExactly("Hello", ", ", "world", "!");
    }

    @Test
    void collectStillAssemblesFullTextWhenNoSinkIsRegistered() {
        StreamingPromptRunner.Streaming streaming = mock(StreamingPromptRunner.Streaming.class);
        when(streaming.withPrompt(anyString())).thenReturn(streaming);
        when(streaming.generateStream()).thenReturn(Flux.just("no", " sink", " here"));

        String result = TokenStreamSink.collect(streaming, "anything");

        assertThat(result).isEqualTo("no sink here");
    }

    @Test
    void collectReturnsEmptyStringWhenTheStreamIsEmpty() {
        StreamingPromptRunner.Streaming streaming = mock(StreamingPromptRunner.Streaming.class);
        when(streaming.withPrompt(anyString())).thenReturn(streaming);
        when(streaming.generateStream()).thenReturn(Flux.empty());

        List<String> forwarded = new ArrayList<>();
        TokenStreamSink.register(forwarded::add);

        String result = TokenStreamSink.collect(streaming, "anything");

        assertThat(result).isEmpty();
        assertThat(forwarded).isEmpty();
    }

    @Test
    void collectDoesNotPropagateExceptionsThrownByTheSink() {
        StreamingPromptRunner.Streaming streaming = mock(StreamingPromptRunner.Streaming.class);
        when(streaming.withPrompt(anyString())).thenReturn(streaming);
        when(streaming.generateStream()).thenReturn(Flux.just("a", "b", "c"));

        TokenStreamSink.register(token -> {
            throw new RuntimeException("boom");
        });

        String result = TokenStreamSink.collect(streaming, "anything");

        assertThat(result).isEqualTo("abc");
    }
}
