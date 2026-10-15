package com.assessment.agentic_system.shortener;

import com.assessment.agentic_system.shortener.Dtos.CreateUrlRequest;
import com.assessment.agentic_system.shortener.Dtos.UrlAnalytics;
import com.assessment.agentic_system.shortener.UrlExceptions.UrlExpiredException;
import com.assessment.agentic_system.shortener.UrlExceptions.UrlNotFoundException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UrlService {

    private final ShortUrlRepository urls;
    private final ClickEventRepository clicks;
    private final CodeGenerator codeGenerator;
    private final Clock clock;

    public UrlService(ShortUrlRepository urls, ClickEventRepository clicks, CodeGenerator codeGenerator, Clock clock) {
        this.urls = urls;
        this.clicks = clicks;
        this.codeGenerator = codeGenerator;
        this.clock = clock;
    }

    @Transactional
    public ShortUrl create(CreateUrlRequest request) {
        String code = codeGenerator.generate(urls::existsByCode);
        return urls.save(new ShortUrl(code, request.targetUrl(), clock.instant(), request.expiresAt()));
    }

    @Transactional(readOnly = true)
    public ShortUrl get(String code) {
        return urls.findByCode(code).orElseThrow(() -> new UrlNotFoundException(code));
    }

    /** Resolves a code for redirect: rejects expired links and records the click. */
    @Transactional
    public String resolve(String code, String referrer, String userAgent) {
        ShortUrl url = get(code);
        Instant now = clock.instant();
        if (url.isExpiredAt(now)) {
            throw new UrlExpiredException(code);
        }
        url.recordClick();
        clicks.save(new ClickEvent(url, now, referrer, userAgent));
        return url.getTargetUrl();
    }

    @Transactional
    public void delete(String code) {
        urls.delete(get(code));
    }

    @Transactional(readOnly = true)
    public UrlAnalytics analytics(String code) {
        ShortUrl url = get(code);
        Instant lastClick = clicks.findLastClickedAt(url).orElse(null);
        return new UrlAnalytics(code, url.getClickCount(), lastClick, url.isExpiredAt(clock.instant()));
    }
}
