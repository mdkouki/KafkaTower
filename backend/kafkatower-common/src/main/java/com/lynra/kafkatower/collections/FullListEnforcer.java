package com.lynra.kafkatower.collections;

import com.embabel.agent.api.common.PromptRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Stops a specialist from answering off a snippet. If a tool handed out a collection key that the
 * LLM never opened with {@code bulkOperation}, the LLM is asked again, naming those keys, until it
 * has read them or the follow-up limit is reached.
 */
final class FullListEnforcer {

    static final int MAX_FOLLOW_UPS = 2;

    private static final Logger log = LoggerFactory.getLogger(FullListEnforcer.class);

    private FullListEnforcer() {
    }

    /** {@code runner} must have {@code tracker} registered as a tool-call inspector. */
    static String answer(PromptRunner runner, String question, UnreadCollectionTracker tracker) {
        String answer = runner.createObject(question, String.class);
        for (int followUp = 1; followUp <= MAX_FOLLOW_UPS; followUp++) {
            List<UnreadCollectionTracker.UnreadCollection> unread = tracker.takeUnread();
            if (unread.isEmpty()) {
                return answer;
            }
            log.info("Answer relied on {} unopened collection(s); follow-up {}/{}", unread.size(), followUp, MAX_FOLLOW_UPS);
            answer = runner.createObject(followUpPrompt(question, answer, unread), String.class);
        }
        if (!tracker.takeUnread().isEmpty()) {
            log.warn("Answer still relies on unopened collections after {} follow-ups", MAX_FOLLOW_UPS);
        }
        return answer;
    }

    private static String followUpPrompt(String question, String previousAnswer,
                                         List<UnreadCollectionTracker.UnreadCollection> unread) {
        String collections = unread.stream()
                .map(collection -> "- collection key " + collection.key() + " (from " + collection.producedBy() + ")")
                .collect(Collectors.joining("\n"));
        return """
                Original question:
                %s

                Your previous answer:
                %s

                The tools returned only a snippet of the long lists below and you never opened them with
                %s, so your answer may be missing items:
                %s

                Call %s with each key now and retrieve every item the question needs: slice through the
                list, or filter, group or count it first when that answers the question. Then write the
                complete final answer. Never say items are "not shown" or "not listed".
                """.formatted(question, previousAnswer, CollectionPresenter.BULK_TOOL_NAME, collections,
                CollectionPresenter.BULK_TOOL_NAME);
    }
}
