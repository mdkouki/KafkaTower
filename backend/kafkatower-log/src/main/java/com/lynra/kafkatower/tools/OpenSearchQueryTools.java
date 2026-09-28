package com.lynra.kafkatower.tools;

import com.embabel.agent.api.annotation.EmbabelComponent;
import com.embabel.agent.api.annotation.LlmTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lynra.kafkatower.agents.specialists.config.OpenSearchClientProvider;
import com.lynra.kafkatower.agents.specialists.config.OpenSearchClientRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Queries OpenSearch directly over its REST API, authenticating with HTTP basic auth
 * (login/password) — used instead of OpenSearch's native MCP server because the OpenSearch
 * version deployed here does not support it. Each Kafka cluster has its own OpenSearch
 * connection (see {@link OpenSearchClientRegistry}), targeting the index configured for
 * that cluster unless the caller names a different one.
 *
 * <p>Exposes three tools split by shape of the work, so the LLM reaches for the cheapest
 * one that answers the question rather than always paying for a full document search:
 * {@code search_documents} (raw document hits), {@code aggregate_documents} (buckets/counts
 * only, hits always suppressed), and {@code get_document} (a single document by id).
 */
@Service
@EmbabelComponent
// See KafkaLogInspector for why this uses the deterministic property condition rather than
// @ConditionalOnBean on a regular scanned @Component.
@ConditionalOnProperty(prefix = "agent.mcp.os", name = "enabled", havingValue = "true")
public class OpenSearchQueryTools {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchQueryTools.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OpenSearchClientRegistry registry;

    public OpenSearchQueryTools(OpenSearchClientRegistry registry) {
        this.registry = registry;
    }

    private OpenSearchClientProvider provider(String clusterName) {
        return registry.forCluster(clusterName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown OpenSearch cluster: " + clusterName));
    }

    private String resolveIndex(OpenSearchClientProvider provider, String index) {
        if (index != null && !index.isBlank()) return index;
        if (provider.defaultIndex() != null && !provider.defaultIndex().isBlank()) return provider.defaultIndex();
        throw new IllegalArgumentException("No index specified and no default index configured for cluster '" +
                provider.clusterName() + "'.");
    }

    @LlmTool(name = "search_documents", description = """
            Run an OpenSearch Query DSL search against an index and return matching documents.
            Use this only when you need the actual document contents (log lines, event bodies).
            For counts, histograms, or bucketed breakdowns use aggregate_documents instead — it's
            cheaper. For a single document whose _id you already know, use get_document instead.
            Parameter clusterName: name of the Kafka cluster.
            Parameter index: index name to search; pass empty string to use the cluster's default index.
            Parameter queryJson: the full OpenSearch request body as JSON.
            """)
    public String searchDocuments(String clusterName, String index, String queryJson) {
        OpenSearchClientProvider provider;
        String target;
        try {
            provider = provider(clusterName);
            target = resolveIndex(provider, index);
        } catch (Exception e) {
            log.error("searchDocuments rejected before dispatch for cluster {}: {}", clusterName, e.getMessage());
            return "Error: " + e.getMessage();
        }

        log.info("searchDocuments OS query cluster={} index={} query={}", clusterName, target, queryJson);
        try {
            String responseBody = provider.post("/" + target + "/_search", queryJson);
            log.info("searchDocuments OS result cluster={} index={} status=ok {}",
                    clusterName, target, describeResult(responseBody));
            return slimForLlm(responseBody);
        } catch (Exception e) {
            log.error("searchDocuments OS result cluster={} index={} status=failed error={}",
                    clusterName, target, e.getMessage(), e);
            return "Error: " + e.getMessage();
        }
    }

    @LlmTool(name = "aggregate_documents", description = """
            Run an OpenSearch aggregation query against an index and return only the
            aggregation buckets and the total matching document count — no raw document
            hits, so this is far cheaper than search_documents for counts, date histograms,
            terms breakdowns, or narrowing a time window before a detailed search.
            Parameter clusterName: name of the Kafka cluster.
            Parameter index: index name to search; pass empty string to use the cluster's default index.
            Parameter queryJson: the OpenSearch request body as JSON, including an "aggs" clause
              (and optionally a "query"/filter clause). Any "size" in the body is ignored — hits
              are always suppressed.
            """)
    public String aggregateDocuments(String clusterName, String index, String queryJson) {
        OpenSearchClientProvider provider;
        String target;
        try {
            provider = provider(clusterName);
            target = resolveIndex(provider, index);
        } catch (Exception e) {
            log.error("aggregateDocuments rejected before dispatch for cluster {}: {}", clusterName, e.getMessage());
            return "Error: " + e.getMessage();
        }

        String body;
        try {
            body = forceNoHits(queryJson);
        } catch (Exception e) {
            log.error("aggregateDocuments rejected malformed queryJson for cluster {}: {}", clusterName, e.getMessage());
            return "Error: invalid queryJson: " + e.getMessage();
        }

        log.info("aggregateDocuments OS query cluster={} index={} query={}", clusterName, target, body);
        try {
            String responseBody = provider.post("/" + target + "/_search", body);
            log.info("aggregateDocuments OS result cluster={} index={} status=ok {}",
                    clusterName, target, describeResult(responseBody));
            return slimAggregationForLlm(responseBody);
        } catch (Exception e) {
            log.error("aggregateDocuments OS result cluster={} index={} status=failed error={}",
                    clusterName, target, e.getMessage(), e);
            return "Error: " + e.getMessage();
        }
    }

    @LlmTool(name = "get_document", description = """
            Fetch a single document by its OpenSearch _id and return its _source. Use this
            instead of search_documents when you already know the exact document id (e.g. from
            a prior search result or a trace/event id mentioned in the question).
            Parameter clusterName: name of the Kafka cluster.
            Parameter index: index name the document lives in; pass empty string to use the
              cluster's default index.
            Parameter documentId: the document's _id.
            """)
    public String getDocument(String clusterName, String index, String documentId) {
        OpenSearchClientProvider provider;
        String target;
        try {
            provider = provider(clusterName);
            target = resolveIndex(provider, index);
        } catch (Exception e) {
            log.error("getDocument rejected before dispatch for cluster {}: {}", clusterName, e.getMessage());
            return "Error: " + e.getMessage();
        }

        log.info("getDocument OS lookup cluster={} index={} id={}", clusterName, target, documentId);
        try {
            String responseBody = provider.get("/" + target + "/_doc/" + documentId);
            log.info("getDocument OS result cluster={} index={} id={} status=ok", clusterName, target, documentId);
            return slimDocumentForLlm(responseBody);
        } catch (Exception e) {
            log.error("getDocument OS result cluster={} index={} id={} status=failed error={}",
                    clusterName, target, documentId, e.getMessage(), e);
            return "Error: " + e.getMessage();
        }
    }

    /** Forces size:0 (hits suppressed) on a caller-supplied aggregation request body. */
    private static String forceNoHits(String queryJson) throws Exception {
        JsonNode root = MAPPER.readTree(queryJson);
        if (!root.isObject()) {
            throw new IllegalArgumentException("queryJson must be a JSON object");
        }
        var obj = (com.fasterxml.jackson.databind.node.ObjectNode) root;
        obj.put("size", 0);
        return MAPPER.writeValueAsString(obj);
    }

    /** Keeps only aggregations and the total hit count — no per-document hits. */
    private static String slimAggregationForLlm(String responseBody) {
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            if (!root.isObject()) return responseBody;
            var out = MAPPER.createObjectNode();
            out.set("totalHits", root.path("hits").path("total"));
            if (root.has("aggregations")) {
                out.set("aggregations", root.path("aggregations"));
            }
            return MAPPER.writeValueAsString(out);
        } catch (Exception e) {
            return responseBody;
        }
    }

