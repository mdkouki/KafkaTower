package com.lynra.kafkatower.collections.bulk;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ChunkOperation implements BulkOperation {

    @Override
    public String name() {
        return "chunk";
    }

    @Override
    public boolean usesExpression() {
        return true;
    }

    @Override
    public boolean usesParameter() {
        return false;
    }

    @Override
    public BulkResult apply(List<String> items, BulkArguments arguments) {
        if (arguments.expression() == null || arguments.expression().isBlank()) {
            throw new BulkOperationException("'chunk' requires an expression: the number of items per chunk.");
        }
        int size = arguments.expressionAsInt(name(), 1, 1);
        Map<String, List<String>> chunks = new LinkedHashMap<>();
        for (int start = 0; start < items.size(); start += size) {
            int end = Math.min(start + size, items.size());
            chunks.put("chunk " + (chunks.size() + 1) + " (items " + (start + 1) + "-" + end + ")",
                    items.subList(start, end));
        }
        return new BulkResult.Groups(chunks);
    }
}
