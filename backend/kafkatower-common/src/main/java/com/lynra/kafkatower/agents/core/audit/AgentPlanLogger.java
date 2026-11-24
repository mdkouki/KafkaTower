package com.lynra.kafkatower.agents.core.audit;

import com.lynra.kafkatower.config.ConditionalOnAssistantEnabled;
import com.embabel.agent.api.event.AgentProcessEvent;
import com.embabel.agent.api.event.AgentProcessPlanFormulatedEvent;
import com.embabel.agent.api.event.AgenticEventListener;
import com.embabel.plan.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/*
 * Registered as a Spring bean because Embabel dispatches early lifecycle events
 * (AgentProcessCreationEvent, AgentProcessPlanFormulatedEvent) only to AgenticEventListener beans;
 * a listener attached via ProcessOptions.withListener(...) never sees them.
 */
@ConditionalOnAssistantEnabled
@Component
public class AgentPlanLogger implements AgenticEventListener {

    private static final Logger log = LoggerFactory.getLogger(AgentPlanLogger.class);

    @Override
    public void onProcessEvent(AgentProcessEvent event) {
        if (event instanceof AgentProcessPlanFormulatedEvent planned) {
            String agentName = planned.getAgentProcess().getAgent().getName();
            String actions = planned.getPlan().getActions().stream()
                    .map(Action::getName)
                    .collect(Collectors.joining(" -> "));
            log.info("plan-formulated agent={} processId={} goal={} actions=[{}]",
                    agentName, planned.getProcessId(), planned.getPlan().getGoal().getName(), actions);
        }
    }
}
