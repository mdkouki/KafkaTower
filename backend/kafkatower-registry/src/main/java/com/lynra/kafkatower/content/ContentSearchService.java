package com.lynra.kafkatower.content;

import com.lynra.kafkatower.content.ContentSearchRequest.HeaderFilter;
import com.lynra.kafkatower.kafka.KafkaAdminConfig;
import com.lynra.kafkatower.kafka.KafkaAdminConfig.KafkaClustersProperties;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Reads record content straight from a topic with a short-lived, group-less consumer (manual
 * assignment, nothing committed) and streams matches to a sink as they're found.
 *
 * <p>Filter searches walk each partition backwards from its end in chunks, round-robin across
 * partitions, so the newest records surface first and every chunk can be reported immediately.
 */
@Service
public class ContentSearchService {

    static final int CHUNK = 500;
    static final int MAX_VALUE_CHARS = 64 * 1024;
    private static final int DEFAULT_MAX_RESULTS = 200;
    private static final int HARD_MAX_RESULTS = 1_000;
    private static final long DEFAULT_MAX_SCAN = 100_000;
    private static final long HARD_MAX_SCAN = 5_000_000;
    private static final Duration TIME_LIMIT = Duration.ofSeconds(60);
    private static final Duration POLL = Duration.ofMillis(300);

    private final Function<String, Consumer<byte[], byte[]>> consumerFactory;

    @Autowired
    public ContentSearchService(KafkaClustersProperties clusters) {
        this(cluster -> newConsumer(clusters, cluster));
    }

    ContentSearchService(Function<String, Consumer<byte[], byte[]>> consumerFactory) {
        this.consumerFactory = consumerFactory;
    }

