package com.lynra.kafkatower.agents.core.root;

import com.lynra.kafkatower.config.ConditionalOnAssistantEnabled;
import com.lynra.kafkatower.agents.core.specialist.Specialist;
import com.lynra.kafkatower.utils.SkillLoader;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// Lives in kafkatower-app rather than in each specialist module on purpose: the shared guidance controls
// how specialists may behave, which is an orchestration-layer decision. A specialist only loads its own
// domain skill doc.
// Wired through the constructor, not an event listener: Spring fully builds every Specialist (including
// its @PostConstruct that sets the base prompt) before it can hand over the list, so there is no ordering race.
@ConditionalOnAssistantEnabled
@Component
public class SpecialistGuidanceInjector {

    private static final Map<String, List<String>> SHARED_SKILLS_BY_SPECIALIST = Map.of(
            "KafkaInvestigator", List.of(
                    "/skills/shared/UNTRUSTED_TOOL_OUTPUT.md",
                    "/skills/shared/METRICS_QUERY_GUARDRAILS.md",
                    "/skills/shared/QUERY_STRATEGY.md",
                    "/skills/shared/SPECIALIST_REPLY_FORMAT.md"),
            "KafkaLogInspector", List.of(
                    "/skills/shared/UNTRUSTED_TOOL_OUTPUT.md",
                    "/skills/shared/QUERY_STRATEGY.md",
                    "/skills/shared/SPECIALIST_REPLY_FORMAT.md"),
            "KafkaRegistryInspector", List.of(
                    "/skills/shared/UNTRUSTED_TOOL_OUTPUT.md",
                    "/skills/shared/QUERY_STRATEGY.md",
                    "/skills/shared/SPECIALIST_REPLY_FORMAT.md"),
            "KafkaMetricsInspector", List.of(
                    "/skills/shared/METRICS_QUERY_GUARDRAILS.md",
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
