package com.lynra.kafkatower.agents.specialists.config;

import com.lynra.kafkatower.utils.ToolTruncationAdvice;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class PromQlTruncationAdvice implements ToolTruncationAdvice {

    private static final String ADVICE = "Narrow your query — use an instant query "
            + "instead of a range, add a coarser step, a shorter time window, or a more specific "
            + "series filter.";

    @Override
    public Set<String> toolNames() {
        return PromQlQueryTools.TOOL_NAMES;
    }

    @Override
    public String advice() {
        return ADVICE;
    }
}
