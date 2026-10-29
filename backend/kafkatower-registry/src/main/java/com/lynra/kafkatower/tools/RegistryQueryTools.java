package com.lynra.kafkatower.tools;

import com.embabel.agent.api.annotation.EmbabelComponent;
import com.embabel.agent.api.annotation.LlmTool;
import com.lynra.kafkatower.config.ConditionalOnAssistantEnabled;
import com.lynra.kafkatower.model.AclGroupGrant;
import com.lynra.kafkatower.model.ClusterAclGraph;
import com.lynra.kafkatower.model.ClusterTopicGraph;
import com.lynra.kafkatower.model.PrincipalDependencies;
import com.lynra.kafkatower.model.TopicDetail;
import com.lynra.kafkatower.model.UserAclSummary;
import com.lynra.kafkatower.service.AclGraphStore;
import com.lynra.kafkatower.service.PrincipalDependencyBuilder;
import com.lynra.kafkatower.service.TopicGraphStore;
import com.lynra.kafkatower.service.TopicGroupLagService;
import com.lynra.kafkatower.utils.CachedTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read-only LLM tools over the registry: the in-memory topic and ACL graphs that
 * {@code TopicGraphRefreshJob}/{@code AclGraphRefreshJob} keep per cluster. They answer
 * "what depends on what" and "who uses what" from ACL grants (what a principal is <em>allowed</em>
 * to do); only {@link #topicConsumerGroups} touches the live brokers. ACL resource names ending
 * in {@code *} come from PREFIXED bindings.
 */
@Service
@EmbabelComponent
@ConditionalOnAssistantEnabled
public class RegistryQueryTools {

    private static final Logger log = LoggerFactory.getLogger(RegistryQueryTools.class);
    private static final int MAX_ROWS = 100;

    private final TopicGraphStore topicStore;
    private final AclGraphStore aclStore;
    private final TopicGroupLagService groupLags;

    public RegistryQueryTools(TopicGraphStore topicStore, AclGraphStore aclStore, TopicGroupLagService groupLags) {
        this.topicStore = topicStore;
        this.aclStore = aclStore;
        this.groupLags = groupLags;
    }

    @LlmTool(description = """
            Find topics in the registry whose name starts with a prefix, with how many principals
            produce to and consume from each. A non-empty prefix is REQUIRED.
            Parameter clusterName: name of the Kafka cluster.
            Parameter prefix: required, non-empty topic-name prefix.
            """)
    public String findTopics(String clusterName, String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "A non-empty prefix is required — pass the topic-name prefix the question is about.";
        }
        return withTopicGraph(clusterName, graph -> {
            List<TopicDetail> matches = graph.topicsByName().values().stream()
                    .filter(t -> t.name().startsWith(prefix))
                    .sorted(Comparator.comparing(TopicDetail::name))
                    .toList();
            if (matches.isEmpty()) return "No topics start with '" + prefix + "' in cluster '" + clusterName + "'.";
            StringBuilder sb = new StringBuilder("Topics starting with '").append(prefix).append("' in '")
                    .append(clusterName).append("' (").append(matches.size()).append("):\n");
            matches.stream().limit(MAX_ROWS).forEach(t -> sb.append("  ").append(t.name())
                    .append("  producers=").append(t.producers().size())
                    .append(" consumers=").append(t.consumers().size()).append("\n"));
            if (matches.size() > MAX_ROWS) sb.append("  ... ").append(matches.size() - MAX_ROWS)
                    .append(" more — use a longer prefix.\n");
            return sb.toString();
        });
    }

    @LlmTool(description = """
            Who uses a topic: the principals ACL-allowed to produce to it and to consume from it
            (including PREFIXED grants covering it), plus partition count and replication factor.
            This is permission-based, not proof of live traffic — use topicConsumerGroups for live
            consumers. Parameter clusterName: name of the Kafka cluster. Parameter topic: exact topic name.
            """)
    public String whoUsesTopic(String clusterName, String topic) {
        return withTopicGraph(clusterName, graph -> {
            TopicDetail t = graph.topicsByName().get(topic);
            if (t == null) return "Topic '" + topic + "' is not in the registry for cluster '" + clusterName
                    + "'. Use findTopics with a prefix to find the exact name.";
            return "Topic '" + t.name() + "' on '" + clusterName + "': " + t.partitionCount() + " partitions, RF "
                    + t.replicationFactor() + (t.internal() ? ", internal" : "") + "\n"
                    + "  producers (" + t.producers().size() + "): " + joined(t.producers()) + "\n"
                    + "  consumers (" + t.consumers().size() + "): " + joined(t.consumers()) + "\n";
        });
    }

    @LlmTool(description = """
            LIVE consumer groups currently subscribed to or holding committed offsets on a topic,
            with state and lag. Queries the brokers (slow on clusters with many groups) — call it
            only when the question is about actual consumption, not just who is permitted.
            Parameter clusterName: name of the Kafka cluster. Parameter topic: exact topic name.
            """)
    @CachedTool(ttlSeconds = 30)
    public String topicConsumerGroups(String clusterName, String topic) {
        try {
            List<String> rows = groupLags.groupsForTopic(clusterName, topic).stream()
                    .map(g -> "  " + g.groupId() + "  state=" + g.state() + "  lag=" + g.lag())
                    .toList();
            if (rows.isEmpty()) return "No consumer group has offsets on '" + topic + "' in cluster '" + clusterName + "'.";
            return "Consumer groups on '" + topic + "' (" + rows.size() + "):\n" + String.join("\n", rows) + "\n";
        } catch (Exception e) {
            log.error("topicConsumerGroups failed for {} / {}", clusterName, topic, e);
            return "Error during operation: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    @LlmTool(description = """
            Find principals (users / service accounts) in the ACL registry whose name contains a
            text fragment, case-insensitive. Use it to resolve the exact principal name before
            describePrincipal or principalDependencies. Parameter clusterName: name of the Kafka
            cluster. Parameter query: text fragment, e.g. "order".
            """)
    public String findPrincipals(String clusterName, String query) {
        return withAclGraph(clusterName, graph -> {
            String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
            List<String> matches = graph.usersByPrincipal().keySet().stream()
                    .filter(p -> p.toLowerCase(Locale.ROOT).contains(q))
                    .sorted().toList();
            if (matches.isEmpty()) return "No principal matches '" + query + "' in cluster '" + clusterName + "'.";
            String more = matches.size() > MAX_ROWS ? "\n  ... " + (matches.size() - MAX_ROWS) + " more — narrow the query." : "";
            return "Principals matching '" + query + "' (" + matches.size() + "):\n  "
                    + matches.stream().limit(MAX_ROWS).collect(Collectors.joining("\n  ")) + more + "\n";
        });
    }

    @LlmTool(description = """
            What one principal is ACL-allowed to do: topics it produces to, topics it consumes from,
            consumer-group grants and transactional IDs. Names ending in * are PREFIXED grants.
            Parameter clusterName: name of the Kafka cluster. Parameter principal: exact principal
            name as returned by findPrincipals (e.g. "User:order-service").
            """)
    public String describePrincipal(String clusterName, String principal) {
        return withAclGraph(clusterName, graph -> {
            UserAclSummary u = graph.usersByPrincipal().get(principal);
            if (u == null) return unknownPrincipal(clusterName, principal);
            return "Principal '" + u.principal() + "' on '" + clusterName + "':\n"
                    + "  produces to (" + u.produceTopics().size() + "): " + joined(u.produceTopics()) + "\n"
                    + "  consumes from (" + u.consumeTopics().size() + "): " + joined(u.consumeTopics()) + "\n"
                    + "  consumer groups: " + joined(u.consumerGroups().stream().map(RegistryQueryTools::groupGrant).toList()) + "\n"
                    + "  transactional IDs: " + joined(u.transactionalIds()) + "\n";
        });
    }

    @LlmTool(description = """
            Direct (depth-1) dependencies of a principal derived from topic ACLs: UPSTREAM principals
            produce to topics this principal consumes; DOWNSTREAM principals consume topics this
            principal produces. Call it again on a neighbour to walk further. Parameter clusterName:
            name of the Kafka cluster. Parameter principal: exact principal name from findPrincipals.
            """)
    public String principalDependencies(String clusterName, String principal) {
        return withAclGraph(clusterName, graph -> {
            PrincipalDependencies deps = PrincipalDependencyBuilder.build(graph, principal);
            if (deps == null) return unknownPrincipal(clusterName, principal);
            StringBuilder sb = new StringBuilder("Dependencies of '").append(principal).append("' on '")
                    .append(clusterName).append("':\n");
            appendRelated(sb, "upstream (produce to topics it consumes)", deps.upstream());
            appendRelated(sb, "downstream (consume topics it produces)", deps.downstream());
            return sb.toString();
        });
    }

    private static void appendRelated(StringBuilder sb, String label, List<PrincipalDependencies.Related> related) {
        sb.append("  ").append(label).append(" (").append(related.size()).append("):\n");
        related.forEach(r -> sb.append("    ").append(r.principal()).append(" via ").append(joined(r.topics())).append("\n"));
    }

    private String withTopicGraph(String clusterName, Function<ClusterTopicGraph, String> body) {
        Optional<ClusterTopicGraph> graph = topicStore.get(clusterName);
        if (graph.isEmpty()) return noGraph(clusterName, topicStore.clusterNames());
        if (!graph.get().isHealthy()) return "The topic registry for '" + clusterName + "' failed its last refresh: " + graph.get().error();
        return body.apply(graph.get());
    }

    private String withAclGraph(String clusterName, Function<ClusterAclGraph, String> body) {
        Optional<ClusterAclGraph> graph = aclStore.get(clusterName);
        if (graph.isEmpty()) return noGraph(clusterName, aclStore.clusterNames());
        if (!graph.get().isHealthy()) return "The ACL registry for '" + clusterName + "' failed its last refresh: " + graph.get().error();
        return body.apply(graph.get());
    }

    private static String noGraph(String clusterName, List<String> known) {
        return "The registry has not been built for cluster '" + clusterName + "' (registry clusters: "
                + (known.isEmpty() ? "none yet" : String.join(", ", known)) + ").";
    }

    private static String unknownPrincipal(String clusterName, String principal) {
        return "Principal '" + principal + "' is not in the ACL registry for cluster '" + clusterName
                + "'. Use findPrincipals to find the exact name (e.g. 'User:name').";
    }

    private static String groupGrant(AclGroupGrant g) {
        return g.prefixed() ? g.pattern() + "*" : g.pattern();
    }

    private static String joined(List<String> values) {
        return values.isEmpty() ? "(none)" : values.stream().sorted().collect(Collectors.joining(", "));
    }
}
