package com.lynra.kafkatower.agents.core.root;

import com.lynra.kafkatower.config.ConditionalOnAssistantEnabled;
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
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Exposes each Kafka specialist sub-agent as an LLM-callable tool for {@link KafkaInspector}.
 * This turns delegation from a fixed classify-then-route-once switch into something the root
 * LLM decides at runtime: it can call zero, one, or several specialists, in whatever order it
 * needs, and feed one specialist's answer into another's question (e.g. narrow a timeframe with
 * askMetrics before handing it to askLogs). The specialist tools themselves are built from
 * {@link #specialists()} — see {@link EnrichedSubAgentTools#specialistTools()}.
 * <p>
 * Needs to give this bean per-request state? See {@link EnrichedSubAgentTools} for the pattern.
 */
@ConditionalOnAssistantEnabled
@Service
@EmbabelComponent
public class SubAgentTools {

    private static final String QUESTION_GUIDANCE = """
            Parameter question: a fully self-contained question. Findings from any specialist already \
            called this conversation are automatically prepended for you — no need to paste them in \
            yourself. If those findings and this specialist's own result genuinely disagree, it is \
            expected to flag the disagreement, not silently pick one.""";

    private final AgentPlatform agentPlatform;
    private final Map<String, AdminClient> kafkaAdminMap;
    // Spring supplies exactly the specialists currently deployed: a @ConditionalOnProperty-gated
    // one (e.g. KafkaLogInspector when OpenSearch isn't configured) simply isn't in this list,
    // so it never becomes a tool and the model can't call it. Sorted once so the tool order and
    // the prompt's tool list are stable. Specialist.name() matches the @Agent name Embabel
    // deploys under, which is how requireAgent resolves the runnable Embabel Agent.
    private final List<Specialist<?, ?>> specialists;

    public SubAgentTools(AgentPlatform agentPlatform,
                          Map<String, AdminClient> kafkaAdminMap, List<Specialist<?, ?>> specialists) {
        this.agentPlatform = agentPlatform;
        this.kafkaAdminMap = kafkaAdminMap;
        this.specialists = specialists.stream().sorted(Comparator.comparing(Specialist::toolName)).toList();
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

    /** Every specialist Spring deployed; the orchestrator exposes each one as an LLM tool. */
    public List<Specialist<?, ?>> specialists() {
        return specialists;
    }

    /** The tool names of {@link #specialists()}, for telling specialist findings apart from other tool output. */
    public SortedSet<String> specialistToolNames() {
        return specialists.stream().map(Specialist::toolName).collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * What the root LLM sees as the tool's description: the specialist's own {@code @Agent}
     * description, so it is written once, plus how to phrase the {@code question}.
     */
    public String toolDescription(Specialist<?, ?> specialist) {
        return requireAgent(specialist.name()).getDescription() + "\n\n" + QUESTION_GUIDANCE;
    }

    /** Runs {@code specialist} as an Embabel sub-process and returns its answer text. */
    public <Q, A> String ask(Specialist<Q, A> specialist, String question) {
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
