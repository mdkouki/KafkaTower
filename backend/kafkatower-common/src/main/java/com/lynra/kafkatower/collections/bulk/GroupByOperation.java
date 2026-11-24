package com.lynra.kafkatower.collections.bulk;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class GroupByOperation implements BulkOperation {

    static final String UNMATCHED_GROUP = "(no match)";

    @Override
    public String name() {
        return "group_by";
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
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (String item : items) {
            grouped.computeIfAbsent(groupOf(pattern, item), group -> new ArrayList<>()).add(item);
        }
        Map<String, List<String>> largestFirst = new LinkedHashMap<>();
        grouped.entrySet().stream()
                .sorted(Map.Entry.<String, List<String>>comparingByValue(Comparator.comparingInt(List::size)).reversed())
                .forEach(entry -> largestFirst.put(entry.getKey(), entry.getValue()));
        return new BulkResult.Groups(largestFirst);
    }

    private static String groupOf(Pattern pattern, String item) {
        Matcher matcher = pattern.matcher(item);
        if (!matcher.find()) {
            return UNMATCHED_GROUP;
        }
        String group = matcher.groupCount() > 0 ? matcher.group(1) : matcher.group();
        return group == null ? UNMATCHED_GROUP : group;
    }
}
