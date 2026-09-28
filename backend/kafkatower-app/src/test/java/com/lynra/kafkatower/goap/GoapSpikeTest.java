package com.lynra.kafkatower.goap;

import com.embabel.agent.api.annotation.support.AgentMetadataReader;
import com.embabel.agent.core.Agent;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.AgentProcessStatusCode;
import com.embabel.agent.core.AgentScope;
import com.embabel.agent.core.ProcessOptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 0.5 spike (EMBABEL_GOAP_ALIGNMENT_PLAN.md) — boots a package-isolated Spring context
 * (see {@link GoapSpikeTestApplication}) with a dummy LLM key, since {@link GoapSpikeAgent}
 * never calls an LLM, purely to get a fully-wired {@link AgentMetadataReader}/
 * {@link AgentPlatform} via Embabel's auto-configuration, deploys the throwaway spike agent,
 * and asserts on the planner's actual behavior. Not testing any production agent.
 */
@SpringBootTest(classes = GoapSpikeTestApplication.class,
        properties = "ECGPT_API_KEY=test-key-not-used-no-llm-calls-in-this-spike")
class GoapSpikeTest {

    @Autowired
    private AgentMetadataReader agentMetadataReader;

    @Autowired
    private AgentPlatform agentPlatform;

    @Test
    void plannerGatesOnConditionAndPrefersHigherValueGoal() {
        AgentScope scope = agentMetadataReader.createAgentScopes(new GoapSpikeAgent()).get(0);
        Agent agent = scope instanceof Agent a ? a : scope.createAgent("goap-spike", "test", "0.0.1");
        agentPlatform.deploy(agent);

        AgentProcess process = agentPlatform.createAgentProcessFrom(
                agent, ProcessOptions.DEFAULT, new GoapSpikeAgent.Seed(0));
        process.run();

        GoapSpikeAgent.Result result = process.resultOfType(GoapSpikeAgent.Result.class);
        assertThat(result).as("planner should reach a goal at all").isNotNull();

        // The real question this spike exists to answer: does the higher-value goal
        // (finishAtTwo, value=5.0, reachable at count>=2) win over the lower-value one
        // (finishAtThree, value=1.0, reachable at count>=3) once both become reachable —
        // i.e. does the planner stop incrementing as soon as the cheaper/higher-value path
        // opens up, rather than always driving to the "last" precondition to become true?
        assertThat(result.reachedVia()).isEqualTo("two");
        assertThat(result.finalValue()).isEqualTo(2);
    }

    /**
     * A goal gated on a dangling condition (see {@link MalformedGoapSpikeAgent}) deploys without
     * complaint — Embabel validates the action graph at plan time, not at deploy time. So
     * {@code process.run()} completes without throwing, but the process is left {@code STUCK}
     * rather than {@code COMPLETED}; see EMBABEL_GOAP_ALIGNMENT_PLAN.md for the full trail.
     */
    @Test
    void structurallyUnreachableGoalLeavesProcessStuckAtRunTime() {
        AgentScope scope = agentMetadataReader.createAgentScopes(new MalformedGoapSpikeAgent()).get(0);
        Agent agent = scope instanceof Agent a ? a : scope.createAgent("malformed-goap-spike", "test", "0.0.1");
        agentPlatform.deploy(agent);

        AgentProcess process = agentPlatform.createAgentProcessFrom(
                agent, ProcessOptions.DEFAULT, new MalformedGoapSpikeAgent.Seed(0));
        process.run();

        assertThat(process.getStatus()).isEqualTo(AgentProcessStatusCode.STUCK);
    }
}
