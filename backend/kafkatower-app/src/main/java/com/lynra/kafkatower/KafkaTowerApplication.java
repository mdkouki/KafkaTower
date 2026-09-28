package com.lynra.kafkatower;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class KafkaTowerApplication {

    public static void main(String[] args) {
        SpringApplication.run(KafkaTowerApplication.class, args);
    }

}
