package com.lynra.kafkatower.agents.core.audit;

import com.embabel.agent.core.AgentProcess;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/*
 * AgentProcess.resultOfType throws (without saying what IS on the blackboard) when the process
 * didn't produce the expected type; this wrapper logs the blackboard content before rethrowing.
 */
public final class AgentResultLogger {

    private static final Logger log = LoggerFactory.getLogger(AgentResultLogger.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentResultLogger() {
    }

    public static <A> A resultOfType(AgentProcess process, Class<A> type, String label) {
        try {
            return process.resultOfType(type);
        } catch (RuntimeException e) {
            log.warn("{}: expected a {} but got {} (status={}). Failure info: {}. Blackboard contains {} object(s): {}",
                    label, type.getSimpleName(), e.getClass().getSimpleName(), process.getStatus(),
                    describe(process.getFailureInfo()),
                    process.getBlackboard().getObjects().size(), describeBlackboard(process), e);
            throw e;
        }
    }

    private static String describeBlackboard(AgentProcess process) {
        List<Object> objects = process.getBlackboard().getObjects();
        if (objects.isEmpty()) {
            return "(empty)";
        }
        return objects.stream()
                .map(o -> o.getClass().getName() + "=" + describe(o))
                .collect(Collectors.joining(" | "));
    }

    private static String describe(Object o) {
        if (o == null) {
            return "null";
        }
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            // Not Jackson-serializable (cyclic refs, no accessors): fall back to toString().
            return String.valueOf(o);
        }
    }
}
