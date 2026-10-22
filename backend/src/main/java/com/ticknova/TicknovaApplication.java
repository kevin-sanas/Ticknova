package com.ticknova;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TicknovaApplication {
    public static void main(String[] args) { SpringApplication.run(TicknovaApplication.class, args); }
}
