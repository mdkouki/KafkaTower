package com.lynra.kafkatower.collections.bulk;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

@Component
public class FilterOperation implements BulkOperation {

    @Override
    public String name() {
        return "filter";
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
        Pattern pattern = arguments.requiredPattern(name());
        return new BulkResult.Items(items.stream().filter(item -> pattern.matcher(item).find()).toList());
    }
}
