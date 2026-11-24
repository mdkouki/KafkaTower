package com.lynra.kafkatower.collections.bulk;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DistinctOperation implements BulkOperation {

    @Override
    public String name() {
        return "distinct";
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
        return new BulkResult.Items(items.stream().distinct().toList());
    }
}
