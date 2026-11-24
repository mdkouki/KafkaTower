package com.lynra.kafkatower.tools;

import com.embabel.agent.api.annotation.EmbabelComponent;
import com.embabel.agent.api.annotation.LlmTool;
import com.lynra.kafkatower.collections.CollectionPresenter;
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
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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

    private final TopicGraphStore topicStore;
    private final AclGraphStore aclStore;
    private final TopicGroupLagService groupLags;
    private final CollectionPresenter presenter;

    public RegistryQueryTools(TopicGraphStore topicStore, AclGraphStore aclStore, TopicGroupLagService groupLags,
                              CollectionPresenter presenter) {
        this.topicStore = topicStore;
        this.aclStore = aclStore;
        this.groupLags = groupLags;
        this.presenter = presenter;
    }

    @LlmTool(description = """
            Find topics in the registry whose name matches a regex, with how many principals
            produce to and consume from each. A non-empty regex is REQUIRED.
            The regex is a Java regular expression applied unanchored (it matches anywhere in the
            name); anchor it yourself when needed, e.g. "^orders\\." for a prefix, "\\.dlq$" for a
            suffix, "^(orders|payments)\\..*" for several prefixes.
            Lists longer than 5 come back as a count, a 5-item snippet and a collection key for bulkOperation.
            Parameter clusterName: name of the Kafka cluster.
            Parameter regex: required, non-empty Java regex matched against topic names.
            """)
    public String findTopics(String clusterName, String regex) {
        if (regex == null || regex.isBlank()) {
            return "A non-empty regex is required — pass the topic-name regex the question is about.";
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            return "Invalid regex '" + regex + "': " + e.getDescription();
        }
        return withTopicGraph(clusterName, graph -> {
            List<TopicDetail> matches = graph.topicsByName().values().stream()
                    .filter(t -> pattern.matcher(t.name()).find())
                    .sorted(Comparator.comparing(TopicDetail::name))
                    .toList();
            if (matches.isEmpty()) return "No topics match regex '" + regex + "' in cluster '" + clusterName + "'.";
            List<String> rows = matches.stream()
                    .map(t -> t.name() + "  producers=" + t.producers().size() + " consumers=" + t.consumers().size())
                    .toList();
            return presenter.block("Topics matching regex '" + regex + "' in '" + clusterName + "'", rows);
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
                    + "'. Use findTopics with a regex to find the exact name.";
            return "Topic '" + t.name() + "' on '" + clusterName + "': " + t.partitionCount() + " partitions, RF "
                    + t.replicationFactor() + (t.internal() ? ", internal" : "") + "\n"
                    + presenter.inline("  producers", sorted(t.producers())) + "\n"
                    + presenter.inline("  consumers", sorted(t.consumers())) + "\n";
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
                    .map(g -> g.groupId() + "  state=" + g.state() + "  lag=" + g.lag())
                    .toList();
            if (rows.isEmpty()) return "No consumer group has offsets on '" + topic + "' in cluster '" + clusterName + "'.";
            return presenter.block("Consumer groups on '" + topic + "'", rows);
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
            return presenter.block("Principals matching '" + query + "' in '" + clusterName + "'", matches);
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
            List<String> groupGrants = u.consumerGroups().stream().map(RegistryQueryTools::groupGrant).toList();
            return "Principal '" + u.principal() + "' on '" + clusterName + "':\n"
                    + presenter.inline("produces to", sorted(u.produceTopics())) + "\n"
                    + presenter.inline("consumes from", sorted(u.consumeTopics())) + "\n"
                    + presenter.inline("consumer groups", sorted(groupGrants)) + "\n"
                    + presenter.inline("transactional IDs", sorted(u.transactionalIds())) + "\n";
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
            return "Dependencies of '" + principal + "' on '" + clusterName + "':\n"
                    + related("upstream (produce to topics it consumes)", deps.upstream())
                    + related("downstream (consume topics it produces)", deps.downstream());
        });
    }

    private String related(String label, List<PrincipalDependencies.Related> related) {
        List<String> rows = related.stream()
                .map(r -> r.principal() + " via " + String.join(", ", sorted(r.topics())))
                .toList();
        return presenter.block(label, rows);
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

    private static List<String> sorted(List<String> values) {
        return values.stream().sorted().toList();
    }
}
