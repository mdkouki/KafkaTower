package com.lynra.kafkatower.collections.bulk;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.function.IntPredicate;

/** A comparison such as "gt 1000", applied to a number extracted from an item. */
record NumericComparison(IntPredicate onComparisonResult, BigDecimal threshold) {

    private static final String SYNTAX_HINT = "a comparison like 'gt 1000' (operators: gt, gte, lt, lte, eq, ne)";

    static NumericComparison parse(String operation, String text) {
        String[] parts = text == null ? new String[0] : text.trim().split("\\s+");
        if (parts.length != 2) {
            throw new BulkOperationException("'" + operation + "' requires a parameter: " + SYNTAX_HINT + ".");
        }
        IntPredicate predicate = switch (parts[0].toLowerCase(Locale.ROOT)) {
            case "gt" -> result -> result > 0;
            case "gte" -> result -> result >= 0;
            case "lt" -> result -> result < 0;
            case "lte" -> result -> result <= 0;
            case "eq" -> result -> result == 0;
            case "ne" -> result -> result != 0;
            default -> throw new BulkOperationException("Unknown comparison '" + parts[0] + "'; use " + SYNTAX_HINT + ".");
        };
        try {
            return new NumericComparison(predicate, new BigDecimal(parts[1]));
        } catch (NumberFormatException e) {
            throw new BulkOperationException("'" + parts[1] + "' is not a number; use " + SYNTAX_HINT + ".");
        }
    }

    boolean test(BigDecimal actual) {
        return onComparisonResult.test(actual.compareTo(threshold));
    }
}
