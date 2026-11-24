package com.lynra.kafkatower.collections.bulk;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class AggregateOperation implements BulkOperation {

    private static final String FUNCTIONS = "sum, min, max or avg";

    @Override
    public String name() {
        return "aggregate";
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
        Pattern pattern = arguments.requiredNumberPattern(name());
        String function = arguments.requiredParameter(name(), FUNCTIONS).trim().toLowerCase(Locale.ROOT);
        List<BigDecimal> numbers = items.stream()
                .map(item -> BulkArguments.extractNumber(pattern, item))
                .flatMap(Optional::stream)
                .toList();
        if (numbers.isEmpty()) {
            return new BulkResult.Value("No item contains a number matching the expression; nothing to " + function + ".");
        }
        BigDecimal value = switch (function) {
            case "sum" -> sum(numbers);
            case "min" -> numbers.stream().min(BigDecimal::compareTo).orElseThrow();
            case "max" -> numbers.stream().max(BigDecimal::compareTo).orElseThrow();
            case "avg" -> sum(numbers).divide(BigDecimal.valueOf(numbers.size()), MathContext.DECIMAL64);
            default -> throw new BulkOperationException("'aggregate' parameter must be " + FUNCTIONS + ", got '" + function + "'.");
        };
        return new BulkResult.Value(function + " = " + value.stripTrailingZeros().toPlainString()
                + " (over " + numbers.size() + " of " + items.size() + " items)");
    }

    private static BigDecimal sum(List<BigDecimal> numbers) {
        return numbers.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
