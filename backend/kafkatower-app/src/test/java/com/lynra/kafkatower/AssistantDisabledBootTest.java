package com.lynra.kafkatower;

import com.lynra.kafkatower.controller.ChatController;
import com.lynra.kafkatower.chatbot.ChatRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots the real context against an empty database — no LLM settings saved yet — and checks the
 * console still starts, with Embabel and the assistant switched off.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:assistantdisabledbootdb;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.flyway.enabled=false"
        })
class AssistantDisabledBootTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ChatController chatController;

    @Test
    void startsWithoutAnLlmAndWithoutTheAssistantBeans() {
        assertThat(context.getEnvironment().getProperty("kafkatower.assistant.enabled")).isEqualTo("false");
        assertThat(context.containsBean("chatService")).isFalse();
        assertThat(context.containsBean("kafkaInspector")).isFalse();
        assertThat(context.containsBean("subAgentTools")).isFalse();
        assertThat(context.containsBean("asyncPromptClassifier")).isFalse();
        assertThat(context.getBeanNamesForType(com.embabel.agent.core.AgentPlatform.class)).isEmpty();
    }

    @Test
    void chatAnswersServiceUnavailableInsteadOfFailing() {
        assertThatThrownBy(() -> chatController.chat(new ChatRequest("s1", "hello")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
