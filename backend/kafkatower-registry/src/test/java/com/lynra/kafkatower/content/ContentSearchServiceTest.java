package com.lynra.kafkatower.content;

import com.lynra.kafkatower.content.ContentSearchRequest.HeaderFilter;
import com.lynra.kafkatower.content.ContentSearchRequest.Op;
import com.lynra.kafkatower.content.ContentSearchRequest.TextFilter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentSearchServiceTest {

    private static final String TOPIC = "orders";
    private final TopicPartition tp = new TopicPartition(TOPIC, 0);
    private MockConsumer<byte[], byte[]> consumer;
    private ContentSearchService service;
    private final List<SearchEvent> events = new ArrayList<>();

    private static byte[] b(String s) {
        return s == null ? null : s.getBytes(StandardCharsets.UTF_8);
    }

    @BeforeEach
    void setUp() {
        consumer = new MockConsumer<>("earliest");
        consumer.updatePartitions(TOPIC, List.of(new PartitionInfo(TOPIC, 0, Node.noNode(), new Node[0], new Node[0])));
        consumer.assign(List.of(tp)); // MockConsumer only accepts records for assigned partitions
        consumer.updateBeginningOffsets(Map.of(tp, 0L));
        consumer.updateEndOffsets(Map.of(tp, 10L));
        for (int i = 0; i < 10; i++) {
            ConsumerRecord<byte[], byte[]> r = new ConsumerRecord<>(TOPIC, 0, i, b("k" + i), b("{\"n\":" + i + "}"));
            if (i == 3) r.headers().add(new RecordHeader("trace", b("abc")));
            consumer.addRecord(r);
        }
        consumer.addRecord(new ConsumerRecord<>(TOPIC, 0, 10, b("tomb"), null));
        consumer.updateEndOffsets(Map.of(tp, 11L));
        service = new ContentSearchService(cluster -> consumer);
    }

    private ContentSearchRequest req(TextFilter key, HeaderFilter header, TextFilter value, Integer partition, Long offset, Integer max) {
        return new ContentSearchRequest("c", TOPIC, ContentFormat.JSON, partition, offset, key, header, value, max, null);
    }

    private void run(ContentSearchRequest r) {
        service.search(r, PayloadDecoder.create(ContentFormat.JSON, null), events::add, () -> false);
    }

    private List<ContentRecord> records() {
        return events.stream().filter(e -> e.type().equals("records")).flatMap(e -> e.records().stream()).toList();
    }

    private SearchEvent done() {
        return events.get(events.size() - 1);
    }

    @Test
    void valueContainsFindsMatchAndStreamsProgress() {
        run(req(null, null, new TextFilter(Op.CONTAINS, "\"n\":7"), null, null, null));

        assertEquals(List.of(7L), records().stream().map(ContentRecord::offset).toList());
        assertEquals("{\"n\":7}", records().get(0).value());
        assertTrue(events.stream().anyMatch(e -> e.type().equals("progress")));
        assertEquals("done", done().type());
        assertEquals(11, done().scanned());
    }

    @Test
    void scanReturnsNewestFirstAndStopsAtResultLimit() {
        run(req(new TextFilter(Op.CONTAINS, "k"), null, null, null, null, 3));

        assertEquals(List.of(9L, 8L, 7L), records().stream().map(ContentRecord::offset).toList());
        assertEquals("Result limit (3) reached", done().message());
    }

    @Test
    void keyEqualsMatchesExactKeyOnly() {
        run(req(new TextFilter(Op.EQUALS, "k4"), null, null, null, null, null));
        assertEquals(List.of(4L), records().stream().map(ContentRecord::offset).toList());
    }

    @Test
    void headerFilterMatchesKeyAndValue() {
        run(req(null, new HeaderFilter("trace", Op.EQUALS, "abc"), null, null, null, null));
        assertEquals(List.of(3L), records().stream().map(ContentRecord::offset).toList());
        assertEquals("trace", records().get(0).headers().get(0).key());
    }

    @Test
    void headerFilterWithDifferentValueMatchesNothing() {
        run(req(null, new HeaderFilter("trace", Op.EQUALS, "nope"), null, null, null, null));
        assertTrue(records().isEmpty());
    }

    @Test
    void tombstonesAreShownWithNullValue() {
        run(req(new TextFilter(Op.EQUALS, "tomb"), null, null, null, null, null));
        assertEquals(1, records().size());
        assertNull(records().get(0).value());
    }

    @Test
    void partitionAndOffsetReadsFromThatOffset() {
        run(req(null, null, null, 0, 5L, 2));
        assertEquals(List.of(5L, 6L), records().stream().map(ContentRecord::offset).toList());
    }

    @Test
    void offsetOutsideRangeReportsTheRange() {
        run(req(null, null, null, 0, 99L, null));
        assertTrue(records().isEmpty());
        assertTrue(done().message().contains("outside the available range"));
    }

    @Test
    void offsetWithoutPartitionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> run(req(null, null, null, null, 1L, null)));
    }

    @Test
    void cancelledSearchEmitsNoRecords() {
        service.search(req(new TextFilter(Op.CONTAINS, "k"), null, null, null, null, null),
                PayloadDecoder.create(ContentFormat.JSON, null), events::add, () -> true);
        assertTrue(records().isEmpty());
    }
}
