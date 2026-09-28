package com.lynra.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

// This project depends on spring-kafka directly (not a Spring Boot Kafka starter), so there is
// no Kafka autoconfiguration to exclude — KafkaClientConfig builds the ProducerFactory/consumer
// properties by hand, which is what lets transactional mode be enabled for just one app (see
// order-service's KAFKA_PRODUCER_TRANSACTIONAL_ID in compose.yml) without every app sharing one template.
@SpringBootApplication
@EnableConfigurationProperties(DemoProperties.class)
@EnableScheduling
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
