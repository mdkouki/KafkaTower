package com.lynra.kafkatower.content;

/**
 * One content search. With {@code offset} set it's a direct read of {@code maxResults} records from
 * that partition+offset; otherwise it's a newest-first scan applying every non-blank filter (AND).
 */
public record ContentSearchRequest(
        String clusterName,
        String topic,
        ContentFormat format,
        Integer partition,
        Long offset,
        TextFilter key,
        HeaderFilter header,
        TextFilter value,
        Integer maxResults,
        Integer maxScan) {

    public enum Op {EQUALS, CONTAINS}

    public record TextFilter(Op op, String text) {
        public boolean active() {
            return text != null && !text.isEmpty();
        }

        public boolean matches(String actual) {
            return actual != null && (op == Op.EQUALS ? actual.equals(text) : actual.contains(text));
        }
    }

    /** Matches a record having a header named {@code key}; if {@code text} is set its value must match too. */
    public record HeaderFilter(String key, Op op, String text) {
        public boolean active() {
            return key != null && !key.isEmpty();
        }
    }
}
