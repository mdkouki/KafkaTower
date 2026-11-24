package com.lynra.kafkatower.collections.bulk;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class CountOperation implements BulkOperation {

    @Override
    public String name() {
        return "count";
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
        Optional<Pattern> pattern = arguments.optionalPattern();
        long count = pattern.map(regex -> items.stream().filter(item -> regex.matcher(item).find()).count())
                .orElse((long) items.size());
        return new BulkResult.Value(String.valueOf(count));
    }
}
