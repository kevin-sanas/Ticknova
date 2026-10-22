package com.ticknova.ai.model;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class ModelConfiguration {
    @Bean
    public AiModel aiModel(Environment environment) {
        String provider = environment.getProperty("ticknova.ai.provider", "mock");
        return switch (provider) {
            case "mock" -> new MockAiModel();
            case "openai" -> new SpringAiModel(environment.getProperty("ticknova.ai.base-url"),
                    environment.getProperty("ticknova.ai.api-key"), environment.getProperty("ticknova.ai.chat-model"),
                    environment.getProperty("ticknova.ai.embedding-model"));
            default -> throw new AiModelFailure("AI_CONFIGURATION_INVALID", false);
        };
    }
}