    /** Keeps only _source from a GET /{index}/_doc/{id} response. */
    private static String slimDocumentForLlm(String responseBody) {
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            if (!root.isObject() || !root.has("_source")) return responseBody;
            return MAPPER.writeValueAsString(root.path("_source"));
        } catch (Exception e) {
            return responseBody;
        }
    }

    /**
     * Strips OpenSearch response envelope fields the LLM never needs (`took`, `timed_out`,
     * `_shards`, and per-hit `_index`/`_type`/`_score`) before the response is handed back to
     * the model. This is pure boilerplate on every call — cutting it leaves more of the
     * {@link com.lynra.kafkatower.utils.ToolResponseTruncator} 8k-char budget for actual
     * `_source` content and aggregation buckets. Falls back to the untouched body if the
     * response isn't the JSON shape we expect (e.g. an OpenSearch error body).
     */
    private static String slimForLlm(String responseBody) {
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            if (!root.isObject()) return responseBody;
            var obj = (com.fasterxml.jackson.databind.node.ObjectNode) root;
            obj.remove("took");
            obj.remove("timed_out");
            obj.remove("_shards");
            JsonNode hits = obj.path("hits").path("hits");
            if (hits.isArray()) {
                for (JsonNode hit : hits) {
                    if (hit.isObject()) {
                        var hitObj = (com.fasterxml.jackson.databind.node.ObjectNode) hit;
                        hitObj.remove("_index");
                        hitObj.remove("_type");
                        hitObj.remove("_score");
                    }
                }
            }
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return responseBody;
        }
    }

    /** Summarizes a raw OpenSearch _search response for logging: hit count and whether aggregations were returned. */
    private static String describeResult(String responseBody) {
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            JsonNode totalNode = root.path("hits").path("total");
            long total = totalNode.isObject() ? totalNode.path("value").asLong(-1) : totalNode.asLong(-1);
            int returnedHits = root.path("hits").path("hits").size();
            boolean hasAggs = root.has("aggregations") && root.path("aggregations").size() > 0;
            boolean empty = total == 0 && returnedHits == 0 && !hasAggs;
            return "empty=" + empty + " totalHits=" + total + " returnedHits=" + returnedHits + " hasAggregations=" + hasAggs;
        } catch (Exception parseError) {
            return "empty=unknown (failed to parse response: " + parseError.getMessage() + ")";
        }
    }
}
