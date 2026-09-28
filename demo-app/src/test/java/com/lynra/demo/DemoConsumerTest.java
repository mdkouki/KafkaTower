package com.lynra.demo;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoConsumerTest {

    @Test
    void matchesExactLiteralTopicOnly() {
        Pattern pattern = DemoConsumer.buildPattern(List.of("orders.events"));

        assertTrue(pattern.matcher("orders.events").matches());
        assertFalse(pattern.matcher("orders.events.v2").matches());
        assertFalse(pattern.matcher("other_topic").matches());
    }

    @Test
    void treatsDashesAndDotsInLiteralTopicsAsLiteralNotRegex() {
        // "orders.commands.provider" must not have its '-' or any implicit '.' treated as
        // regex metacharacters (there's no dot in this one, but a naive non-quoted join could
        // still mis-treat other punctuation) — Pattern.quote guarantees a literal match.
        Pattern pattern = DemoConsumer.buildPattern(List.of("orders.commands.provider"));

        assertTrue(pattern.matcher("orders.commands.provider").matches());
        assertFalse(pattern.matcher("ordersXcommandsXprovider").matches());
    }

    @Test
    void wildcardEntryMatchesAnyTopicWithThatPrefix() {
        Pattern pattern = DemoConsumer.buildPattern(List.of("shipments.*"));

        assertTrue(pattern.matcher("shipments.finance").matches());
        assertTrue(pattern.matcher("shipments.anything_else").matches());
        assertFalse(pattern.matcher("other_shipments_topic").matches());
    }

    @Test
    void multipleEntriesAreAlternatives() {
        Pattern pattern = DemoConsumer.buildPattern(List.of("topic-a", "topic-b"));

        assertTrue(pattern.matcher("topic-a").matches());
        assertTrue(pattern.matcher("topic-b").matches());
        assertFalse(pattern.matcher("topic-c").matches());
    }
}
