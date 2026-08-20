package com.ncwu.predictionservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PredictionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PredictionServiceApplication.class, args);
    }

}
