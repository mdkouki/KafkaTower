package com.lynra.kafkatower.collections.bulk;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class FlatMapOperation implements BulkOperation {

    @Override
    public String name() {
        return "flat_map";
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
        Pattern delimiter = arguments.requiredPattern(name());
        return new BulkResult.Items(items.stream()
                .flatMap(item -> Arrays.stream(delimiter.split(item)))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList());
    }
}
