package com.lynra.demo;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Subscribes to every {@code demo.consumes} entry via a single regex {@link Pattern}, so both
 * exact topic names and prefix-wildcard entries (e.g. {@code "shipments.*"}) are handled uniformly
 * with no special-casing — a literal entry is escaped with {@link Pattern#quote}, anything
 * already containing a {@code *} is left as-is and used as a regex fragment directly.
 */
@Component
public class DemoConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DemoConsumer.class);

    private final Map<String, Object> consumerProps;
    private final DemoProperties properties;

    private volatile boolean running = false;
    private volatile KafkaConsumer<String, String> consumer;
    private Thread pollThread;

    public DemoConsumer(Map<String, Object> consumerProps, DemoProperties properties) {
        this.consumerProps = consumerProps;
        this.properties = properties;
    }

    static Pattern buildPattern(List<String> topics) {
        String joined = topics.stream()
                .map(t -> t.contains("*") ? t : Pattern.quote(t))
                .collect(Collectors.joining("|"));
        return Pattern.compile(joined);
    }

    @Override
    public void start() {
        if (properties.getConsumes().isEmpty()) {
            log.info("[{}] no consumes configured, consumer not started", properties.getAppName());
            return;
        }
        consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(buildPattern(properties.getConsumes()));
        running = true;
        pollThread = new Thread(this::pollLoop, properties.getAppName() + "-consumer");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private void pollLoop() {
        try {
            while (running) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));
                for (ConsumerRecord<String, String> record : records) {
                    log.info("[{}] consumed {}-{}@{}", properties.getAppName(), record.topic(), record.partition(), record.offset());
                }
            }
        } catch (WakeupException e) {
            // expected on stop()
        } catch (Exception e) {
            log.error("[{}] consumer loop failed", properties.getAppName(), e);
        } finally {
            consumer.close();
        }
    }

    @Override
    public void stop() {
        running = false;
        KafkaConsumer<String, String> c = consumer;
        if (c != null) {
            c.wakeup();
        }
        if (pollThread != null) {
            try {
                pollThread.join(Duration.ofSeconds(5).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
