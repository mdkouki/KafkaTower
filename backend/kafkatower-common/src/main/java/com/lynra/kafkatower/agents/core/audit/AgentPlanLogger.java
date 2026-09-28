package com.lynra.kafkatower.agents.core.audit;

import com.embabel.agent.api.event.AgentProcessEvent;
import com.embabel.agent.api.event.AgentProcessPlanFormulatedEvent;
import com.embabel.agent.api.event.AgenticEventListener;
import com.embabel.plan.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * Logs the plan Embabel's planner formulates for each agent process — which goal it's
 * pursuing and which action(s) it selected to reach it.
 * <p>
 * Registered as a plain Spring {@code @Component}, not attached via
 * {@code ProcessOptions.withListener(...)}. Verified empirically: Embabel dispatches the early
 * process-lifecycle events — {@code AgentProcessCreationEvent}, {@code AgentProcessReadyToPlanEvent},
 * {@code AgentProcessPlanFormulatedEvent} — only to {@code AgenticEventListener} beans in the
 * Spring context (the same mechanism that lets Embabel's own {@code defaultLogger()} bean
 * work without any manual wiring). A listener attached to an
 * individual process via {@code ProcessOptions} only ever sees later, action-execution-scoped
 * events (tool loop, LLM request/response) — it never receives a plan-formulated event no matter
 * how it's wired up, which is why an earlier version of this class that relied on that channel
 * silently never logged anything.
 * <p>
 * Each agent in this app (root and every specialist) exposes exactly one {@code @Action}, so
 * the planner's own plan is always a single step — the interesting reasoning happens inside
 * that action's {@code Ai.withAutoLlm()} tool-calling loop, which {@link ToolCallAuditLogger}
 * already logs call-by-call. This listener exists so the process-level plan is visible too
 * (which agent/action was chosen for which goal, and when).
 */
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
