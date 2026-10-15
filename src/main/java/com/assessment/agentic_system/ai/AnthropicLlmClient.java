package com.assessment.agentic_system.ai;

import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Claude Messages API adapter, built on Spring's {@link RestClient} so it adds no dependencies.
 * Enabled with {@code ai.provider=anthropic} and an API key; see {@link AiConfig}.
 */
public class AnthropicLlmClient implements LlmClient {

    private final RestClient http;
    private final String model;
    private final int maxTokens;

    public AnthropicLlmClient(String baseUrl, String apiKey, String model, int maxTokens) {
        this.http = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();
        this.model = model;
        this.maxTokens = maxTokens;
    }

    @Override
    public String name() {
        return "anthropic:" + model;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String complete(String systemPrompt, String userPrompt) {
        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", maxTokens,
                "system", systemPrompt,
                "messages", List.of(Map.of("role", "user", "content", userPrompt)));
        try {
            Map<String, Object> response = http.post()
                    .uri("/v1/messages")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            return extractText(response);
        } catch (RestClientException e) {
            throw new LlmUnavailableException("Anthropic API call failed: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    static String extractText(Map<String, Object> response) {
        if (response == null || !(response.get("content") instanceof List<?> blocks)) {
            throw new LlmUnavailableException("Anthropic API returned no content", null);
        }
        StringBuilder text = new StringBuilder();
        for (Object block : blocks) {
            if (block instanceof Map<?, ?> map && "text".equals(map.get("type")) && map.get("text") != null) {
                text.append(map.get("text")).append('\n');
            }
        }
        return text.toString().strip();
    }
}
