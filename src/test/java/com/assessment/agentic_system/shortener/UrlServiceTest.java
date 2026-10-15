package com.assessment.agentic_system.shortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.assessment.agentic_system.shortener.Dtos.CreateUrlRequest;
import com.assessment.agentic_system.shortener.UrlExceptions.UrlExpiredException;
import com.assessment.agentic_system.shortener.UrlExceptions.UrlNotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UrlServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    private ShortUrlRepository urls;
    private ClickEventRepository clicks;
    private UrlService service;

    @BeforeEach
    void setUp() {
        urls = mock(ShortUrlRepository.class);
        clicks = mock(ClickEventRepository.class);
        service = new UrlService(urls, clicks, new CodeGenerator(), Clock.fixed(NOW, ZoneOffset.UTC));
        when(urls.save(any(ShortUrl.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createStoresUrlWithGeneratedCode() {
        ShortUrl created = service.create(new CreateUrlRequest("https://example.com", null));

        assertThat(created.getCode()).hasSize(CodeGenerator.CODE_LENGTH);
        assertThat(created.getTargetUrl()).isEqualTo("https://example.com");
        assertThat(created.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void resolveRecordsClickAndReturnsTarget() {
        ShortUrl url = new ShortUrl("abc1234", "https://example.com", NOW, NOW.plus(Duration.ofDays(1)));
        when(urls.findByCode("abc1234")).thenReturn(Optional.of(url));

        String target = service.resolve("abc1234", "https://ref.example", "junit");

        assertThat(target).isEqualTo("https://example.com");
        assertThat(url.getClickCount()).isEqualTo(1);
        verify(clicks).save(any(ClickEvent.class));
    }

    @Test
    void resolveRejectsExpiredUrlWithoutRecordingClick() {
        ShortUrl url = new ShortUrl("old1234", "https://example.com", NOW.minusSeconds(60), NOW);
        when(urls.findByCode("old1234")).thenReturn(Optional.of(url));

        assertThatThrownBy(() -> service.resolve("old1234", null, null))
                .isInstanceOf(UrlExpiredException.class);
        assertThat(url.getClickCount()).isZero();
        verify(clicks, never()).save(any());
    }

    @Test
    void unknownCodeThrowsNotFound() {
        when(urls.findByCode("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("missing")).isInstanceOf(UrlNotFoundException.class);
    }

    @Test
    void deleteRemovesExistingUrl() {
        ShortUrl url = new ShortUrl("del1234", "https://example.com", NOW, null);
        when(urls.findByCode("del1234")).thenReturn(Optional.of(url));

        service.delete("del1234");

        verify(urls).delete(url);
    }
}
