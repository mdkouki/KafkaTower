package com.lynra.kafkatower.agents.core.audit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.api.tool.callback.AfterToolCallContext;
import com.embabel.agent.api.tool.callback.BeforeToolCallContext;
import com.embabel.chat.ToolCall;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallAuditLoggerTest {

    private final ToolCallAuditLogger logger = ToolCallAuditLogger.INSTANCE;
    private ListAppender<ILoggingEvent> appender;
    private Logger targetLogger;

    @BeforeEach
    void attachAppender() {
        targetLogger = (Logger) LoggerFactory.getLogger(ToolCallAuditLogger.class);
        appender = new ListAppender<>();
        appender.start();
        targetLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        targetLogger.detachAppender(appender);
    }

    @Test
    void beforeToolCallLogsToolNameAndArguments() {
        ToolCall toolCall = new ToolCall("call-1", "listBrokers", "{\"clusterName\":\"prod\"}");

        logger.beforeToolCall(new BeforeToolCallContext(toolCall));

        assertThat(appender.list).hasSize(1);
        String message = appender.list.get(0).getFormattedMessage();
        assertThat(message).contains("listBrokers").contains("clusterName");
    }

    @Test
    void afterToolCallLogsDurationAndResult() {
        ToolCall toolCall = new ToolCall("call-1", "listBrokers", "{}");
        Tool.Result result = Tool.Result.text("2 brokers found");

        logger.afterToolCall(new AfterToolCallContext(toolCall, result, "2 brokers found", 42L));

        assertThat(appender.list).hasSize(1);
        String message = appender.list.get(0).getFormattedMessage();
        assertThat(message).contains("listBrokers").contains("42").contains("2 brokers found");
    }

    @Test
    void afterToolCallTruncatesLongResults() {
        ToolCall toolCall = new ToolCall("call-1", "listTopics", "{}");
        String longResult = "x".repeat(400);
        Tool.Result result = Tool.Result.text(longResult);

        logger.afterToolCall(new AfterToolCallContext(toolCall, result, longResult, 10L));

        String message = appender.list.get(0).getFormattedMessage();
        assertThat(message).contains("...[truncated]");
        assertThat(message).doesNotContain("x".repeat(400));
    }
}
