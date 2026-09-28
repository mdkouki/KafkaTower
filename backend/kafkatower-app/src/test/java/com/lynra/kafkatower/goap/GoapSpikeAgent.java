package com.lynra.kafkatower.goap;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Condition;

/**
 * Phase 0.5 mechanism spike (EMBABEL_GOAP_ALIGNMENT_PLAN.md) — a throwaway, no-LLM, no-I/O
 * agent used only to empirically verify three things before any production agent is touched:
 * <ol>
 *   <li>{@code @Condition} + {@code @Action(pre = ...)} actually gates scheduling.</li>
 *   <li>Two competing {@code @AchievesGoal} actions producing the same output type resolve by
 *       {@code value()}, not by "whichever precondition is satisfiable first".</li>
 *   <li>{@code canRerun()} drives a bounded loop (increment) to termination.</li>
 * </ol>
 * Not wired into the production Spring context as a scanned bean — instantiated directly in
 * {@link GoapSpikeTest} and handed to {@code AgentMetadataReader} so it never appears in the
 * real application's agent registry.
 */
@Agent(description = "Phase 0.5 GOAP mechanism spike — not a production agent")
public class GoapSpikeAgent {

    public record Seed(int start) {}

    public record Counter(int value) {}

    public record Result(String reachedVia, int finalValue) {}

    @Action(post = "belowLimit", description = "Seed the counter from the initial input")
    public Counter begin(Seed seed) {
        return new Counter(seed.start());
    }

    @Condition(name = "belowLimit")
    public boolean belowLimit(Counter counter) {
        return counter.value() < 3;
    }

    @Condition(name = "reachedTwo")
    public boolean reachedTwo(Counter counter) {
        return counter.value() >= 2;
    }

    @Condition(name = "reachedThree")
    public boolean reachedThree(Counter counter) {
        return counter.value() >= 3;
    }

    // Deliberately consumes and produces the same type (Counter -> Counter) — this is exactly
    // the shape the plan flagged as a possible DuplicateParameterTypeException risk for Phase
    // 4's refineAnswer(Draft...) -> Draft. Finding out here, cheaply, whether it compiles/plans
    // at all is one of this spike's jobs.
    @Action(pre = "belowLimit", post = {"belowLimit", "reachedTwo", "reachedThree"},
            canRerun = true, description = "Increment the counter by one")
    public Counter increment(Counter counter) {
        return new Counter(counter.value() + 1);
    }

    // Lower value, reachable later (count >= 3) — should lose the tie-break to finishAtTwo
    // below once count reaches 2, if @AchievesGoal.value() actually drives goal selection.
    @AchievesGoal(description = "Finished once the counter reaches three", value = 1.0)
    @Action(pre = "reachedThree", description = "Finish at three")
    public Result finishAtThree(Counter counter) {
        return new Result("three", counter.value());
    }

    // Higher value, reachable earlier (count >= 2) — expected winner if value() is respected.
    @AchievesGoal(description = "Finished once the counter reaches two", value = 5.0)
    @Action(pre = "reachedTwo", description = "Finish at two")
    public Result finishAtTwo(Counter counter) {
        return new Result("two", counter.value());
    }
}
