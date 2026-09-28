package com.lynra.kafkatower.goap;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;

/**
 * Companion spike to {@link GoapSpikeAgent}: {@code finish}'s precondition, {@code
 * "neverProduced"}, is never declared as a {@code post} by any action or {@code @Condition} in
 * this class — a dangling reference no action could ever satisfy, not merely one that isn't
 * satisfied yet.
 */
@Agent(description = "Structural-rejection spike — every action references a condition no action ever produces")
public class MalformedGoapSpikeAgent {

    public record Seed(int start) {}

    public record Result(int value) {}

    @Action(description = "Seed the value")
    public Result begin(Seed seed) {
        return new Result(seed.start());
    }

    @AchievesGoal(description = "Finish once the dangling condition holds")
    @Action(pre = "neverProduced", description = "Unreachable by construction")
    public Result finish(Result result) {
        return result;
    }
}
