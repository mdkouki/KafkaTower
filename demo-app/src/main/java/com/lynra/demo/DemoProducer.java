package com.lynra.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Sends a small message to every {@code demo.produces} topic on a fixed interval, to generate steady dummy traffic. */
@Component
public class DemoProducer {

    private static final Logger log = LoggerFactory.getLogger(DemoProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final DemoProperties properties;

    public DemoProducer(KafkaTemplate<String, String> kafkaTemplate, DemoProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${demo.produce-interval-ms:5000}")
    public void produce() {
        if (properties.getProduces().isEmpty()) {
            return;
        }
        if (properties.isTransactional()) {
            kafkaTemplate.executeInTransaction(template -> {
                sendToEveryTopic(template);
                return null;
            });
        } else {
            sendToEveryTopic(kafkaTemplate);
        }
    }

    private void sendToEveryTopic(KafkaOperations<String, String> template) {
        String payload = "{\"app\":\"" + properties.getAppName() + "\",\"ts\":" + Instant.now().toEpochMilli() + "}";
        for (String topic : properties.getProduces()) {
            template.send(topic, payload).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.warn("[{}] failed to send to {}: {}", properties.getAppName(), topic, ex.getMessage());
                } else {
                    log.debug("[{}] sent to {}-{}@{}", properties.getAppName(), topic,
                            result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
                }
            });
        }
    }
}
