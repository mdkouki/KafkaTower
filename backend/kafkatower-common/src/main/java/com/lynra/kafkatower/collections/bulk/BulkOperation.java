package com.lynra.kafkatower.collections.bulk;

import java.util.List;

public interface BulkOperation {

    String name();

    boolean usesExpression();

    boolean usesParameter();

    BulkResult apply(List<String> items, BulkArguments arguments);

    /** A silently ignored argument means the caller misunderstood the operation, so say so. */
    default void rejectUnusedArguments(BulkArguments arguments) {
        rejectIfUnused(usesExpression(), "expression", arguments.expression());
        rejectIfUnused(usesParameter(), "parameter", arguments.parameter());
    }

    private void rejectIfUnused(boolean used, String argumentName, String value) {
        if (!used && value != null && !value.isBlank()) {
            throw new BulkOperationException("'" + name() + "' takes no " + argumentName
                    + " (got '" + value + "'). Remove it and call again.");
        }
    }
}
