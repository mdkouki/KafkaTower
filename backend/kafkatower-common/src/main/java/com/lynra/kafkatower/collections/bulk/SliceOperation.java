package com.lynra.kafkatower.collections.bulk;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SliceOperation implements BulkOperation {

    private static final int DEFAULT_LIMIT = 5;

    @Override
    public String name() {
        return "slice";
    }

    @Override
    public boolean usesExpression() {
        return true;
    }

    @Override
    public boolean usesParameter() {
        return true;
    }

    @Override
    public BulkResult apply(List<String> items, BulkArguments arguments) {
        int offset = arguments.expressionAsInt(name(), 0, 0);
        int limit = arguments.parameterAsInt(name(), DEFAULT_LIMIT, 1);
        int from = Math.min(offset, items.size());
        int to = (int) Math.min((long) from + limit, items.size());
        return new BulkResult.Items(items.subList(from, to));
    }
}
