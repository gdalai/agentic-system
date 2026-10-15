package com.assessment.agentic_system.shortener;

import com.assessment.agentic_system.shortener.Dtos.CreateUrlRequest;
import com.assessment.agentic_system.shortener.Dtos.UrlAnalytics;
import com.assessment.agentic_system.shortener.Dtos.UrlResponse;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
public class UrlController {

    private final UrlService service;

    public UrlController(UrlService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/urls")
    public ResponseEntity<UrlResponse> create(@Valid @RequestBody CreateUrlRequest request) {
        ShortUrl url = service.create(request);
        UrlResponse body = UrlResponse.from(url, baseUrl());
        return ResponseEntity.created(URI.create(baseUrl() + "/api/v1/urls/" + url.getCode())).body(body);
    }

    @GetMapping("/api/v1/urls/{code}")
    public UrlResponse get(@PathVariable String code) {
        return UrlResponse.from(service.get(code), baseUrl());
    }

    @DeleteMapping("/api/v1/urls/{code}")
    public ResponseEntity<Void> delete(@PathVariable String code) {
        service.delete(code);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/urls/{code}/analytics")
    public UrlAnalytics analytics(@PathVariable String code) {
        return service.analytics(code);
    }

    /** Public redirect endpoint. Kept under /r/ so it never collides with /actuator, /swagger-ui, etc. */
    @GetMapping("/r/{code}")
    public ResponseEntity<Void> redirect(
            @PathVariable String code,
            @RequestHeader(value = HttpHeaders.REFERER, required = false) String referrer,
            @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        String target = service.resolve(code, referrer, userAgent);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }

    private static String baseUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }
}
