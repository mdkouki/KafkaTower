package com.lynra.kafkatower.utils;

import java.util.Set;

/**
 * Tells the model how to shrink a request whose result {@link ToolResultLimiter} had to cut off.
 * Register one as a bean for the tools whose results need more specific guidance than the default.
 */
public interface ToolTruncationAdvice {

    Set<String> toolNames();

    String advice();
}
