package com.lynra.kafkatower.collections.bulk;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** The two free-form arguments the LLM passes to a bulk operation, with the parsing every operation needs. */
public record BulkArguments(String expression, String parameter) {

    public Pattern requiredPattern(String operation) {
        if (expression == null || expression.isBlank()) {
            throw new BulkOperationException("'" + operation + "' requires an expression: a Java regular expression.");
        }
        return compile(expression);
    }

    public Optional<Pattern> optionalPattern() {
        return expression == null || expression.isBlank() ? Optional.empty() : Optional.of(compile(expression));
    }

    public Pattern requiredNumberPattern(String operation) {
        Pattern pattern = requiredPattern(operation);
        if (pattern.matcher("").groupCount() != 1) {
            throw new BulkOperationException("'" + operation
                    + "' needs a regex with exactly one capture group around the number, e.g. 'lag=(\\d+)'.");
        }
        return pattern;
    }

    public String requiredParameter(String operation, String meaning) {
        if (parameter == null) {
            throw new BulkOperationException("'" + operation + "' requires a parameter: " + meaning + ".");
        }
        return parameter;
    }

    public int expressionAsInt(String operation, int defaultValue, int minimum) {
        return parseInt(expression, operation, "expression", defaultValue, minimum);
    }

    public int parameterAsInt(String operation, int defaultValue, int minimum) {
        return parseInt(parameter, operation, "parameter", defaultValue, minimum);
    }

    public static Optional<BigDecimal> extractNumber(Pattern pattern, String item) {
        Matcher matcher = pattern.matcher(item);
        if (!matcher.find() || matcher.group(1) == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(matcher.group(1).trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Pattern compile(String regex) {
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new BulkOperationException("Invalid regex '" + regex + "': " + e.getDescription());
        }
    }

    private static int parseInt(String value, String operation, String argumentName, int defaultValue, int minimum) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed >= minimum) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // reported below with the same message as an out-of-range value
        }
        throw new BulkOperationException("'" + operation + "' needs the " + argumentName
                + " to be a whole number >= " + minimum + ", got '" + value + "'.");
    }
}
