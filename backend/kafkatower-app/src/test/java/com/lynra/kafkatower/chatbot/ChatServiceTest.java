package com.lynra.kafkatower.chatbot;

import com.embabel.agent.core.Agent;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.AgentProcess;
import com.lynra.kafkatower.agents.core.root.AgentAnswer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatServiceTest {

    private ChatService chatService;
    private AgentPlatform agentPlatform;
    private AgentProcess agentProcess;

    @BeforeEach
    void setUp() {
        agentPlatform = mock(AgentPlatform.class);
        agentProcess = mock(AgentProcess.class);

        Agent inspectorAgent = mock(Agent.class);
        when(inspectorAgent.getName()).thenReturn("KafkaInspector");
        when(agentPlatform.agents()).thenReturn(List.of(inspectorAgent));
        when(agentPlatform.createAgentProcessFrom(any(), any(), any(Object[].class)))
                .thenReturn(agentProcess);
        when(agentProcess.run()).thenReturn(agentProcess);
        when(agentProcess.resultOfType(AgentAnswer.class))
                .thenReturn(new AgentAnswer("Test answer"));

        chatService = new ChatService(agentPlatform);
        chatService.init();
    }

    @Test
    void testChatServiceConstructor() {
        assertNotNull(chatService);
    }

    @Test
    void testAskAgentWithValidInput() {
        Flux<String> result = chatService.askAgent("session-123", "Hello, how can you help?");
        assertNotNull(result);
    }

    @Test
    void testAskAgentWithEmptyMessage() {
        Flux<String> result = chatService.askAgent("session-123", "");
        assertNotNull(result);
    }

    @Test
    void testAskAgentWithDifferentSessions() {
        Flux<String> result1 = chatService.askAgent("session-1", "test message");
        Flux<String> result2 = chatService.askAgent("session-2", "test message");
        assertNotNull(result1);
        assertNotNull(result2);
    }

    @Test
    void testAskAgentEmitsAnswer() {
        Flux<String> result = chatService.askAgent("session-123", "test");
        String first = result.blockFirst();
        assertNotNull(first);
        assertFalse(first.isEmpty());
    }

    @Test
    void testAskAgentWithNullMessage() {
        Flux<String> result = chatService.askAgent("session-123", null);
        assertNotNull(result);
    }

    @Test
    void testAskAgentWithLongMessage() {
        Flux<String> result = chatService.askAgent("session-123", "x".repeat(10000));
        assertNotNull(result);
    }

    @Test
    void testAskAgentReturnType() {
        Flux<String> result = chatService.askAgent("session-123", "test");
        assertNotNull(result);
        assertTrue(result instanceof Flux);
    }
}
