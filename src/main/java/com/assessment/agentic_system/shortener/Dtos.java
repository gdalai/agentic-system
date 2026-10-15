package com.assessment.agentic_system.shortener;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.hibernate.validator.constraints.URL;

/** Request and response contracts for the URL shortener API. */
public final class Dtos {

    private Dtos() {
    }

    public record CreateUrlRequest(
            @NotBlank @URL(regexp = "^(?i)https?://.*") @Size(max = 2048) String targetUrl,
            @Future Instant expiresAt) {
    }

    public record UrlResponse(
            String code,
            String shortUrl,
            String targetUrl,
            Instant createdAt,
            Instant expiresAt,
            long clickCount) {

        static UrlResponse from(ShortUrl url, String baseUrl) {
            return new UrlResponse(url.getCode(), baseUrl + "/r/" + url.getCode(), url.getTargetUrl(),
                    url.getCreatedAt(), url.getExpiresAt(), url.getClickCount());
        }
    }

    public record UrlAnalytics(
            String code,
            long totalClicks,
            Instant lastClickedAt,
            boolean expired) {
    }
}
