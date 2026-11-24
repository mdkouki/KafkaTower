package com.lynra.kafkatower.collections.bulk;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SortOperation implements BulkOperation {

    private static final String DESCENDING = "desc";
    private static final String ASCENDING = "asc";

    private record Keyed(String item, String key) {
    }

    @Override
    public String name() {
        return "sort";
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
        boolean descending = isDescending(arguments.parameter());
        Optional<Pattern> keyPattern = arguments.optionalPattern();
        List<Keyed> keyed = new ArrayList<>();
        List<String> withoutKey = new ArrayList<>();
        for (String item : items) {
            Optional<String> key = keyPattern.map(pattern -> keyOf(pattern, item)).orElse(Optional.of(item));
            key.ifPresentOrElse(k -> keyed.add(new Keyed(item, k)), () -> withoutKey.add(item));
        }
        Comparator<Keyed> order = allNumeric(keyed)
                ? Comparator.comparing(entry -> new BigDecimal(entry.key().trim()))
                : Comparator.comparing(Keyed::key);
        keyed.sort(descending ? order.reversed() : order);
        List<String> sorted = new ArrayList<>(keyed.stream().map(Keyed::item).toList());
        sorted.addAll(withoutKey);
        return new BulkResult.Items(sorted);
    }

    private static boolean isDescending(String direction) {
        if (direction == null || direction.isBlank() || direction.trim().equalsIgnoreCase(ASCENDING)) {
            return false;
        }
        if (direction.trim().toLowerCase(Locale.ROOT).equals(DESCENDING)) {
            return true;
        }
        throw new BulkOperationException("'sort' parameter must be '" + ASCENDING + "' or '" + DESCENDING + "', got '" + direction + "'.");
    }

    private static Optional<String> keyOf(Pattern pattern, String item) {
        Matcher matcher = pattern.matcher(item);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.ofNullable(matcher.groupCount() > 0 ? matcher.group(1) : matcher.group());
    }

    private static boolean allNumeric(List<Keyed> keyed) {
        return keyed.stream().allMatch(entry -> {
            try {
                new BigDecimal(entry.key().trim());
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        });
    }
}
