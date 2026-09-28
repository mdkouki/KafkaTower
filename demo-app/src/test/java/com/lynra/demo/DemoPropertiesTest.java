package com.lynra.demo;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoPropertiesTest {

    @Test
    void trimsWhitespaceFromEachConsumesEntry() {
        DemoProperties properties = new DemoProperties();

        properties.setConsumes(new ArrayList<>(List.of(" topic-a", "topic-b ", "  topic-c  ")));

        assertEquals(List.of("topic-a", "topic-b", "topic-c"), properties.getConsumes());
    }

    @Test
    void dropsBlankEntriesAfterTrimming() {
        DemoProperties properties = new DemoProperties();

        properties.setProduces(new ArrayList<>(List.of("topic-a", "   ", "")));

        assertEquals(List.of("topic-a"), properties.getProduces());
    }

    @Test
    void isTransactionalFalseWhenTransactionalIdBlankOrNull() {
        DemoProperties properties = new DemoProperties();

        assertFalse(properties.isTransactional());

        properties.setTransactionalId("   ");
        assertFalse(properties.isTransactional());
    }

    @Test
    void isTransactionalTrueWhenTransactionalIdSet() {
        DemoProperties properties = new DemoProperties();

        properties.setTransactionalId("order-txn-1");

        assertTrue(properties.isTransactional());
    }
}
