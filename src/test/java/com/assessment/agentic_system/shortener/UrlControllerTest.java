package com.assessment.agentic_system.shortener;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.assessment.agentic_system.common.ApiExceptionHandler;
import com.assessment.agentic_system.shortener.UrlExceptions.UrlExpiredException;
import com.assessment.agentic_system.shortener.UrlExceptions.UrlNotFoundException;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Web-layer tests using standalone MockMvc: fast, and independent of Boot's test auto-configuration. */
class UrlControllerTest {

    private UrlService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(UrlService.class);
        mvc = MockMvcBuilders.standaloneSetup(new UrlController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void createReturns201WithShortUrl() throws Exception {
        when(service.create(any())).thenReturn(
                new ShortUrl("abc1234", "https://example.com", Instant.parse("2026-10-15T12:00:00Z"), null));

        mvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUrl\":\"https://example.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/urls/abc1234"))
                .andExpect(jsonPath("$.code").value("abc1234"))
                .andExpect(jsonPath("$.shortUrl").value("http://localhost/r/abc1234"));
    }

    @Test
    void createRejectsInvalidUrlWith400() throws Exception {
        mvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUrl\":\"not a url\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.targetUrl").exists());
    }

    @Test
    void redirectReturns302ToTarget() throws Exception {
        when(service.resolve(eq("abc1234"), any(), any())).thenReturn("https://example.com");

        mvc.perform(get("/r/abc1234"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com"));
    }

    @Test
    void unknownCodeReturns404() throws Exception {
        when(service.get(anyString())).thenThrow(new UrlNotFoundException("nope"));

        mvc.perform(get("/api/v1/urls/nope")).andExpect(status().isNotFound());
    }

    @Test
    void expiredCodeReturns410() throws Exception {
        when(service.resolve(eq("old1234"), any(), any())).thenThrow(new UrlExpiredException("old1234"));

        mvc.perform(get("/r/old1234")).andExpect(status().isGone());
    }
}
