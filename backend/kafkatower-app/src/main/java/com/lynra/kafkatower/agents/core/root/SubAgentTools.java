package com.lynra.kafkatower.agents.core.root;

import com.embabel.agent.api.annotation.EmbabelComponent;
import com.embabel.agent.api.annotation.LlmTool;
import com.embabel.agent.core.Agent;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.ProcessOptions;
import com.lynra.kafkatower.agents.core.audit.AgentResultLogger;
import com.lynra.kafkatower.agents.core.specialist.Specialist;
import com.lynra.kafkatower.agents.core.streaming.TokenStreamSink;
import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Exposes each Kafka specialist sub-agent as an LLM-callable tool for {@link KafkaInspector}.
 * This turns delegation from a fixed classify-then-route-once switch into something the root
 * LLM decides at runtime: it can call zero, one, or several specialists, in whatever order it
 * needs, and feed one specialist's answer into another's question (e.g. narrow a timeframe with
 * {@link #askMetrics} before handing it to {@link #askLogs}).
 * <p>
 * Needs to give this bean per-request state? See {@link EnrichedSubAgentTools} for the pattern.
 */
@Service
@EmbabelComponent
public class SubAgentTools {

    private static final String METRICS = "KafkaMetricsInspector";
    private static final String INVESTIGATION = "KafkaInvestigator";
    private static final String LOG = "KafkaLogInspector";

    private final AgentPlatform agentPlatform;
    private final Map<String, AdminClient> kafkaAdminMap;
    // Keyed by Specialist.name(), which is defined to match the @Agent name Embabel deploys
    // under — the same key requireAgent/findAgent use to resolve the runnable Embabel Agent.
    // Spring supplies exactly the specialists currently deployed: a @ConditionalOnProperty-gated
    // one (e.g. KafkaLogInspector when OpenSearch isn't configured) simply won't be in this map,
    // which is what askLogs' "not available" branch below relies on.
    private final Map<String, Specialist<?, ?>> specialistsByName;

    public SubAgentTools(AgentPlatform agentPlatform,
                          Map<String, AdminClient> kafkaAdminMap, List<Specialist<?, ?>> specialists) {
        this.agentPlatform = agentPlatform;
        this.kafkaAdminMap = kafkaAdminMap;
        this.specialistsByName = specialists.stream()
                .collect(Collectors.toMap(Specialist::name, Function.identity()));
    }

    // Agent deployment into AgentPlatform happens on ContextRefreshedEvent, after every
    // singleton bean (including this one) is already constructed — so agents cannot be
    // resolved eagerly in a @PostConstruct. Resolve them lazily on each call instead.
    private Agent findAgent(String name) {
        return agentPlatform.agents().stream()
                .filter(a -> a.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    private Agent requireAgent(String name) {
        Agent agent = findAgent(name);
        if (agent == null) {
            throw new IllegalStateException("Sub-agent not deployed: " + name);
        }
        return agent;
    }

    @LlmTool(description = """
            List every Kafka cluster this assistant can actually use (the live AdminClient
            connections needed by askInvestigation and every other specialist).

            ALWAYS call this before calling any ask* tool if you are not 100% certain of the exact
            cluster name — do not guess, invent, or assume a cluster name from the user's wording.
            Also call it if a specialist's answer says a cluster was "not found": that usually means
            the name was slightly wrong, not that no clusters exist.

            Parameter query: optional. Pass whatever cluster name/fragment the user mentioned (e.g.
            "prod", "the nonprod one") and matches are ranked by similarity — this resolves typos,
            partial names, and abbreviations (e.g. "prod" -> "nonprod-01") so you don't have to ask
            the user to repeat themselves or silently pick the wrong cluster. Leave empty (or pass
            an empty string) to just list every cluster.
            """)
    public String listClusters(String query) {
        Set<String> liveClusters = kafkaAdminMap.keySet();

        if (liveClusters.isEmpty()) {
            return "No Kafka clusters are configured. There is nothing to query.";
        }

        List<String> ranked = (query == null || query.isBlank())
                ? liveClusters.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList()
                : rankByMatch(liveClusters, query);

        String header = (query == null || query.isBlank())
                ? "Available Kafka clusters (" + ranked.size() + "):\n"
                : "Clusters ranked by similarity to '" + query + "':\n";

        return header + ranked.stream()
                .map(name -> "  - '" + name + "'")
                .collect(Collectors.joining("\n"));
    }

    private List<String> rankByMatch(Set<String> names, String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        return names.stream()
                .sorted(Comparator.comparingInt(name -> matchScore(name.toLowerCase(Locale.ROOT), q)))
                .toList();
    }

    /** Lower is better: 0 = exact, then substring matches, then edit-distance. */
    private int matchScore(String name, String query) {
        if (name.equals(query)) return 0;
        // Require a minimum length before treating containment as a strong match — otherwise a
        // query like "nonprod-01-eu" ranks a cluster literally named "p" as a near-exact match.
        if (name.length() >= 3 && (name.contains(query) || query.contains(name))) return 1;
        return 2 + levenshtein(name, query);
    }

    private int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[b.length()];
    }

    @LlmTool(description = """
            Ask the metrics specialist about HISTORICAL TIME-SERIES Kafka data: throughput/lag
            trends, capacity analysis, or comparisons over an explicit time window. Also use this
            to narrow down a vague or wide-open timeframe (e.g. find exactly when a lag spike or
            throughput drop started) before handing a precise window to another specialist.
            Parameter question: a fully self-contained question including any known time window.
            This specialist has no memory of anything another specialist already found this turn —
            if you already called askInvestigation or askLogs, restate whatever they established
            that's relevant here instead of just repeating the user's original question verbatim.
            """)
    public String askMetrics(String question) {
        return invoke(requireSpecialist(METRICS), question);
    }

    @LlmTool(description = """
            Ask the live-investigation specialist about CURRENT Kafka state and root-cause
            analysis: consumer group health, partition assignments, active lag spikes, broker/ACL
            state. Use for troubleshooting happening right now, or to correlate live state with a
            metrics trend.
            Parameter question: a fully self-contained question — include any timeframe or metric
            findings from other specialists that are relevant. This specialist independently
            discovers and queries metrics itself (Admin First, Metrics Second) — if askMetrics
            already ran this turn, say what it found (including "no data" / "no such metric
            exists") so this specialist doesn't re-run the same discovery and the same PromQL
            queries from scratch. Do not just pass the user's original question through unchanged
            if another specialist has already been called this turn.
            """)
    public String askInvestigation(String question) {
        return invoke(requireSpecialist(INVESTIGATION), question);
    }

    @LlmTool(description = """
            Ask the log specialist to search application/service logs and traces for exceptions,
            errors, or anomalies. Only available when OpenSearch is configured. Works best with a
            narrow, specific time window — if the user gave a large or open-ended timeframe, call
            askMetrics first to narrow it down to when the anomaly actually occurred.
            Parameter question: a fully self-contained question including the (ideally narrow)
            time window and any service/topic names to search for.
            """)
    public String askLogs(String question) {
        Specialist<?, ?> specialist = specialistsByName.get(LOG);
        if (specialist == null) {
            return "Log inspection is not available: no OpenSearch clusters are configured " +
                    "(agent.mcp.os.enabled / agent.mcp.os.clusters).";
        }
        return invoke(specialist, question);
    }

    private Specialist<?, ?> requireSpecialist(String name) {
        Specialist<?, ?> specialist = specialistsByName.get(name);
        if (specialist == null) {
            throw new IllegalStateException("Specialist not deployed: " + name);
        }
        return specialist;
    }

    private String invoke(Specialist<?, ?> specialist, String question) {
        return invokeTyped(specialist, question);
    }

    private <Q, A> String invokeTyped(Specialist<Q, A> specialist, String question) {
        Agent agent = requireAgent(specialist.name());
        return run(agent, specialist.question(question), specialist.answerType(), specialist::text);
    }

    private <Q, A> String run(Agent agent, Q question, Class<A> answerType, Function<A, String> textOf) {
        // ProcessOptions.DEFAULT — no per-process listener needed here. AgentStatusEventListener
        // and AgentPlanLogger are Spring beans that see every process platform-wide already (see
        // their Javadoc); AgentStatusEventListener scopes itself to the current chat request via
        // a ThreadLocal, which this sub-process creation naturally participates in since run()
        // executes synchronously on the calling thread. Never let the specialist's own token
        // stream reach the browser directly, though: its raw answer is not the synthesized
        // response the user should see. TokenStreamSink.suppressed keeps full-text collection
        // working for the specialist's own TokenStreamSink.collect() calls while dropping the
        // per-token forwarding.
        return TokenStreamSink.suppressed(() -> {
            AgentProcess subProcess = agentPlatform.createAgentProcessFrom(agent, ProcessOptions.DEFAULT, question);
            subProcess.run();
            try {
                // resultOfType throws rather than returning null when the process didn't
                // complete or didn't produce answerType — AgentResultLogger logs the actual
                // (opaque, otherwise-invisible) blackboard content before rethrowing.
                A answer = AgentResultLogger.resultOfType(subProcess, answerType, agent.getName());
                String text = textOf.apply(answer);
                // Embabel's tool-result message rejects empty text outright (Text content
                // cannot be empty) — a specialist producing a blank answer must still surface
                // something so the tool loop can continue instead of crashing the whole turn.
                if (text == null || text.isBlank()) {
                    return "No answer produced by " + agent.getName() + " (see server logs for details).";
                }
                return text;
            } catch (RuntimeException e) {
                return "No answer produced by " + agent.getName() + " (see server logs for details).";
            }
        });
    }
}
