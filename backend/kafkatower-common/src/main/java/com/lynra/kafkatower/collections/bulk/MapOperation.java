package com.lynra.kafkatower.collections.bulk;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

@Component
public class MapOperation implements BulkOperation {

    @Override
    public String name() {
        return "map";
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
        Pattern pattern = arguments.requiredPattern(name());
        String replacement = arguments.requiredParameter(name(), "the replacement, e.g. '$1' to keep a capture group");
        try {
            return new BulkResult.Items(items.stream().map(item -> pattern.matcher(item).replaceAll(replacement)).toList());
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw new BulkOperationException("Invalid replacement '" + replacement + "': " + e.getMessage());
        }
    }
}
