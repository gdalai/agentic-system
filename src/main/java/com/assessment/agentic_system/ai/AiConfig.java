package com.assessment.agentic_system.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the model provider. {@code mock} (default) needs no credentials and is what CI uses.
 * {@code anthropic} calls the Claude Messages API and fails fast at startup without a key.
 */
@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    @Bean
    public LlmClient llmClient(
            @Value("${ai.provider:mock}") String provider,
            @Value("${ai.anthropic.api-key:}") String apiKey,
            @Value("${ai.anthropic.model:claude-sonnet-5-5}") String model,
            @Value("${ai.anthropic.base-url:https://api.anthropic.com}") String baseUrl,
            @Value("${ai.anthropic.max-tokens:1024}") int maxTokens) {
        return switch (provider.trim().toLowerCase()) {
            case "mock" -> {
                log.info("AI provider: deterministic mock");
                yield new MockLlmClient();
            }
            case "anthropic" -> {
                if (apiKey.isBlank()) {
                    throw new IllegalStateException(
                            "ai.provider=anthropic requires ai.anthropic.api-key (env ANTHROPIC_API_KEY)");
                }
                log.info("AI provider: Anthropic, model {}", model);
                yield new AnthropicLlmClient(baseUrl, apiKey, model, maxTokens);
            }
            default -> throw new IllegalStateException("Unknown ai.provider '" + provider + "' (use mock or anthropic)");
        };
    }
}
