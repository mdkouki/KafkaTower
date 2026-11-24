package com.lynra.kafkatower.collections;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Renders the list a tool found. A list within the inline limit is shown in full; a larger one is
 * parked in the {@link CollectionStore} and only its size, a snippet and the retrieval key reach
 * the LLM, which then uses the {@value #BULK_TOOL_NAME} tool to filter, group or page through it.
 */
@Component
public class CollectionPresenter {

    public static final String BULK_TOOL_NAME = "bulkOperation";
    public static final Pattern COLLECTION_KEY = Pattern.compile("(?i)collection key: ([0-9a-f]+)");
    private static final String ROW_INDENT = "  - ";
    private static final String EMPTY_LIST = "(none)";

    private final CollectionStore store;
    private final int inlineLimit;

    public CollectionPresenter(CollectionStore store, @Value("${agent.collections.inline-limit:5}") int inlineLimit) {
        this.store = store;
        this.inlineLimit = inlineLimit;
    }

    public int inlineLimit() {
        return inlineLimit;
    }

    /** One bullet per item under a "title (N):" line. */
    public String block(String title, List<String> items) {
        StringBuilder text = new StringBuilder(title).append(" (").append(items.size()).append("):");
        if (items.isEmpty()) {
            return text.append(' ').append(EMPTY_LIST).append('\n').toString();
        }
        text.append('\n');
        items.stream().limit(inlineLimit).forEach(item -> text.append(ROW_INDENT).append(item).append('\n'));
        if (items.size() > inlineLimit) {
            text.append("  ... ").append(items.size() - inlineLimit).append(" more not shown. Collection key: ")
                    .append(store.put(items)).append(" (").append(items.size()).append(" items).\n")
                    .append("  The full list is stored. Use ").append(BULK_TOOL_NAME)
                    .append(" with this key to access its content (operation all for every item, slice to page) ")
                    .append("or to count, filter, group or sort it.\n");
        }
        return text.toString();
    }

    /** Every item, however many: for the explicit request to read a whole collection. */
    public String completeBlock(String title, List<String> items) {
        StringBuilder text = new StringBuilder(title).append(" (").append(items.size()).append("):");
        if (items.isEmpty()) {
            return text.append(' ').append(EMPTY_LIST).append('\n').toString();
        }
        text.append('\n');
        items.forEach(item -> text.append(ROW_INDENT).append(item).append('\n'));
        return text.toString();
    }

    /** All items on one "label (N): a, b, c" line. */
    public String inline(String label, List<String> items) {
        StringBuilder text = new StringBuilder(label).append(" (").append(items.size()).append("): ");
        if (items.isEmpty()) {
            return text.append(EMPTY_LIST).toString();
        }
        text.append(String.join(", ", items.subList(0, Math.min(items.size(), inlineLimit))));
        if (items.size() > inlineLimit) {
            text.append(", ... +").append(items.size() - inlineLimit).append(" more [collection key: ")
                    .append(store.put(items)).append(", use ").append(BULK_TOOL_NAME).append(" to access the content]");
        }
        return text.toString();
    }
}
