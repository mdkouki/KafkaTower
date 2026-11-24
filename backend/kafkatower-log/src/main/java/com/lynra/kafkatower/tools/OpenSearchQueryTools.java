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
import com.lynra.kafkatower.collections.CollectionPresenter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.lynra.kafkatower.utils.CachedTool;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    private static final String TOTAL_HITS_SEARCH_LABEL = "Total hits";
    private static final String TOTAL_HITS_AGGREGATION_LABEL = "totalHits";
    private static final List<String> HIT_METADATA_FIELDS = List.of("_index", "_type", "_score");

    private final OpenSearchClientRegistry registry;
    private final CollectionPresenter presenter;

    public OpenSearchQueryTools(OpenSearchClientRegistry registry, CollectionPresenter presenter) {
        this.registry = registry;
        this.presenter = presenter;
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
            The response is text, not a raw JSON envelope: a "Total hits" line, then the returned
            documents as one compact JSON row each. More than 5 documents come back as a count,
            a snippet of the first 5 and a collection key to pass to bulkOperation to count,
            filter or group them.
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
            return presentSearchResult(responseBody);
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
            The response is text, not a raw JSON envelope: a "totalHits" line, then each
            aggregation. Buckets are listed per aggregation as one compact JSON row each;
            more than 5 buckets come back as a count, a snippet of the first 5 and a
            collection key to pass to bulkOperation to count, filter or group them.
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
            return presentAggregations(responseBody);
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

    private String presentAggregations(String responseBody) {
        Optional<JsonNode> parsed = parseObject(responseBody);
        if (parsed.isEmpty()) return responseBody;
        JsonNode root = parsed.get();
        StringBuilder text = new StringBuilder(TOTAL_HITS_AGGREGATION_LABEL).append(": ")
                .append(totalHits(root)).append('\n');
        root.path("aggregations").properties().forEach(aggregation -> text.append(presentAggregation(aggregation)));
        return text.toString();
    }

    private String presentAggregation(Map.Entry<String, JsonNode> aggregation) {
        JsonNode buckets = aggregation.getValue().path("buckets");
        if (!buckets.isArray()) {
            return aggregation.getKey() + ": " + aggregation.getValue() + "\n";
        }
        return presenter.block("Aggregation " + aggregation.getKey(), toRows(buckets));
    }

    private static List<String> toRows(JsonNode array) {
        List<String> rows = new ArrayList<>();
        array.forEach(element -> rows.add(element.toString()));
        return rows;
    }

    private static Optional<JsonNode> parseObject(String responseBody) {
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            return root != null && root.isObject() ? Optional.of(root) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String totalHits(JsonNode root) {
        JsonNode total = root.path("hits").path("total");
        return total.isObject() ? total.path("value").asText() : total.asText();
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

    private String presentSearchResult(String responseBody) {
        Optional<JsonNode> parsed = parseObject(responseBody);
        if (parsed.isEmpty()) return responseBody;
        JsonNode root = parsed.get();
        JsonNode hits = root.path("hits").path("hits");
        if (!hits.isArray()) return responseBody;
        hits.forEach(OpenSearchQueryTools::removeHitMetadata);
        return TOTAL_HITS_SEARCH_LABEL + ": " + totalHits(root) + "\n" + presenter.block("Documents", toRows(hits));
    }

    private static void removeHitMetadata(JsonNode hit) {
        if (hit.isObject()) {
            ((ObjectNode) hit).remove(HIT_METADATA_FIELDS);
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
