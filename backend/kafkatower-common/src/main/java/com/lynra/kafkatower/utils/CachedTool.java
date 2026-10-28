package com.lynra.kafkatower.utils;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an {@code @LlmTool} method whose String result may be served from a short-lived cache —
 * see {@link ToolResultCacheAspect}. Only put this on <b>read-only</b> tools whose result does not
 * depend on the calling user: the cache key is the method plus its arguments, nothing else.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface CachedTool {

    /** How long a result stays fresh. Keep it short for live state (offsets, lag). */
    int ttlSeconds() default 30;
}
