package com.lynra.kafkatower.tools;

import com.embabel.agent.api.annotation.EmbabelComponent;
import com.embabel.agent.api.annotation.LlmTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lynra.kafkatower.agents.specialists.config.OpenSearchClientProvider;
import com.lynra.kafkatower.agents.specialists.config.OpenSearchClientRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.lynra.kafkatower.utils.CachedTool;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@EmbabelComponent
// See KafkaLogInspector for why this uses the deterministic property condition rather than
// @ConditionalOnBean on a regular scanned @Component.
// Talks to the REST API directly (basic auth) because the deployed OpenSearch version has no native MCP server.
@ConditionalOnProperty(prefix = "agent.mcp.os", name = "enabled", havingValue = "true")
public class OpenSearchQueryTools {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchQueryTools.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ERROR_PREFIX = "Error: ";

    private final OpenSearchClientRegistry registry;

    public OpenSearchQueryTools(OpenSearchClientRegistry registry) {
        this.registry = registry;
    }

    private OpenSearchClientProvider provider(String clusterName) {
        return registry.forCluster(clusterName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown OpenSearch cluster: " + clusterName));
    }

    private Target resolveTarget(String clusterName, String index) {
        OpenSearchClientProvider provider = provider(clusterName);
        return new Target(provider, resolveIndex(provider, index));
    }

    private String resolveIndex(OpenSearchClientProvider provider, String index) {
        if (StringUtils.hasText(index)) return index;
        if (StringUtils.hasText(provider.defaultIndex())) return provider.defaultIndex();
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
    @CachedTool(ttlSeconds = 20)
    public String searchDocuments(String clusterName, String index, String queryJson) {
        Target resolved;
        try {
            resolved = resolveTarget(clusterName, index);
        } catch (Exception e) {
            log.error("searchDocuments rejected before dispatch for cluster {}: {}", clusterName, e.getMessage());
            return ERROR_PREFIX + e.getMessage();
        }
        OpenSearchClientProvider provider = resolved.provider();
        String target = resolved.index();

        log.info("searchDocuments OS query cluster={} index={} query={}", clusterName, target, queryJson);
        try {
            String responseBody = provider.post(searchPath(target), queryJson);
            log.info("searchDocuments OS result cluster={} index={} status=ok {}",
                    clusterName, target, describeResult(responseBody));
            return slimForLlm(responseBody);
        } catch (Exception e) {
            log.error("searchDocuments OS result cluster={} index={} status=failed error={}",
                    clusterName, target, e.getMessage(), e);
            return ERROR_PREFIX + e.getMessage();
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
    @CachedTool(ttlSeconds = 20)
    public String aggregateDocuments(String clusterName, String index, String queryJson) {
        Target resolved;
        try {
            resolved = resolveTarget(clusterName, index);
        } catch (Exception e) {
            log.error("aggregateDocuments rejected before dispatch for cluster {}: {}", clusterName, e.getMessage());
            return ERROR_PREFIX + e.getMessage();
        }
        OpenSearchClientProvider provider = resolved.provider();
        String target = resolved.index();

        String body;
        try {
            body = forceNoHits(queryJson);
        } catch (Exception e) {
            log.error("aggregateDocuments rejected malformed queryJson for cluster {}: {}", clusterName, e.getMessage());
            return ERROR_PREFIX + "invalid queryJson: " + e.getMessage();
        }

        log.info("aggregateDocuments OS query cluster={} index={} query={}", clusterName, target, body);
        try {
            String responseBody = provider.post(searchPath(target), body);
            log.info("aggregateDocuments OS result cluster={} index={} status=ok {}",
                    clusterName, target, describeResult(responseBody));
            return slimAggregationForLlm(responseBody);
        } catch (Exception e) {
            log.error("aggregateDocuments OS result cluster={} index={} status=failed error={}",
                    clusterName, target, e.getMessage(), e);
            return ERROR_PREFIX + e.getMessage();
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
    @CachedTool(ttlSeconds = 30)
    public String getDocument(String clusterName, String index, String documentId) {
        Target resolved;
        try {
            resolved = resolveTarget(clusterName, index);
        } catch (Exception e) {
            log.error("getDocument rejected before dispatch for cluster {}: {}", clusterName, e.getMessage());
            return ERROR_PREFIX + e.getMessage();
        }
        OpenSearchClientProvider provider = resolved.provider();
        String target = resolved.index();

        log.info("getDocument OS lookup cluster={} index={} id={}", clusterName, target, documentId);
        try {
            String responseBody = provider.get("/" + target + "/_doc/" + documentId);
            log.info("getDocument OS result cluster={} index={} id={} status=ok", clusterName, target, documentId);
            return slimDocumentForLlm(responseBody);
        } catch (Exception e) {
            log.error("getDocument OS result cluster={} index={} id={} status=failed error={}",
                    clusterName, target, documentId, e.getMessage(), e);
            return ERROR_PREFIX + e.getMessage();
        }
    }

    private static String searchPath(String index) {
        return "/" + index + "/_search";
    }

    private static String forceNoHits(String queryJson) throws Exception {
        JsonNode root = MAPPER.readTree(queryJson);
        if (!root.isObject()) {
            throw new IllegalArgumentException("queryJson must be a JSON object");
        }
        var obj = (ObjectNode) root;
        obj.put("size", 0);
        return MAPPER.writeValueAsString(obj);
    }

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

    private static String slimDocumentForLlm(String responseBody) {
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            if (!root.isObject() || !root.has("_source")) return responseBody;
            return MAPPER.writeValueAsString(root.path("_source"));
        } catch (Exception e) {
            return responseBody;
        }
    }

    // Dropping the envelope fields leaves more of the ToolResponseTruncator 8k-char budget for _source
    // content and aggregation buckets; non-JSON bodies (e.g. OpenSearch errors) are returned untouched.
    private static String slimForLlm(String responseBody) {
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            if (!root.isObject()) return responseBody;
            var obj = (ObjectNode) root;
            obj.remove("took");
            obj.remove("timed_out");
            obj.remove("_shards");
            JsonNode hits = obj.path("hits").path("hits");
            if (hits.isArray()) {
                for (JsonNode hit : hits) {
                    if (hit.isObject()) {
                        var hitObj = (ObjectNode) hit;
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

    private record Target(OpenSearchClientProvider provider, String index) {}
}