    private static Consumer<byte[], byte[]> newConsumer(KafkaClustersProperties clusters, String cluster) {
        KafkaClustersProperties.ClusterConfig cfg = clusters.getClusters().get(cluster);
        if (cfg == null) {
            throw new IllegalArgumentException("Unknown cluster: " + cluster);
        }
        Map<String, Object> props = new HashMap<>(KafkaAdminConfig.clientConfig(cfg));
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, "kafkatower-content");
        return new KafkaConsumer<>(props);
    }

    /** Runs the search, pushing events to {@code sink}; returns when finished, limited, timed out or cancelled. */
    public void search(ContentSearchRequest req, PayloadDecoder decoder,
                       java.util.function.Consumer<SearchEvent> sink, BooleanSupplier cancelled) {
        Run run = new Run(req, decoder, sink, cancelled);
        try (Consumer<byte[], byte[]> consumer = consumerFactory.apply(req.clusterName())) {
            if (req.offset() != null) {
                run.direct(consumer);
            } else {
                run.scan(consumer);
            }
        }
    }

    private static int clamp(Integer v, int def, int max) {
        return v == null || v < 1 ? def : Math.min(v, max);
    }

    private static long clamp(Integer v, long def, long max) {
        return v == null || v < 1 ? def : Math.min(v, max);
    }

    /** State of a single search. */
    private static final class Run {
        final ContentSearchRequest req;
        final PayloadDecoder decoder;
        final java.util.function.Consumer<SearchEvent> sink;
        final BooleanSupplier cancelled;
        final int maxResults;
        final long maxScan;
        final Instant deadline = Instant.now().plus(TIME_LIMIT);
        long scanned;
        int matched;

        Run(ContentSearchRequest req, PayloadDecoder decoder, java.util.function.Consumer<SearchEvent> sink, BooleanSupplier cancelled) {
            this.req = req;
            this.decoder = decoder;
            this.sink = sink;
            this.cancelled = cancelled;
            this.maxResults = clamp(req.maxResults(), DEFAULT_MAX_RESULTS, HARD_MAX_RESULTS);
            this.maxScan = clamp(req.maxScan(), DEFAULT_MAX_SCAN, HARD_MAX_SCAN);
        }

        boolean stopped() {
            return cancelled.getAsBoolean() || Instant.now().isAfter(deadline);
        }

        List<TopicPartition> partitions(Consumer<byte[], byte[]> consumer) {
            List<PartitionInfo> infos = consumer.partitionsFor(req.topic(), Duration.ofSeconds(10));
            if (infos == null || infos.isEmpty()) {
                throw new IllegalArgumentException("Unknown topic: " + req.topic());
            }
            List<TopicPartition> parts = infos.stream()
                    .filter(i -> req.partition() == null || i.partition() == req.partition())
                    .map(i -> new TopicPartition(i.topic(), i.partition()))
                    .toList();
            if (parts.isEmpty()) {
                throw new IllegalArgumentException("Topic " + req.topic() + " has no partition " + req.partition());
            }
            return parts;
        }

        /** Reads up to maxResults records starting at one partition+offset. */
        void direct(Consumer<byte[], byte[]> consumer) {
            if (req.partition() == null) {
                throw new IllegalArgumentException("An offset needs a partition");
            }
            TopicPartition tp = partitions(consumer).get(0);
            long begin = consumer.beginningOffsets(List.of(tp)).get(tp);
            long end = consumer.endOffsets(List.of(tp)).get(tp);
            if (req.offset() < begin || req.offset() >= end) {
                sink.accept(SearchEvent.done(0, 0, "Offset " + req.offset() + " is outside the available range [" + begin + ", " + end + ")"));
                return;
            }
            consumer.assign(List.of(tp));
            consumer.seek(tp, req.offset());
            int empty = 0;
            while (matched < maxResults && consumer.position(tp) < end && !stopped() && empty < 5) {
                ConsumerRecords<byte[], byte[]> polled = consumer.poll(POLL);
                empty = polled.isEmpty() ? empty + 1 : 0;
                List<ContentRecord> batch = new ArrayList<>();
                for (ConsumerRecord<byte[], byte[]> r : polled.records(tp)) {
                    if (matched >= maxResults) break;
                    batch.add(toContent(r, null));
                    matched++;
                }
                scanned += batch.size();
                if (!batch.isEmpty()) sink.accept(SearchEvent.records(batch));
            }
            sink.accept(SearchEvent.done(scanned, matched, null));
        }

        /** Newest-first filtered scan, one chunk of one partition at a time. */
        void scan(Consumer<byte[], byte[]> consumer) {
            List<TopicPartition> parts = partitions(consumer);
            Map<TopicPartition, Long> begin = consumer.beginningOffsets(parts);
            Map<TopicPartition, Long> cursor = new HashMap<>(consumer.endOffsets(parts));
            Deque<TopicPartition> active = new ArrayDeque<>();
            for (TopicPartition tp : parts) {
                if (cursor.get(tp) > begin.get(tp)) active.add(tp);
            }
            while (!active.isEmpty() && matched < maxResults && scanned < maxScan && !stopped()) {
                TopicPartition tp = active.poll();
                long hi = cursor.get(tp);
                long lo = Math.max(begin.get(tp), hi - CHUNK);
                List<ConsumerRecord<byte[], byte[]>> chunk = readRange(consumer, tp, lo, hi);
                scanned += chunk.size();

                List<ContentRecord> found = new ArrayList<>();
                for (int i = chunk.size() - 1; i >= 0 && matched < maxResults; i--) {
                    ContentRecord match = matchOrNull(chunk.get(i));
                    if (match != null) {
                        found.add(match);
                        matched++;
                    }
                }
                cursor.put(tp, lo);
                if (lo > begin.get(tp)) active.add(tp);
                if (!found.isEmpty()) sink.accept(SearchEvent.records(found));
                sink.accept(SearchEvent.progress(scanned, matched));
            }
            String why = matched >= maxResults ? "Result limit (" + maxResults + ") reached"
                    : scanned >= maxScan ? "Scan limit (" + maxScan + " records) reached"
                    : !active.isEmpty() && !cancelled.getAsBoolean() ? "Time limit reached" : null;
            sink.accept(SearchEvent.done(scanned, matched, why));
        }

        List<ConsumerRecord<byte[], byte[]>> readRange(Consumer<byte[], byte[]> consumer, TopicPartition tp, long lo, long hi) {
            consumer.assign(List.of(tp));
            consumer.seek(tp, lo);
            List<ConsumerRecord<byte[], byte[]>> out = new ArrayList<>();
            int empty = 0;
            while (consumer.position(tp) < hi && !stopped() && empty < 3) {
                ConsumerRecords<byte[], byte[]> polled = consumer.poll(POLL);
                empty = polled.isEmpty() ? empty + 1 : 0;
                for (ConsumerRecord<byte[], byte[]> r : polled.records(tp)) {
                    if (r.offset() < hi) out.add(r);
                }
            }
            return out;
        }

        /** Applies key / header / value filters (cheapest first); returns the record to show, or null. */
        ContentRecord matchOrNull(ConsumerRecord<byte[], byte[]> r) {
            if (req.key() != null && req.key().active() && !req.key().matches(utf8(r.key()))) return null;
            if (req.header() != null && req.header().active() && !headerMatches(r, req.header())) return null;
            PayloadDecoder.Decoded decoded = null;
            if (req.value() != null && req.value().active()) {
                decoded = decodeValue(r);
                if (decoded == null || !req.value().matches(decoded.text())) return null;
            }
            return toContent(r, decoded);
        }

        boolean headerMatches(ConsumerRecord<byte[], byte[]> r, HeaderFilter f) {
            for (org.apache.kafka.common.header.Header h : r.headers()) {
                if (h.key().equals(f.key())
                        && (f.text() == null || f.text().isEmpty()
                        || new ContentSearchRequest.TextFilter(f.op(), f.text()).matches(utf8(h.value())))) {
                    return true;
                }
            }
            return false;
        }

        PayloadDecoder.Decoded decodeValue(ConsumerRecord<byte[], byte[]> r) {
            return r.value() == null ? null : decoder.decode(r.value());
        }

        ContentRecord toContent(ConsumerRecord<byte[], byte[]> r, PayloadDecoder.Decoded decoded) {
            if (decoded == null) decoded = decodeValue(r);
            String text = decoded == null ? null : decoded.text();
            boolean truncated = text != null && text.length() > MAX_VALUE_CHARS;
            if (truncated) text = text.substring(0, MAX_VALUE_CHARS);
            List<ContentRecord.Header> headers = new ArrayList<>();
            r.headers().forEach(h -> headers.add(new ContentRecord.Header(h.key(), utf8(h.value()))));
            return new ContentRecord(r.partition(), r.offset(), r.timestamp(), utf8(r.key()), headers,
                    text, decoded == null || decoded.decoded(), truncated);
        }

        static String utf8(byte[] b) {
            return b == null ? null : new String(b, StandardCharsets.UTF_8);
        }
    }
}
