package com.lynra.kafkatower.tools;

import com.embabel.agent.api.annotation.EmbabelComponent;
import com.embabel.agent.api.annotation.LlmTool;
import com.lynra.kafkatower.utils.SkillLoader;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * On-demand lookups for the Kafka investigation/metrics reference material that used to be
 * concatenated wholesale into KafkaInvestigator's and KafkaMetricsInspector's system prompts
 * (~10K tokens of promql-snippets.md + metrics-catalog.md on every single call, regardless of
 * whether the question needed it). Both specialists now load only their small always-relevant
 * core prompt and pull in the rest through these tools, scoped to what the question actually
 * needs — see KafkaInvestigator.loadSkill / KafkaMetricsInspector.loadSkill.
 * <p>
 * Content is loaded once per key at construction (this bean is a singleton) rather than re-read
 * from the classpath on every tool call, since the LLM tool loop can call these repeatedly
 * within — and across — conversations.
 */
@Service
@EmbabelComponent
public class MetricsSkillTools {

    private final Map<String, String> playbooks = load(
            "cluster", "/skills/kafka-investigation/references/playbooks/cluster.md",
            "broker", "/skills/kafka-investigation/references/playbooks/broker.md",
            "topic", "/skills/kafka-investigation/references/playbooks/topic.md",
            "consumer-group", "/skills/kafka-investigation/references/playbooks/consumer-group.md",
            "principal", "/skills/kafka-investigation/references/playbooks/principal.md",
            "failure-patterns", "/skills/kafka-investigation/references/playbooks/failure-patterns.md"
    );

    private final Map<String, String> metricCatalogCategories = load(
            "kminion", "/skills/kafka-investigation/references/metrics-catalog/kminion.md",
            "kafka-core", "/skills/kafka-investigation/references/metrics-catalog/kafka-core.md",
            "kafka-extended", "/skills/kafka-investigation/references/metrics-catalog/kafka-extended.md"
    );

    private static Map<String, String> load(String... keyPathPairs) {
        Map<String, String> content = new LinkedHashMap<>();
        for (int i = 0; i < keyPathPairs.length; i += 2) {
            content.put(keyPathPairs[i], SkillLoader.loadContent(keyPathPairs[i + 1]));
        }
        return content;
    }

    @LlmTool(description = """
            Fetch the step-by-step investigation playbook for one scope: admin calls to make,
            in what order, which metrics to check afterward, and a completion checklist.
            Parameter scope, one of:
            - cluster: cluster-wide health snapshot (broker count, error rate, replication, traffic)
            - broker: a specific broker (config, disk, replication load, saturation, traffic)
            - topic: a specific topic (metadata, config, size, replication health, consumer lag)
            - consumer-group: a specific consumer group (state, membership, lag, offset commits)
            - principal: ACL/auth investigation for a specific principal (ACLs, quotas, auth failures)
            - failure-patterns: runbooks for known failure signatures (protocol errors, lag spikes,
              under-replicated partitions, authorization failures, frequent rebalances) — fetch this
              when the question describes a symptom rather than naming a specific resource to check.
            Fetch only the scope(s) the question actually needs — most questions need exactly one.
            """)
    public String getInvestigationPlaybook(String scope) {
        return lookup(playbooks, scope, "scope");
    }

    @LlmTool(description = """
            Fetch a category of the Kafka metrics catalog — the authoritative list of every
            confirmed metric name available in this VictoriaMetrics environment. Do not use a
            metric name that isn't listed in the catalog; fetch the relevant category first.
            Parameter category, one of:
            - kminion: consumer group lag/state/offsets, topic size/HWM, broker info/disk/traffic,
              cluster info — the most commonly needed category, live-verified
            - kafka-core: error rate (primary error signal), controller state, replica manager,
              partition/ISR topology — reach for this on any health/error investigation
            - kafka-extended: consumer client-side, group coordinator, request handler saturation,
              per-user quotas, per-topic broker usage — less common, check here if kminion/kafka-core
              don't have what you need
            """)
    public String getMetricsCatalog(String category) {
        return lookup(metricCatalogCategories, category, "category");
    }

    private String lookup(Map<String, String> content, String key, String kind) {
        String value = content.get(key);
        if (value == null) {
            return "Unknown " + kind + " '" + key + "'. Valid values: " + String.join(", ", content.keySet());
        }
        return value;
    }
}
