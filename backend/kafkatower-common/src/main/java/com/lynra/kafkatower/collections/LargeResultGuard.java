package com.lynra.kafkatower.collections;

import com.embabel.agent.api.common.PromptRunner;
import com.lynra.kafkatower.tools.BulkCollectionTools;
import com.lynra.kafkatower.utils.ToolResultLimiter;
import org.springframework.stereotype.Component;

/**
 * Everything a specialist needs to cope with large tool results, applied to its {@link PromptRunner}
 * in one place: each result is bounded by {@link ToolResultLimiter}, long lists are opened through
 * {@code bulkOperation}, and an answer written from a snippet is sent back until the lists are read.
 * This and the two collaborators it owns are the only parts of the collections feature that know
 * about the Embabel runner.
 */
@Component
public class LargeResultGuard {

    private final BulkCollectionTools bulkCollectionTools;
    private final ToolResultLimiter toolResultLimiter;

    public LargeResultGuard(BulkCollectionTools bulkCollectionTools, ToolResultLimiter toolResultLimiter) {
        this.bulkCollectionTools = bulkCollectionTools;
        this.toolResultLimiter = toolResultLimiter;
    }

    /** {@code runner} must already carry the specialist's own tools and system prompt. */
    public String answer(PromptRunner runner, String question) {
        UnreadCollectionTracker unreadCollections = new UnreadCollectionTracker();
        PromptRunner guarded = runner
                .withToolObject(bulkCollectionTools)
                .withToolLoopTransformers(toolResultLimiter)
                .withToolCallInspectors(unreadCollections);
        return FullListEnforcer.answer(guarded, question, unreadCollections);
    }
}
