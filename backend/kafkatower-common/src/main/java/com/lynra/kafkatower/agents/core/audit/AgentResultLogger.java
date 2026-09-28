package com.lynra.kafkatower.agents.core.audit;

import com.embabel.agent.core.AgentProcess;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * {@link AgentProcess#resultOfType(Class)} does not return {@code null} when a process didn't
 * produce the expected type — it throws ({@code IllegalArgumentException} if the process didn't
 * complete, {@code IllegalStateException} if it completed without a value of that type on the
 * blackboard), and neither exception message includes what actually IS on the blackboard. Left
 * uncaught (or caught and discarded, as the "answer != null" checks in this codebase used to
 * assume could happen), the caller sees only a generic exception or a static fallback string —
 * an opaque failure with no way to tell what the agent actually produced instead.
 * <p>
 * {@link #resultOfType} wraps the call so that failure logs the full blackboard content —
 * every object actually produced, serialized where possible — before rethrowing, so the
 * opaque object is visible in the logs even though it never reaches the caller.
 */
public final class AgentResultLogger {

    private static final Logger log = LoggerFactory.getLogger(AgentResultLogger.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentResultLogger() {
    }

    /**
     * @param label a short identifier for what's being extracted (e.g. the agent name), used to
     *              tell which call failed when several processes run in the same request
     */
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

    /** Serializes {@code o} for logging so the actual content is visible, not just its class/hash. */
    private static String describe(Object o) {
        if (o == null) {
            return "null";
        }
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            // Not Jackson-serializable (cyclic refs, no accessors, etc.) — toString() is still
            // more than the default Object@hash a bare log of the reference would give if the
            // type overrides it (Java records and most Kotlin data classes do).
            return String.valueOf(o);
        }
    }
}
