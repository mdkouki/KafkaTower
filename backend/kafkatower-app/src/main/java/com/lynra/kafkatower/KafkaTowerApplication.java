package com.lynra.kafkatower;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class KafkaTowerApplication {

    public static void main(String[] args) {
        // ChatService runs each blocking agent process on Reactor's boundedElastic scheduler, which
        // is capped at 10 x CPU platform threads by default. Read once at Reactor class-init, so it
        // must be set before anything touches Schedulers — a yml property would be too late.
        System.setProperty("reactor.schedulers.defaultBoundedElasticOnVirtualThreads",
                System.getProperty("reactor.schedulers.defaultBoundedElasticOnVirtualThreads", "true"));
        SpringApplication.run(KafkaTowerApplication.class, args);
    }

}
