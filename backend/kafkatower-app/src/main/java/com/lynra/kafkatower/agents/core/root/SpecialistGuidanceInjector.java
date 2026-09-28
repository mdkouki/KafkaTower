package com.lynra.kafkatower.agents.core.root;

import com.lynra.kafkatower.agents.core.specialist.Specialist;
import com.lynra.kafkatower.utils.SkillLoader;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Pushes the shared, cross-specialist behavioral guidance (tool-output handling, query
 * strategy, reply format, VictoriaMetrics query guardrails — see
 * kafkatower-common/src/main/resources/skills/shared/) into every deployed {@link Specialist}.
 * <p>
 * This lives in kafkatower-app rather than in each specialist module on purpose: the shared
 * guidance controls how specialists are allowed to behave, and that's an orchestration-layer
 * decision, not something each domain module should assemble for itself. A specialist only
 * loads its own domain skill doc; it never reads {@code skills/shared/*.md} directly.
 * <p>
 * Which shared docs go to which specialist, and in what order, is domain knowledge that used to
 * live inside each specialist's own {@code loadSkill()} — preserved here verbatim so behavior is
 * unchanged, just relocated to the one place that's allowed to know about every specialist at
 * once.
 * <p>
 * Wired entirely through constructor injection, not an event listener: Spring must fully build
 * every {@code List<Specialist<?, ?>>} element — including its own {@code @PostConstruct} that
 * sets its base prompt — before it can hand this constructor the list, so there's no ordering
 * race to guard against here.
 */
@Component
public class SpecialistGuidanceInjector {

    private static final Map<String, List<String>> SHARED_SKILLS_BY_SPECIALIST = Map.of(
            "KafkaInvestigator", List.of(
                    "/skills/shared/UNTRUSTED_TOOL_OUTPUT.md",
                    "/skills/shared/VM_QUERY_GUARDRAILS.md",
                    "/skills/shared/QUERY_STRATEGY.md",
                    "/skills/shared/SPECIALIST_REPLY_FORMAT.md"),
            "KafkaLogInspector", List.of(
                    "/skills/shared/UNTRUSTED_TOOL_OUTPUT.md",
                    "/skills/shared/QUERY_STRATEGY.md",
                    "/skills/shared/SPECIALIST_REPLY_FORMAT.md"),
            "KafkaMetricsInspector", List.of(
                    "/skills/shared/VM_QUERY_GUARDRAILS.md",
                    "/skills/shared/QUERY_STRATEGY.md",
                    "/skills/shared/SPECIALIST_REPLY_FORMAT.md")
    );

    public SpecialistGuidanceInjector(List<Specialist<?, ?>> specialists) {
        for (Specialist<?, ?> specialist : specialists) {
            List<String> paths = SHARED_SKILLS_BY_SPECIALIST.get(specialist.name());
            if (paths == null || paths.isEmpty()) {
                continue;
            }
            String guidance = paths.stream()
                    .map(SkillLoader::loadContent)
                    .collect(Collectors.joining("\n\n"));
            specialist.applySharedGuidance(guidance);
        }
    }
}
