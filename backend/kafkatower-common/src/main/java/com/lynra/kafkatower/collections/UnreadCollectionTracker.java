package com.lynra.kafkatower.collections;

import com.embabel.agent.api.tool.callback.AfterToolCallContext;
import com.embabel.agent.api.tool.callback.BeforeToolCallContext;
import com.embabel.agent.api.tool.callback.ToolCallInspector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * Remembers, for one request, which collection keys the tools handed out and which of them the LLM
 * opened with {@code bulkOperation}. Stateful: create one instance per request.
 * <p>
 * Keys printed by {@code bulkOperation} itself are not tracked: they are intermediate results of a
 * list the LLM already chose to open.
 */
public final class UnreadCollectionTracker implements ToolCallInspector {

    public record UnreadCollection(String key, String producedBy) {
    }

    private final Map<String, String> producerByUnreadKey = new LinkedHashMap<>();

    @Override
    public void beforeToolCall(BeforeToolCallContext context) {
    }

    @Override
    public synchronized void afterToolCall(AfterToolCallContext context) {
        String tool = context.getToolCall().getName();
        if (CollectionPresenter.BULK_TOOL_NAME.equals(tool)) {
            producerByUnreadKey.keySet().removeIf(key -> context.getToolCall().getArguments().contains(key));
            return;
        }
        Matcher keys = CollectionPresenter.COLLECTION_KEY.matcher(context.getResultAsString());
        while (keys.find()) {
            producerByUnreadKey.putIfAbsent(keys.group(1), tool);
        }
    }

    /** Returns the collections still unopened and forgets them, so each one is demanded at most once. */
    public synchronized List<UnreadCollection> takeUnread() {
        List<UnreadCollection> unread = new ArrayList<>();
        producerByUnreadKey.forEach((key, tool) -> unread.add(new UnreadCollection(key, tool)));
        producerByUnreadKey.clear();
        return unread;
    }
}
