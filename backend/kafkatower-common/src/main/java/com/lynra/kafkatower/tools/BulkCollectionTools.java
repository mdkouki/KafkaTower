package com.lynra.kafkatower.tools;

import com.embabel.agent.api.annotation.EmbabelComponent;
import com.embabel.agent.api.annotation.LlmTool;
import com.lynra.kafkatower.collections.CollectionPresenter;
import com.lynra.kafkatower.collections.CollectionStore;
import com.lynra.kafkatower.collections.bulk.BulkArguments;
import com.lynra.kafkatower.collections.bulk.BulkOperation;
import com.lynra.kafkatower.collections.bulk.BulkOperationException;
import com.lynra.kafkatower.collections.bulk.BulkOperationRegistry;
import com.lynra.kafkatower.collections.bulk.BulkResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lets the LLM work on a large list a tool parked in the {@link CollectionStore}, without the list
 * itself ever entering its context.
 */
@Service
@EmbabelComponent
public class BulkCollectionTools {

    private final CollectionStore store;
    private final CollectionPresenter presenter;
    private final BulkOperationRegistry operations;

    public BulkCollectionTools(CollectionStore store, CollectionPresenter presenter, BulkOperationRegistry operations) {
        this.store = store;
        this.presenter = presenter;
        this.operations = operations;
    }

    @LlmTool(name = CollectionPresenter.BULK_TOOL_NAME, description = """
            Work on a large list that another tool did not print in full. Such a tool shows only the
            first few items plus a "collection key"; pass that key here to count, filter, group, sort,
            map or page through the whole list. Results of more than a few items are again stored and
            come back as a count, a snippet and a NEW key, so keep chaining operations on the newest
            key until the answer is small. Collections expire after a few minutes; if a key is
            reported unknown or expired, call the original tool again.
            Parameter collectionKey: the key printed by the tool that returned the list.
            Parameter operation: one of
              count         - number of items. With an expression (Java regex), the number of items
                              matching it, e.g. "^fulfillment\\." counts the items starting with fulfillment.
              all           - the ENTIRE list, every item, with no snippet. Use it only when the
                              answer really needs every item; prefer count, filter or group_by when
                              they answer the question. Refused above a size limit (chunk or filter
                              first); output past ~8000 characters is cut off, so for very long rows
                              read chunk by chunk instead. No other arguments.
              slice         - a page of items. expression: start offset, 0-based (default 0);
                              parameter: how many items (default 5).
              chunk         - divide the list into consecutive chunks. expression: items per chunk.
                              Each chunk gets its own key.
              filter        - keep items matching a regex. expression: Java regex, matched anywhere
                              in the item (use (?i) for case-insensitive).
              exclude       - drop items matching a regex. expression: Java regex.
              filter_number - keep items whose number passes a comparison. expression: regex with ONE
                              capture group around the number, e.g. "lag=(\\d+)"; parameter: operator
                              and value, e.g. "gt 1000" (gt, gte, lt, lte, eq, ne).
              sort          - order items. expression: optional regex whose first capture group is the
                              sort key (default: the whole item; numeric when every key is a number);
                              parameter: "asc" (default) or "desc".
              distinct      - remove duplicate items. No other arguments.
              group_by      - group items by a key. expression: regex; the first capture group (or the
                              whole match) is the group name. Returns one entry per group, largest
                              first, each with its own key.
              map           - rewrite every item. expression: Java regex; parameter: replacement,
                              where $1 refers to capture group 1, e.g. expression "^(\\S+) .*$",
                              parameter "$1" keeps the first word.
              flat_map      - split every item into several items. expression: regex delimiter, e.g.
                              ",\\s*"; the parts of all items are concatenated.
              aggregate     - arithmetic over a number in each item. expression: regex with ONE capture
                              group around the number; parameter: sum, min, max or avg.
            Parameter expression: first argument of the operation, as described above; empty when unused.
            Parameter parameter: second argument of the operation, as described above; empty when unused.
            """)
    public String bulkOperation(String collectionKey, String operation, String expression, String parameter) {
        List<String> items = store.find(collectionKey).orElse(null);
        if (items == null) {
            return "Error: collection key '" + collectionKey + "' is unknown or has expired. "
                    + "Call the tool that produced the list again to get a fresh key.";
        }
        BulkOperation bulkOperation = operations.find(operation).orElse(null);
        if (bulkOperation == null) {
            return "Error: unknown operation '" + operation + "'. Available: " + String.join(", ", operations.names()) + ".";
        }
        try {
            BulkArguments arguments = new BulkArguments(expression, parameter);
            bulkOperation.rejectUnusedArguments(arguments);
            BulkResult result = bulkOperation.apply(items, arguments);
            return present(bulkOperation.name(), result);
        } catch (BulkOperationException e) {
            return "Error: " + e.getMessage();
        }
    }

    private String present(String operation, BulkResult result) {
        return switch (result) {
            case BulkResult.Value value -> value.text();
            case BulkResult.Items items -> presenter.block("Result of " + operation, items.items());
            case BulkResult.Everything everything -> presenter.completeBlock("Result of " + operation, everything.items());
            case BulkResult.Groups groups -> presentGroups(operation, groups.groups());
        };
    }

    private String presentGroups(String operation, Map<String, List<String>> groups) {
        List<String> rows = new ArrayList<>();
        groups.forEach((name, members) -> rows.add(presenter.inline(name, members)));
        return presenter.block("Result of " + operation + ": groups", rows);
    }
}
