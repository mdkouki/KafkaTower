package com.lynra.kafkatower.collections.bulk;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AllOperation implements BulkOperation {

    private final int maxItems;

    public AllOperation(@Value("${agent.collections.max-full-items:500}") int maxItems) {
        this.maxItems = maxItems;
    }

    @Override
    public String name() {
        return "all";
    }

    @Override
    public boolean usesExpression() {
        return false;
    }

    @Override
    public boolean usesParameter() {
        return false;
    }

    @Override
    public BulkResult apply(List<String> items, BulkArguments arguments) {
        if (items.size() > maxItems) {
            throw new BulkOperationException("The collection has " + items.size() + " items, more than the "
                    + maxItems + " that can be read at once. Narrow it with filter, group_by or chunk first.");
        }
        return new BulkResult.Everything(items);
    }
}
